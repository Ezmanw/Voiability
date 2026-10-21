package com.voiability.models

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voiability.R
import com.voiability.VoiApp
import com.voiability.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the process alive with a progress notification while models download. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.downloads_channel), NotificationManager.IMPORTANCE_LOW)
        )
        ServiceCompat.startForeground(
            this, ID, build(0f, true),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        val models = VoiApp.graph.models
        scope.launch {
            models.states.collectLatest { states ->
                val active = states.values.filterIsInstance<ModelState.Downloading>()
                if (active.isEmpty()) {
                    stopSelf()
                } else {
                    nm.notify(ID, build(active.map { it.progress }.average().toFloat(), false))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(progress: Float, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_voi)
            .setContentTitle(getString(R.string.downloading_models))
            .setProgress(100, (progress * 100).toInt(), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val ID = 7

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
