package com.bnyro.recorder.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.bnyro.recorder.R
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderControl
import com.bnyro.recorder.util.RecorderStatusHolder

/**
 * 音量键长按停止录制。
 *
 * 录制进行中时接管音量键：按住达到设定时长就停止当前录制；如果只是短按，
 * 由我们自己补一次音量调节，所以短按的行为和系统默认一致。
 *
 * 之所以自己补音量而不是先让系统调、触发后再还原：按住期间系统会连续
 * 调音量，而音量是按输出设备分别记录的，停下来后很难精确还原。
 * 直接吞掉按键序列就不存在这个问题。
 *
 * 需要用户在系统无障碍设置里开启本服务，并在应用设置里打开对应开关，
 * 两者都满足时才会接管按键。
 */
class VolumeKeyStopService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN
    private var longPressTriggered = false
    private var longPressRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 不请求过滤按键事件的话 onKeyEvent 收不到音量键
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return false
        }

        if (event.action == KeyEvent.ACTION_UP) {
            if (pendingKeyCode == KeyEvent.KEYCODE_UNKNOWN) return false
            // 没到长按时长：补一次音量调节，保持短按的原有手感
            if (!longPressTriggered) adjustVolume(pendingKeyCode)
            stopTracking()
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return false
        // 按住产生的重复事件：序列已经被接管，继续吞掉
        if (event.repeatCount > 0) {
            return pendingKeyCode != KeyEvent.KEYCODE_UNKNOWN
        }

        // 没有录制、或开关关闭时，音量键完全交给系统
        if (!isEnabled() || RecorderStatusHolder.activeType == null) return false

        pendingKeyCode = event.keyCode
        longPressTriggered = false
        val runnable = Runnable { onLongPress() }
        longPressRunnable = runnable
        handler.postDelayed(runnable, holdDurationMs())
        return true
    }

    override fun onDestroy() {
        cancelLongPress()
        super.onDestroy()
    }

    private fun isEnabled() =
        Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false)

    private fun holdDurationMs(): Long =
        Preferences.prefs.getInt(
            Preferences.volumeKeyStopHoldMsKey,
            Preferences.volumeKeyStopHoldDefaultMs
        ).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS).toLong()

    private fun stopTracking() {
        cancelLongPress()
        pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN
        longPressTriggered = false
    }

    private fun cancelLongPress() {
        longPressRunnable?.let(handler::removeCallbacks)
        longPressRunnable = null
    }

    private fun onLongPress() {
        longPressRunnable = null
        if (longPressTriggered) return
        // 按住期间开关可能被关掉、录制可能已经结束，触发前再确认一次
        if (!isEnabled()) return
        val type = RecorderStatusHolder.activeType ?: return
        longPressTriggered = true
        RecorderControl.requestStop(this, type)
        Toast.makeText(this, R.string.volume_key_stop_toast, Toast.LENGTH_SHORT).show()
    }

    private fun adjustVolume(keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        runCatching {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    companion object {
        private const val MIN_HOLD_MS = 300
        private const val MAX_HOLD_MS = 3000
    }
}
