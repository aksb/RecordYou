package com.bnyro.recorder.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
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
import de.robv.android.xposed.callbacks.XCallback

/**
 * 息屏音量键手势（system_server 侧），两套功能共用一条按键序列状态机：
 *
 * ① 录制中长按单键停止：只在「录制中 + 停止开关开启 +（息屏 或 无障碍不可用）」
 * 时接管：吞掉 DOWN/重复/UP，达到设定时长后发广播让 App 停止录制；短按在
 * UP 时补一次音量调节，音量值和音量条都不会被这次长按改变。
 *
 * ② 空闲时双键长按快捷录音：总开关开启且息屏时，第一个音量键 DOWN 后开约
 * 250ms 观察窗，另一个音量键在窗口内按下即视为双键组合，持续按住达到设定
 * 时长后亮屏并发广播让 App 弹出锁屏录音页；只是单键短按则在 UP 时补调音量，
 * 窗口关闭后长按重复事件照常放行，保持音量连调手感。录制中触发同一手势
 * 时由 App 侧转为「停止」。
 *
 * 亮屏时不拦截单键停止（交给 App 里的无障碍服务），但无障碍服务不可用时
 * 两种状态都接管。
 *
 * 用最高优先级注册：另一个常见模块（长按音量键切歌等）hook 的是同一个方法，
 * Xposed 的 before hook 按优先级从高到低执行、遇到 setResult 就终止后续 hook。
 * 接管期间必须由我们先吞掉按键，否则可能被对方抢走导致功能失效，
 * 或者两边同时触发。未接管时我们不做任何拦截，不影响其它模块。
 */
