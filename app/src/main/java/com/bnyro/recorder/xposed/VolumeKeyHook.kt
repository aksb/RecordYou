package com.bnyro.recorder.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import com.bnyro.recorder.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 息屏长按音量键停止录制（system_server 侧）。
 *
 * 只在「息屏 + 收到 App 的录制状态」时接管音量键：吞掉 DOWN/重复/UP，
 * 达到设定时长后发广播让 App 停止录制；如果只是短按，UP 时补一次音量
 * 调节，所以音量值和音量条都不会被这次长按改变。
 *
 * 亮屏时不拦截（交给 App 里的无障碍服务），但无障碍服务不可用时两种状态都接管。
 */
object VolumeKeyHook : XC_MethodHook() {
    const val ACTION_STOP = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STOP"

    private const val ACTION_STATE = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STATE"
    private val STATE_PERMISSION = BuildConfig.APPLICATION_ID + ".permission.VOLUME_KEY_STATE"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_HOLD_MS = "holdMs"
    private const val EXTRA_ACCESSIBILITY = "accessibility"

    /** 心跳间隔 5 秒，20 秒收不到就视为录制结束，避免永久吞掉音量键 */
    private const val STATE_TIMEOUT_MS = 20_000L
    private const val MIN_HOLD_MS = 300
    private const val MAX_HOLD_MS = 3000
    private const val DEFAULT_HOLD_MS = 800

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var active = false

    @Volatile
    private var accessibilityEnabled = false

    @Volatile
    private var holdMs = DEFAULT_HOLD_MS

    @Volatile
    private var expireAt = 0L

    @Volatile
    private var receiverRegistered = false

    private var appContext: Context? = null

    @Volatile
    private var pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN

    @Volatile
    private var longPressTriggered = false

    private var pendingRunnable: Runnable? = null
    private var pendingHandler: Handler? = null

