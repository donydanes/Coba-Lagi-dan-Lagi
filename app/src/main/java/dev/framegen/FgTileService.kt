package dev.framegen

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Tile Quick Settings: ketuk untuk mulai (langsung ke dialog pilih game) atau berhenti. */
class FgTileService : TileService() {
    override fun onStartListening() { update() }

    override fun onClick() {
        if (FrameGenService.running) {
            startService(Intent(this, FrameGenService::class.java).setAction(FrameGenService.ACTION_STOP))
            return
        }
        val i = Intent(this, MainActivity::class.java)
            .putExtra("autostart", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(
            PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
    }

    private fun update() {
        val t = qsTile ?: return
        t.state = if (FrameGenService.running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "FrameGen"
        t.updateTile()
    }
}