object VolumeKeyHook : XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
    const val ACTION_STOP = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STOP"

    /** 双键长按快捷录音：录制中则由 App 转为停止 */
    private const val ACTION_QUICK_START = "com.bnyro.recorder.action.XPOSED_QUICK_RECORD_START"
    private const val ACTION_STATE = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_STATE"
    /** App 推送的功能配置（快捷录音总开关等），与录制状态无关、不过期 */
    private const val ACTION_CONFIG = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_CONFIG"
    /** App 请求立即熄屏（锁屏录音页的熄屏倒计时到期） */
    private const val ACTION_GO_SLEEP = "com.bnyro.recorder.action.XPOSED_GO_SLEEP"
    /** App 探活：问模块在不在，收到就回 pong，与录制状态无关 */
    private const val ACTION_PING = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_PING"
    private const val ACTION_PONG = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_PONG"
    private val STATE_PERMISSION = BuildConfig.APPLICATION_ID + ".permission.VOLUME_KEY_STATE"
    private const val EXTRA_ACTIVE = "active"
    private const val EXTRA_HOLD_MS = "holdMs"
    private const val EXTRA_ACCESSIBILITY = "accessibility"
    private const val EXTRA_STOP_ENABLED = "stopEnabled"
    private const val EXTRA_QUICK_RECORD = "quickRecord"
    private const val EXTRA_QUICK_HOLD_MS = "quickHoldMs"
    /** 锁屏录音页是否正在显示：页面常亮，isInteractive 此时为 true，需要靠它武装双键手势 */
    private const val EXTRA_QUICK_PAGE = "quickPage"

    /** 心跳间隔 5 秒，20 秒收不到就视为录制结束，避免永久吞掉音量键 */
    private const val STATE_TIMEOUT_MS = 20_000L
    private const val MIN_HOLD_MS = 300
    private const val MAX_HOLD_MS = 3000
    private const val DEFAULT_HOLD_MS = 800

    /** 双键识别窗口：第二个音量键必须在这个时间内按下 */
    private const val DUAL_WINDOW_MS = 250L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    private var active = false

    @Volatile
    private var stopEnabled = false

    @Volatile
    private var quickRecordEnabled = false

    /** 锁屏录音页正在显示：此时屏幕算亮屏，双键手势也要武装（停止/再次开始） */
    @Volatile
    private var quickPageShowing = false

    @Volatile
    private var accessibilityEnabled = false

    @Volatile
    private var holdMs = DEFAULT_HOLD_MS

    /** 双键长按的独立时长，与单键长按停止分开设置 */
    @Volatile
    private var quickHoldMs = DEFAULT_HOLD_MS

    @Volatile
    private var expireAt = 0L

    @Volatile
    private var receiverRegistered = false

    private var appContext: Context? = null

    @Volatile
    private var pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN

    @Volatile
    private var secondKeyCode = KeyEvent.KEYCODE_UNKNOWN

    @Volatile
    private var longPressTriggered = false

    /** 当前序列是否为快捷录音接管（决定重复事件放行与双键识别） */
    @Volatile
    private var quickSeq = false

    /** 当前序列是否同时武装了单键长按停止 */
    @Volatile
    private var seqStopArmed = false

    @Volatile
    private var dualWindowOpen = false

    /** 双键已组合成功，正在计时 */
    @Volatile
    private var dualActive = false

    /** 双键长按已触发，松手时不再补调音量 */
    @Volatile
    private var dualTriggered = false

    /** 双键同时按下的起始时刻（elapsedRealtime），供 repeat 兜底判定按住时长 */
    private var dualHoldStartAt = 0L

    /** 单键序列起点的时刻（elapsedRealtime），供长按停止的 repeat 兜底判定 */
    private var seqStartAt = 0L

    /** 本序列组合过双键（即使未达到长按时长），松手时不补调音量 */
    @Volatile
    private var dualAttempted = false

    /** 快捷录音序列里放行过长按重复事件，松手时不再补调音量 */
    @Volatile
    private var repeatForwarded = false

    private var pendingRunnable: Runnable? = null
    private var dualWindowRunnable: Runnable? = null
    private var dualHoldRunnable: Runnable? = null
    private var pendingHandler: Handler? = null

    /** 长按判定期间短暂持有的 CPU 唤醒锁（带超时），防止深度休眠下判定窗口被挂起 */
    private var sequenceWakeLock: PowerManager.WakeLock? = null

    private var lastLoggedState: String? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_STATE -> runCatching {
                    active = intent.getBooleanExtra(EXTRA_ACTIVE, false)
                    stopEnabled = intent.getBooleanExtra(EXTRA_STOP_ENABLED, false)
                    accessibilityEnabled = intent.getBooleanExtra(EXTRA_ACCESSIBILITY, false)
                    holdMs = intent.getIntExtra(EXTRA_HOLD_MS, holdMs).coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
                    expireAt = if (active) SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS else 0L

                    val snapshot = "active=$active stop=$stopEnabled a11y=$accessibilityEnabled hold=$holdMs"
                    if (snapshot != lastLoggedState) {
                        lastLoggedState = snapshot
                        XposedBridge.log("[RecordYou] state $snapshot")
                    }
                }
                ACTION_CONFIG -> runCatching {
                    val enabled = intent.getBooleanExtra(EXTRA_QUICK_RECORD, false)
                    quickHoldMs = intent.getIntExtra(EXTRA_QUICK_HOLD_MS, quickHoldMs)
                        .coerceIn(MIN_HOLD_MS, MAX_HOLD_MS)
                    quickPageShowing = intent.getBooleanExtra(EXTRA_QUICK_PAGE, false)
                    if (enabled != quickRecordEnabled) {
                        quickRecordEnabled = enabled
                        XposedBridge.log("[RecordYou] quick record enabled=$enabled")
                    }
                }
                // 熄屏请求：锁屏录音页的倒计时到期，立即关屏
                ACTION_GO_SLEEP -> runCatching { goToSleep(context) }
                // 探活应答：只证明模块注入成功，不改变任何录制状态
                ACTION_PING -> runCatching {
                    context?.sendBroadcast(
                        Intent(ACTION_PONG).setPackage(BuildConfig.APPLICATION_ID)
                    )
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

        val handler = (XposedHelpers.getObjectField(param.thisObject, "mHandler") as? Handler)
            ?: mainHandler

        when (event.action) {
            KeyEvent.ACTION_DOWN -> handleDown(param, context, handler, event)
            KeyEvent.ACTION_UP -> handleUp(param, context, event)
        }
    }

    private fun handleDown(
        param: MethodHookParam,
        context: Context,
        handler: Handler,
        event: KeyEvent
    ) {
        val interactive = isInteractive(context)
        val sequenceActive = pendingKeyCode != KeyEvent.KEYCODE_UNKNOWN

        // 排查「息屏待机久了双键失效」：只在快捷录音开启时记录按键起点的
        // 原始屏幕状态。武装判断不成立时原有日志打不出来，这里必须前置，
        // 否则拿到的是「零日志」无法区分「hook 没进」和「条件不满足」
        if (event.repeatCount == 0 && quickRecordEnabled) {
            logInteractivityDiag(context, event.keyCode)
        }

        if (event.repeatCount > 0) {
            if (!sequenceActive) return
            // 深度休眠（Doze/suspend）下 system_server 主线程的 uptime 停走，
            // postDelayed 的定时器可能整段跑不完，导致长按判定失效。
            // 长按 repeat 是硬件输入事件（本身唤醒 CPU），用它兜底判定时长。
            if (dualActive && !dualTriggered) {
                if (SystemClock.elapsedRealtime() - dualHoldStartAt >= quickHoldMs) {
                    triggerQuickRecord(context)
                }
                param.setResult(0)
                return
            }
            // 单键「长按停止」同理：录音期间虽有音频 wakelock 兜着，
            // 但息屏后若 wakelock 被系统放宽仍可能丢失定时器，一并兜底
            if (seqStopArmed && !longPressTriggered) {
                if (SystemClock.elapsedRealtime() - seqStartAt >= holdMs) {
                    triggerSingleStop(context)
                }
                param.setResult(0)
                return
            }
            // 快捷录音序列在双键窗口关闭后放行长按重复事件，
            // 保持「按住音量键连续调节」的系统手感
            if (quickSeq && !seqStopArmed && !dualWindowOpen && !dualActive) {
                repeatForwarded = true
                return
            }
            param.setResult(0)
            return
        }

        if (sequenceActive) {
            // 观察窗内按下另一个音量键：升级为双键组合，开始双键长按计时
            if (quickSeq && dualWindowOpen && !dualActive &&
                event.keyCode != pendingKeyCode
            ) {
                dualActive = true
                dualAttempted = true
                secondKeyCode = event.keyCode
                dualHoldStartAt = SystemClock.elapsedRealtime()
                cancelDualWindow()
                // 双键接管后取消单键长按停止，双键的结果由 App 侧决定
                cancelPending()
                val runnable = Runnable { onDualHoldTimeout(context) }
                dualHoldRunnable = runnable
                pendingHandler = handler
                handler.postDelayed(runnable, quickHoldMs.toLong())
                XposedBridge.log("[RecordYou] dual volume keys detected")
            }
            param.setResult(0)
            return
        }

        // 序列起点：决定本次要不要接管
        // 双键武装条件：
        //  - 息屏：开始/停止都可用（唯一允许「开始录音」的场景）
        //  - 锁屏录音页显示中（页面常亮）：仅录音进行时武装，用于双键停止；
        //    空闲的「录制完成」页上不武装——否则快速连续双键会和录音启动的
        //    异步状态翻转竞争，导致重复启动/无法停止的时序混乱
        val stopArmed = isActiveNow() && stopEnabled && (!interactive || !accessibilityEnabled)
        val quickArmed = quickRecordEnabled &&
            (!interactive || (quickPageShowing && isActiveNow()))
        if (!stopArmed && !quickArmed) return

        XposedBridge.log(
            "[RecordYou] DOWN key=${event.keyCode} interactive=$interactive " +
                "active=${isActiveNow()} stop=$stopArmed quick=$quickArmed a11y=$accessibilityEnabled"
        )

        pendingKeyCode = event.keyCode
        secondKeyCode = KeyEvent.KEYCODE_UNKNOWN
        longPressTriggered = false
        seqStopArmed = stopArmed
        quickSeq = quickArmed
        dualWindowOpen = false
        dualActive = false
        dualTriggered = false
        dualAttempted = false
        repeatForwarded = false
        pendingHandler = handler
        seqStartAt = SystemClock.elapsedRealtime()
        // 深度休眠时 system_server 处理完 DOWN 后会立刻再次挂起，postDelayed
        // 定时器和按键 repeat 都等不到，长按判定必然失效。序列期间短暂持有
        // CPU 唤醒锁（自带超时），保证判定窗口内系统不会再次休眠。
        acquireSequenceWakeLock(context)

        if (stopArmed) {
            val runnable = Runnable { onHoldTimeout(context, event.keyCode) }
            pendingRunnable = runnable
            handler.postDelayed(runnable, holdMs.toLong())
        }
        if (quickArmed) {
            dualWindowOpen = true
            val runnable = Runnable { dualWindowOpen = false }
            dualWindowRunnable = runnable
            handler.postDelayed(runnable, DUAL_WINDOW_MS)
        }
        // 目标方法在新版本返回 int，必须给 0（吞键），不能给 null
        param.setResult(0)
    }

    private fun handleUp(param: MethodHookParam, context: Context, event: KeyEvent) {
        val sequenceActive = pendingKeyCode != KeyEvent.KEYCODE_UNKNOWN
        // 没接管过这个序列就放行；接管过则必须吞掉 UP，避免系统补调音量
        if (!sequenceActive) return

        // 先松开的是第二个键：结束双键判定，主键序列继续
        if (event.keyCode == secondKeyCode) {
            param.setResult(0)
            // 兜底：计时与 repeat 在深度休眠下都可能失效，松手时补判一次
            if (dualActive && !dualTriggered &&
                SystemClock.elapsedRealtime() - dualHoldStartAt >= quickHoldMs
            ) {
                XposedBridge.log("[RecordYou] dual hold confirmed on UP (fallback)")
                triggerQuickRecord(context)
            }
            secondKeyCode = KeyEvent.KEYCODE_UNKNOWN
            dualActive = false
            cancelDualHold()
            return
        }

        // 序列外的其它键（理论上不会出现），吞掉保持序列一致
        if (event.keyCode != pendingKeyCode) {
            param.setResult(0)
            return
        }

        param.setResult(0)
        cancelPending()
        cancelDualWindow()
        cancelDualHold()
        // 兜底：同上。双键优先，且只要组合过双键就不再走单键停止
        if (dualActive && !dualTriggered) {
            if (SystemClock.elapsedRealtime() - dualHoldStartAt >= quickHoldMs) {
                XposedBridge.log("[RecordYou] dual hold confirmed on UP (fallback)")
                triggerQuickRecord(context)
            }
        } else if (seqStopArmed && !longPressTriggered && !dualAttempted) {
            if (SystemClock.elapsedRealtime() - seqStartAt >= holdMs) {
                XposedBridge.log("[RecordYou] single hold confirmed on UP (fallback)")
                triggerSingleStop(context)
            }
        }
        // 单键短按（未触发任何长按、未放行过重复事件、未组合过双键）：
        // 补一次音量调节，短按体验和平时完全一致
        if (!longPressTriggered && !dualAttempted && !repeatForwarded) {
            adjustVolume(param, pendingKeyCode)
        }
        resetSequence()
    }

    /**
     * 诊断日志：记录双键识别所依赖的原始屏幕状态值。
     * 用于定位「熄屏待机一段时间后双键失效」——重点看 pmInteractive、
     * displayState 与 keyguardLocked 三个值在正常/失效两种状态下是否不同。
     */
    private fun logInteractivityDiag(context: Context, keyCode: Int) {
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = displayManager?.displays?.firstOrNull()
            val keyguardManager =
                context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            XposedBridge.log(
                "[RecordYou] DIAG key=$keyCode pmInteractive=${powerManager?.isInteractive}" +
                    " displayState=${display?.state}" +
                    " keyguardLocked=${keyguardManager?.isKeyguardLocked}" +
                    " quick=$quickRecordEnabled stop=$stopEnabled a11y=$accessibilityEnabled" +
                    " page=$quickPageShowing active=${isActiveNow()}"
            )
        }
    }

    private fun resetSequence() {
        pendingKeyCode = KeyEvent.KEYCODE_UNKNOWN
        secondKeyCode = KeyEvent.KEYCODE_UNKNOWN
        longPressTriggered = false
        quickSeq = false
        seqStopArmed = false
        dualWindowOpen = false
        dualActive = false
        dualTriggered = false
        dualAttempted = false
        repeatForwarded = false
        releaseSequenceWakeLock()
    }

    /**
     * 序列期间短暂持有 CPU 唤醒锁。深度休眠下 system_server 处理完按键 DOWN
     * 会马上再次挂起，postDelayed 定时器与按键 repeat 都推进不了，长按判定
     * 就会失效（表现为连按多次偶尔成功）。带超时兜底，松手时立即释放。
     */
    private fun acquireSequenceWakeLock(context: Context) {
        releaseSequenceWakeLock()
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@runCatching
            sequenceWakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "RecordYou:volumeKeyHold"
            ).apply {
                setReferenceCounted(false)
                acquire(maxOf(holdMs, quickHoldMs).toLong() + 3_000L)
            }
        }.onFailure {
            XposedBridge.log("[RecordYou] sequence wake lock failed: $it")
        }
    }

    private fun releaseSequenceWakeLock() {
        runCatching { sequenceWakeLock?.takeIf { it.isHeld }?.release() }
        sequenceWakeLock = null
    }

    private fun onHoldTimeout(context: Context, keyCode: Int) {
        if (pendingKeyCode != keyCode) return
        pendingRunnable = null
        triggerSingleStop(context)
    }

    /**
     * 单键长按到时的统一触发入口。定时器路径与 repeat 兜底路径都走这里，
     * 用 longPressTriggered 保证只发一次停止。
     */
    private fun triggerSingleStop(context: Context) {
        if (longPressTriggered) return
        if (!isActiveNow() || !stopEnabled) return
        longPressTriggered = true
        cancelPending()
        sendStop(context)
        XposedBridge.log("[RecordYou] volume long press -> stop recording")
    }

    private fun onDualHoldTimeout(context: Context) {
        dualHoldRunnable = null
        if (!dualActive || pendingKeyCode == KeyEvent.KEYCODE_UNKNOWN) return
        triggerQuickRecord(context)
    }

    /**
     * 双键长按到时的统一触发入口。定时器路径（亮屏/未休眠时更精确）与
     * repeat 兜底路径（深度休眠下定时器不推进）都走这里，用 dualTriggered
     * 保证只触发一次；两条路径都在 system_server 主线程，无竞态。
     */
    private fun triggerQuickRecord(context: Context) {
        if (dualTriggered) return
        if (!quickRecordEnabled) return
        dualTriggered = true
        cancelDualHold()
        wakeUp(context)
        sendQuickStart(context)
        XposedBridge.log("[RecordYou] dual volume long press -> quick record")
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

    private fun sendQuickStart(context: Context) {
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_QUICK_START).setClassName(
                    BuildConfig.APPLICATION_ID,
                    "com.bnyro.recorder.receivers.XposedQuickRecordReceiver"
                )
            )
        }
    }

    /** system_server 持有 DEVICE_POWER，wakeUp 不受限制；SDK 里该方法是隐藏的，用反射调 */
    private fun wakeUp(context: Context) {
        runCatching {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@runCatching
            powerManager.javaClass
                .getMethod("wakeUp", Long::class.javaPrimitiveType)
                .invoke(powerManager, SystemClock.uptimeMillis())
        }.onFailure {
            XposedBridge.log("[RecordYou] wakeUp failed: $it")
        }
    }

    /** 同上：goToSleep 也是隐藏方法，Android 13 起废弃单参数版本，做两级回退 */
    private fun goToSleep(context: Context?) {
        val ctx = context ?: appContext ?: return
        runCatching {
            val powerManager = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return@runCatching
            val now = SystemClock.uptimeMillis()
            runCatching {
                powerManager.javaClass
                    .getMethod("goToSleep", Long::class.javaPrimitiveType)
                    .invoke(powerManager, now)
            }.onFailure {
                powerManager.javaClass
                    .getMethod(
                        "goToSleep",
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    .invoke(powerManager, now, 0, 0)
            }
            XposedBridge.log("[RecordYou] goToSleep by quick record countdown")
        }.onFailure {
            XposedBridge.log("[RecordYou] goToSleep failed: $it")
        }
    }

    private fun adjustVolume(param: MethodHookParam, keyCode: Int) {
        val context = (XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context)
            ?: appContext ?: return
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

    private fun cancelPending() {
        pendingRunnable?.let { runnable ->
            (pendingHandler ?: mainHandler).removeCallbacks(runnable)
        }
        pendingRunnable = null
    }

    private fun cancelDualWindow() {
        dualWindowRunnable?.let { runnable ->
            (pendingHandler ?: mainHandler).removeCallbacks(runnable)
        }
        dualWindowRunnable = null
        dualWindowOpen = false
    }

    private fun cancelDualHold() {
        dualHoldRunnable?.let { runnable ->
            (pendingHandler ?: mainHandler).removeCallbacks(runnable)
        }
        dualHoldRunnable = null
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
                // 同一个接收器同时处理录制状态同步、功能配置与探活 ping
                val filter = IntentFilter().apply {
                    addAction(ACTION_STATE)
                    addAction(ACTION_CONFIG)
                    addAction(ACTION_GO_SLEEP)
                    addAction(ACTION_PING)
                }
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
