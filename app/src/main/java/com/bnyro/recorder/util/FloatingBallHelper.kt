package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.bnyro.recorder.services.FloatingBallTile
import com.bnyro.recorder.services.OverlayBallService

/**
 * Turning the floating ball on/off is wired up from four different places
 * (the main screen's toggle button, the quick-settings tile, the "enable
 * floating ball" launcher shortcut, and the ball's own "close" button) -
 * this is the one place that logic lives, so all four stay in sync instead
 * of each reimplementing (and potentially drifting from) the same
 * permission-check-then-toggle flow.
 */
object FloatingBallHelper {
    /**
     * @return true if the ball was actually toggled, false if turning it on
     * was blocked by a missing overlay permission (the person has been sent
     * to the system permission screen in that case - there's no way to
     * request it silently, so the caller should just let them try again
     * once they're back).
     */
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        if (enabled) {
            if (Settings.canDrawOverlays(context)) {
                Preferences.edit { putBoolean(Preferences.floatingBallKey, true) }
                context.startService(Intent(context, OverlayBallService::class.java))
            } else {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
        } else {
            Preferences.edit { putBoolean(Preferences.floatingBallKey, false) }
            context.stopService(Intent(context, OverlayBallService::class.java))
        }

        FloatingBallTile.requestTileRefresh(context)
        return true
    }

    fun isEnabled(context: Context): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Preferences.prefs.getBoolean(Preferences.floatingBallKey, false) &&
            Settings.canDrawOverlays(context)
    }
}
