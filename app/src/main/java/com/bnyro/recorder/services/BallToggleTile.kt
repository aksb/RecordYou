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
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.util.FloatingBallHelper

/**
 * Toggle-style quick-settings tile for one floating ball: one tap turns it on,
 * another tap turns it back off. Kept in sync with the toggles on the main
 * screen via [requestTileRefresh], since neither those nor the automatic
 * "close the other ball" behaviour update tiles automatically.
 */
@RequiresApi(Build.VERSION_CODES.N)
abstract class BallToggleTile : TileService() {
    protected abstract val ballType: RecorderType

    override fun onStartListening() {
        super.onStartListening()
        refreshState()
    }

    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val currentlyEnabled = FloatingBallHelper.isEnabled(this, ballType)

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

        FloatingBallHelper.setEnabled(this, ballType, !currentlyEnabled)
        refreshState()
    }

    private fun refreshState() {
        val enabled = FloatingBallHelper.isEnabled(this, ballType)
        qsTile?.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        qsTile?.updateTile()
    }

    companion object {
        private const val PENDING_INTENT_REQUEST_CODE = 22

        /**
         * Tells the system to re-query both ball tiles. Call this any time a
         * ball is turned on/off from somewhere other than the tile itself
         * (main screen toggles, the other ball's tile).
         */
        fun requestTileRefresh(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
            val tiles = listOf(AudioBallTile::class.java, VideoBallTile::class.java)
            tiles.forEach { tile ->
                runCatching { requestListeningState(context, ComponentName(context, tile)) }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.N)
class AudioBallTile : BallToggleTile() {
    override val ballType = RecorderType.AUDIO
}

@RequiresApi(Build.VERSION_CODES.N)
class VideoBallTile : BallToggleTile() {
    override val ballType = RecorderType.VIDEO
}
