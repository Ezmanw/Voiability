package com.voiability.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.voiability.overlay.VoiAccessibilityService

/** Things that live outside the app (permissions, system settings), re-read on every resume. */
data class SystemStatus(
    val mic: Boolean,
    val bubbleService: Boolean,
    val keyboardEnabled: Boolean,
    val keyboardSelected: Boolean,
    val totalRamGb: Double,
) {
    companion object {
        fun read(context: Context): SystemStatus {
            val imm = context.getSystemService(InputMethodManager::class.java)
            val enabledIme = imm.enabledInputMethodList.any { it.packageName == context.packageName }
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
            val mem = ActivityManager.MemoryInfo().also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
            return SystemStatus(
                mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                bubbleService = VoiAccessibilityService.isEnabled(context),
                keyboardEnabled = enabledIme,
                keyboardSelected = current.startsWith(context.packageName + "/"),
                totalRamGb = mem.totalMem / 1_073_741_824.0,
            )
        }
    }
}

@Composable
fun rememberSystemStatus(): SystemStatus {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(SystemStatus.read(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) status = SystemStatus.read(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return status
}

object SystemIntents {
    fun accessibility(context: Context) = context.startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun keyboards(context: Context) = context.startActivity(
        Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )

    fun pickKeyboard(context: Context) = context.getSystemService(InputMethodManager::class.java).showInputMethodPicker()

    fun appDetails(context: Context) = context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
