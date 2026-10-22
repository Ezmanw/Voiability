package com.voiability.overlay

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voiability.VoiApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Quick Settings tile that shows or hides the dictation bubble everywhere. */
class BubbleTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val settings = VoiApp.graph.settings
        scope.launch {
            settings.update { it.copy(bubble = it.bubble.copy(enabled = !it.bubble.enabled)) }
            refresh()
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val serviceOn = VoiAccessibilityService.isEnabled(this)
        val on = serviceOn && VoiApp.graph.settings.current.bubble.enabled
        tile.state = when {
            !serviceOn -> Tile.STATE_UNAVAILABLE
            on -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.subtitle = if (!serviceOn) "Turn on in the app" else if (on) "On" else "Off"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