    private var lastLoggedState: String? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_STATE) return
            runCatching {
                active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
                accessibilityEnabled = intent.getBooleanExtra(EXTRA_ACCESSIBILITY, false)
                holdMs = intent.getIntExtra(EXTRA_HOLD_MS, holdMs).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
                expireAt = if (active) SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS else 0L

                val snapshot = "active=$active a11y=$accessibilityEnabled hold=$holdMs"
                if (snapshot != lastLoggedState) {
                    lastLoggedState = snapshot
                    XposedBridge.log("[RecordYou] state $snapshot")
                }
            }
        }
    }

    fun attachSystemContext(context: Context?) {
        val ctx = context ?: return
        appContext = ctx.applicationContext ?: ctx
        ensureReceiver(appContext!!)
        // system_server 启动早期 AMS 还没就绪，注册会失败，需要稍后重试
        if (!receiverRegistered) scheduleReceiverRetry(0)
    }

    private fun scheduleReceiverRetry(attempt: Int) {
        if (attempt > 6) return
        mainHandler.postDelayed({
            if (receiverRegistered) return@postDelayed
            val ctx = appContext ?: return@postDelayed
            ensureReceiver(ctx)
            if (!receiverRegistered) scheduleReceiverRetry(attempt + 1)
        }, 10_000L)
    }

    override fun beforeHookedMethod(param: MethodHookParam) {
        // 任何异常都不能抛回 system_server
        runCatching { handleKeyEvent(param) }.onFailure {
            XposedBridge.log("[RecordYou] key handling failed: $it")
        }
    }

    private fun handleKeyEvent(param: MethodHookParam) {
        val event = param.args.getOrNull(0) as? KeyEvent ?: return
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) {
            return
        }
        if (event.flags and KeyEvent.FLAG_FROM_SYSTEM == 0) return

        val context = (XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context)
            ?: appContext ?: return
        ensureReceiver(context)

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        // 通话等非普通音频场景不接管
        if (audioManager.mode != AudioManager.MODE_NORMAL) return

        val interactive = isInteractive(context)
        val sequenceActive = pendingKeyCode != KeyEvent.KEYCODE_UNKNOWN
        val handler = (XposedHelpers.getObjectField(param.thisObject, "mHandler") as? Handler)
            ?: mainHandler

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) {
                    if (sequenceActive) param.setResult(0)
                    return
                }
                if (event.repeatCount == 0) {
                    XposedBridge.log(
                        "[RecordYou] DOWN key=${event.keyCode} interactive=$interactive " +
                            "active=${isActiveNow()} a11y=$accessibilityEnabled seq=$sequenceActive"
                    )
                }
                if (sequenceActive) {
                    param.setResult(0)
                    return
                }
                // 无障碍负责亮屏，模块负责息屏；无障碍不可用时模块两种状态都接管
                if (!shouldHandle(interactive)) return

                pendingKeyCode = event.keyCode
                longPressTriggered = false
                val runnable = Runnable { onHoldTimeout(context, event.keyCode) }
                pendingRunnable = runnable
                pendingHandler = handler
                handler.postDelayed(runnable, holdMs.toLong())
                // 目标方法在新版本返回 int，必须给 0（吞键），不能给 null
                param.setResult(0)
            }

            KeyEvent.ACTION_UP -> {
                // 没接管过这个序列就放行；接管过则必须吞掉 UP，避免系统补调音量
                if (!sequenceActive) return
                param.setResult(0)
                cancelPending()
                if (!longPressTriggered) adjustVolume(context, pendingKeyCode)
                pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN
                longPressTriggered = false
            }
        }
    }

    private fun onHoldTimeout(context: Context, keyCode: Int) {
        if (pendingKeyCode != keyCode) return
        pendingRunnable = null
        if (!isActiveNow()) return
        longPressTriggered = true
        sendStop(context)
        XposedBridge.log("[RecordYou] volume long press -> stop recording")
    }

    private fun sendStop(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_STOP).setClassName(
                    BuildConfig.APPLICATION_ID,
                    "com.bnyro.recorder.receivers.XposedStopReceiver"
                )
            )
        }
    }

    private fun adjustVolume(context: Context, keyCode: Int) {
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            AudioManager.ADJUST_RAISE
        } else {
            AudioManager.ADJUST_LOWER
        }
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )
        }
    }

    private fun isActiveNow(): Boolean =
        active && SystemClock.elapsedRealtime() < expireAt

    /**
     * 有录制状态，且满足下列任一条件才接管：
     * - 息屏：无障碍收不到按键，必须由模块处理；
     * - 无障碍不可用：亮屏也由模块兜底。
     */
    private fun shouldHandle(interactive: Boolean): Boolean {
        if (!isActiveNow()) return false
        return !interactive || !accessibilityEnabled
    }

    private fun cancelPending() {
        pendingRunnable?.let { runnable ->
            (pendingHandler ?: mainHandler).removeCallbacks(runnable)
        }
        pendingRunnable = null
        pendingHandler = null
    }

    private fun isInteractive(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return true
        if (!powerManager.isInteractive) return false

        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return true
        val display = displayManager.displays.firstOrNull() ?: return true
        return display.state != Display.STATE_OFF &&
            display.state != Display.STATE_DOZE &&
            display.state != Display.STATE_DOZE_SUSPEND
    }

    private fun ensureReceiver(context: Context) {
        if (receiverRegistered) return
        synchronized(this) {
            if (receiverRegistered) return
            runCatching {
                val filter = IntentFilter(ACTION_STATE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        stateReceiver,
                        filter,
                        STATE_PERMISSION,
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    context.registerReceiver(stateReceiver, filter, STATE_PERMISSION, null)
                }
                receiverRegistered = true
                XposedBridge.log("[RecordYou] state receiver registered")
            }.onFailure {
                XposedBridge.log("[RecordYou] state receiver register failed: $it")
            }
        }
    }
}
