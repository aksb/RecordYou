package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * 把「正在录制 + 音量键长按时长 + 功能开关」同步给 system_server 里的 LSPosed 模块。
 *
 * 模块没有可靠办法判断 App 进程是否还活着，所以这里用「立即发一次 + 每 5 秒
 * 心跳」的方式，模块侧 20 秒收不到就自动失效。这样即使 App 被杀或崩溃，
 * 息屏音量键最多被占用几十秒，不会永久吞键。
 *
 * active 上报的是真实录制状态（不论长按停止开关是否开启），模块据此做
 * 「录制中 / 空闲」分流：单键长按停止是否武装由 stopEnabled 决定，
 * 双键长按快捷录音是否武装由配置广播里的 quickRecord 决定。
 */
object XposedVolumeKeyState {
    private const val ACTION = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STATE"
    private const val ACTION_CONFIG = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_CONFIG"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_HOLD_MS = "holdMs"
    private const val EXTRA_ACCESSIBILITY = "accessibility"
    private const val EXTRA_STOP_ENABLED = "stopEnabled"
    private const val EXTRA_QUICK_RECORD = "quickRecord"
    private const val EXTRA_QUICK_HOLD_MS = "quickHoldMs"
    /** 锁屏录音页是否正在显示：页面常亮时屏幕算「亮屏」，模块需要靠它武装双键手势 */
    private const val EXTRA_QUICK_PAGE = "quickPage"
    private const val ACTION_GO_SLEEP = "com.bnyro.recorder.action.XPOSED_GO_SLEEP"
    private const val HEARTBEAT_INTERVAL_MS = 5_000L

    @Volatile
    private var quickPageShowing = false

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
            val active = !forceInactive && RecorderStatusHolder.isRecording
            val holdMs = Preferences.prefs.getInt(
                Preferences.volumeKeyStopHoldMsKey,
                Preferences.volumeKeyStopHoldDefaultMs
            ).coerceIn(300, 3000)

            context.sendBroadcast(
                Intent(ACTION)
                    .putExtra(EXTRA_ACTIVE, active)
                    .putExtra(EXTRA_HOLD_MS, holdMs)
                    .putExtra(
                        EXTRA_STOP_ENABLED,
                        Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false)
                    )
                    .putExtra(EXTRA_ACCESSIBILITY, VolumeKeyStopHelper.isReady(context))
            )
        }
    }

    /**
     * 推送快捷录音总开关给模块。配置不随心跳过期，所以要在开关变化、
     * 应用启动、开机完成时都补发一次（模块只在 system_server 里存活，
     * 手机重启后状态丢失，必须由开机广播重新同步）。
     */
    fun syncQuickConfig(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_CONFIG)
                    .putExtra(
                        EXTRA_QUICK_RECORD,
                        Preferences.prefs.getBoolean(Preferences.quickRecordLockscreenKey, false)
                    )
                    .putExtra(
                        EXTRA_QUICK_HOLD_MS,
                        Preferences.prefs.getInt(
                            Preferences.quickRecordHoldMsKey,
                            Preferences.volumeKeyStopHoldDefaultMs
                        ).coerceIn(300, 3000)
                    )
                    .putExtra(EXTRA_QUICK_PAGE, quickPageShowing)
            )
        }
    }

    /**
     * 锁屏录音页前后台同步：页面持 KEEP_SCREEN_ON，系统「亮屏」期间模块的
     * 息屏判断不成立，必须额外告诉模块「页面在显示」来武装双键手势。
     */
    fun setQuickPageShowing(context: Context, showing: Boolean) {
        if (quickPageShowing == showing) return
        quickPageShowing = showing
        syncQuickConfig(context)
    }

    /** 让模块调用 system_server 的 PowerManager.goToSleep() 立即熄屏 */
    fun requestScreenOff(context: Context) {
        runCatching {
            context.applicationContext.sendBroadcast(Intent(ACTION_GO_SLEEP))
        }
    }
}
