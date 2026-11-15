package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.services.AudioBallService
import com.bnyro.recorder.services.BallToggleTile
import com.bnyro.recorder.services.VideoBallService

/**
 * Turning the floating balls on/off is wired up from several different places
 * (the main screen's toggles, the quick-settings tiles and the launcher
 * shortcuts) - this is the one place that logic lives, so all of them stay in
 * sync instead of each reimplementing (and potentially drifting from) the
 * same permission-check-then-toggle flow.
 *
 * Only one ball may be shown at a time: enabling one automatically closes the
 * other, unless a recording of the other type is currently running - in that
 * case the request is refused rather than interrupting the recording.
 */
object FloatingBallHelper {
    /**
     * @return true if the state was actually changed, false if turning it on
     * was blocked by a missing overlay permission (the person has been sent
     * to the system permission screen in that case) or by a running recording
     * of the other type.
     */
    fun setEnabled(context: Context, type: RecorderType, enabled: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        if (enabled) {
            val otherType = otherType(type)
            if (RecorderStatusHolder.stateOf(otherType) == RecorderState.ACTIVE) {
                Toast.makeText(context, R.string.floating_ball_other_recording, Toast.LENGTH_SHORT)
                    .show()
                return false
            }
            if (!Settings.canDrawOverlays(context)) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return false
            }
            // 两个球不能同时显示：先关掉另一个（它一定没在录制，否则上面已经返回了）
            if (Preferences.prefs.getBoolean(keyOf(otherType), false)) {
                stopBall(context, otherType)
            }
            Preferences.edit { putBoolean(keyOf(type), true) }
            context.startService(Intent(context, serviceClass(type)))
        } else {
            stopBall(context, type)
        }

        BallToggleTile.requestTileRefresh(context)
        return true
    }

    fun isEnabled(context: Context, type: RecorderType): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Preferences.prefs.getBoolean(keyOf(type), false) &&
            Settings.canDrawOverlays(context)
    }

    private fun stopBall(context: Context, type: RecorderType) {
        Preferences.edit { putBoolean(keyOf(type), false) }
        context.stopService(Intent(context, serviceClass(type)))
    }

    private fun keyOf(type: RecorderType): String = when (type) {
        RecorderType.AUDIO -> Preferences.audioBallKey
        else -> Preferences.videoBallKey
    }

    private fun serviceClass(type: RecorderType): Class<*> = when (type) {
        RecorderType.AUDIO -> AudioBallService::class.java
        else -> VideoBallService::class.java
    }

    private fun otherType(type: RecorderType): RecorderType =
        if (type == RecorderType.AUDIO) RecorderType.VIDEO else RecorderType.AUDIO
}
