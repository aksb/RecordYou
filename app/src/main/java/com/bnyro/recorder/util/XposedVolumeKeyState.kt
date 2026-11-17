package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 把「正在录制 + 音量键长按时长」同步给 system_server 里的 LSPosed 模块。
 *
 * 模块没有可靠办法判断 App 进程是否还活着，所以这里用「立即发一次 + 每 5 秒
 * 心跳」的方式，模块侧 20 秒收不到就自动失效。这样即使 App 被杀或崩溃，
 * 息屏音量键最多被占用几十秒，不会永久吞键。
 */
object XposedVolumeKeyState {
    private const val ACTION = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STATE"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_HOLD_MS = "holdMs"
    private const val EXTRA_ACCESSIBILITY = "accessibility"
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var running = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (!running) return
            appContext?.let { sendState(it) }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    /** 录制开始时调用，重复调用只会立即补发一次状态 */
    fun start(context: Context) {
        val app = context.applicationContext
        appContext = app
        sendState(app)
        if (running) return
        running = true
        handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)
    }

    /** 最后一种录制结束时调用，必须显式通知模块失效 */
    fun stop(context: Context) {
        running = false
        handler.removeCallbacks(heartbeat)
        appContext = null
        sendState(context.applicationContext, forceInactive = true)
    }

    private fun sendState(context: Context, forceInactive: Boolean = false) {
        runCatching {
            val active = !forceInactive &&
                Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false) &&
                RecorderStatusHolder.isRecording
            val holdMs = Preferences.prefs.getInt(
                Preferences.volumeKeyStopHoldMsKey,
                Preferences.volumeKeyStopHoldDefaultMs
            ).coerceIn(300, 3000)

            context.sendBroadcast(
                Intent(ACTION)
                    .putExtra(EXTRA_ACTIVE, active)
                    .putExtra(EXTRA_HOLD_MS, holdMs)
                    .putExtra(EXTRA_ACCESSIBILITY, VolumeKeyStopHelper.isReady(context))
            )
        }
    }
}
