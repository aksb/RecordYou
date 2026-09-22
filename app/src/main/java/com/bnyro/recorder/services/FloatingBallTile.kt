package com.bnyro.recorder.services

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.bnyro.recorder.util.FloatingBallHelper
import com.bnyro.recorder.util.Preferences

/**
 * Toggle-style quick-settings tile for the floating ball: one tap turns it
 * on, another tap turns it back off. Kept in sync with the toggle button on
 * the main screen and with the ball's own "close" button via
 * [requestTileRefresh], since none of those update the tile automatically.
 */
@RequiresApi(Build.VERSION_CODES.N)
class FloatingBallTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val currentlyEnabled = Preferences.prefs.getBoolean(Preferences.floatingBallKey, false)

        if (!currentlyEnabled && !Settings.canDrawOverlays(this)) {
            // Can't request a runtime permission from a tile - send the user
            // to the system permission screen instead, collapsing the shade
            // for a cleaner handoff. They'll need to tap the tile again
            // afterwards to actually turn the ball on.
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this, PENDING_INTENT_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                startActivityAndCollapse(intent)
            }
            return
        }

        FloatingBallHelper.setEnabled(this, !currentlyEnabled)
        refreshState()
    }

    private fun refreshState() {
        val enabled = FloatingBallHelper.isEnabled(this)
        qsTile?.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    companion object {
        private const val PENDING_INTENT_REQUEST_CODE = 22

        /**
         * Tells the system to re-query this tile's state. Call this any time
         * the floating ball is turned on/off from somewhere other than the
         * tile itself (main screen toggle, the ball's own close button),
         * otherwise the tile would keep showing a stale icon.
         */
        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            runCatching {
                requestListeningState(context, ComponentName(context, FloatingBallTile::class.java))
            }
        }
    }
}
