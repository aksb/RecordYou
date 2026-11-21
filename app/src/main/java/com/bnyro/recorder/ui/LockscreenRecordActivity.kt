package com.bnyro.recorder.ui

import android.app.Activity
import android.app.KeyguardManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.ui.theme.RecordYouTheme
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.XposedVolumeKeyState

private fun systemScreenOffSec(context: android.content.Context): Int = runCatching {
    Settings.System.getInt(
        context.contentResolver,
        Settings.System.SCREEN_OFF_TIMEOUT
    ) / 1000
}.getOrDefault(30).coerceAtLeast(5)

/** 录音开始的震动提示（可在设置中关闭） */
private fun vibrate(context: android.content.Context) {
    runCatching {
        val vibrator = context.getSystemService(android.os.Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                android.os.VibrationEffect.createOneShot(
                    250,
                    android.os.VibrationEffect.DEFAULT_AMPLITUDE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(250)
        }
    }
}

/**
 * 锁屏快捷录音的全屏页面：盖在锁屏之上，不解锁即可操作。
 *
 * 由 LSPosed 模块在「息屏 + 双键长按」后拉起（见 receivers/XposedQuickRecordReceiver）。
 * 两个状态页：
 *  - 录音中：动态时长 + 停止按钮
 *  - 录制完成：停止后显示，点「完成」关闭页面回到锁屏
 * 空闲（未录制）状态下熄屏会自动关闭本页，避免下次亮屏看到残留页面。
 *
 * 只支持话筒模式（内录需要 MediaProjection 弹窗，锁屏下无法确认），
 * 录制期间熄屏由页面自己的倒计时控制，录音服务本身不受影响。
 */
class LockscreenRecordActivity : ComponentActivity() {
    private val recorderModel: RecorderModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API 27 以下用旧窗口标志达到同样效果
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        // 熄屏时机由页面自己的倒计时控制（到期后请 LSPosed 模块 goToSleep），
        // 所以这里要顶住系统的自动熄屏
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 防御：功能开关被关掉后仍残留触发时不做任何事
        if (!Preferences.prefs.getBoolean(Preferences.quickRecordLockscreenKey, false)) {
            finish()
            return
        }

        handleIntent(intent)

        setContent {
            RecordYouTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    QuickRecordPage(recorderModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // 页面持 KEEP_SCREEN_ON，显示期间系统算「亮屏」，
        // 必须同步给模块，否则息屏武装条件不成立、双键手势失效
        XposedVolumeKeyState.setQuickPageShowing(this, true)
    }

    override fun onPause() {
        XposedVolumeKeyState.setQuickPageShowing(this, false)
        // 注意：这里不能做「空闲回收」。录音启动是异步的，从发起启动到
        // activeType 翻转之间有几百毫秒窗口，熄屏拉起页面过程中若发生
        // onPause 会把启动中的页面误判成空闲杀掉。回收逻辑由页面层的
        // ON_PAUSE 观察者按 stoppedOnce（确认开始过且已停止）判断
        super.onPause()
    }

    override fun onDestroy() {
        XposedVolumeKeyState.setQuickPageShowing(this, false)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        startPendingRecording()
    }

    /**
     * 发起话筒录音（自动触发与「再录一次」按钮共用）。
     * 启动是异步的：发起后到状态翻转之间（百毫秒级）重复请求会创建重复的
     * MediaRecorder 实例，3 秒内直接忽略；收尾期间由 canStart 统一拦截
     * （Toast 提示），启动失败不翻转时 3 秒后仍允许重试。
     */
    fun startPendingRecording() {
        if (recorderModel.activeType != null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - startPendingAt < START_PENDING_WINDOW_MS) return
        startPendingAt = now
        recorderModel.startAudioRecorder(this, forceMicrophone = true)
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
        private const val START_PENDING_WINDOW_MS = 3_000L
    }

    /** 上一次发起自动启动的时间戳；0 表示从未发起过 */
    @Volatile
    private var startPendingAt = 0L

    /** 是否处于「已发起启动、状态还没翻转」的窗口内 */
    fun isStartPending(): Boolean =
        android.os.SystemClock.elapsedRealtime() - startPendingAt < START_PENDING_WINDOW_MS
}

@Composable
private fun QuickRecordPage(recorderModel: RecorderModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeType = recorderModel.activeType

    // 录制状态转换：有→无 = 刚停止，切「录制完成」页；无→有 = 录音开始，
    // 清掉「已完成」标记（否则第二次录音熄屏时会被当成完成页回收）并震动提示
    var stoppedOnce by remember { mutableStateOf(false) }
    var prevActive by remember { mutableStateOf(activeType) }
    LaunchedEffect(activeType) {
        if (prevActive != null && activeType == null) {
            stoppedOnce = true
        } else if (prevActive == null && activeType == RecorderType.AUDIO) {
            stoppedOnce = false
            if (Preferences.prefs.getBoolean(Preferences.quickRecordVibrateKey, true)) {
                vibrate(context)
            }
        }
        prevActive = activeType
    }

    // 熄屏倒计时：默认取系统熄屏时间，点模板按钮改为指定分钟数。
    // 页面持了 KEEP_SCREEN_ON，到期后由 LSPosed 模块执行 goToSleep
    var remainingSec by remember { mutableStateOf(systemScreenOffSec(context)) }
    var sleepRequested by remember { mutableStateOf(false) }
    // 唤醒（onResume）时重置倒计时重新走，否则 goToSleep 一次后倒计时停在 00:00；
    // 熄屏（onPause）时若已处于「录制完成」状态则关闭页面，避免下次亮屏
    // 看到残留页。用 stoppedOnce 判断而非 activeType，避开录音启动的异步窗口
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    remainingSec = systemScreenOffSec(context)
                    sleepRequested = false
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // 只有「确认停止过 + 当前确实没在录 + 不在启动窗口内」
                    // 才是真正的空闲完成页。启动窗口判断避开「再录一次」
                    // 后状态尚未翻转时被误杀
                    val host = context as? LockscreenRecordActivity
                    if (stoppedOnce && recorderModel.activeType == null &&
                        host?.isStartPending() != true
                    ) {
                        host?.finish()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            if (remainingSec > 0) remainingSec--
            if (remainingSec == 0 && !sleepRequested) {
                sleepRequested = true
                XposedVolumeKeyState.requestScreenOff(context)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(
                // 双击空白处熄屏，对应系统锁屏的双击熄屏习惯。
                // 按钮自己消费点击事件，手势不会冒泡到这里，
                // 所以「除了按钮以外任意位置」天然成立
                if (Preferences.prefs.getBoolean(Preferences.doubleTapScreenOffKey, true)) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { XposedVolumeKeyState.requestScreenOff(context) }
                        )
                    }
                } else {
                    Modifier
                }
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 顶部按钮条：返回锁屏（关掉本页，录音若在跑不受影响）、
        // 解锁（弹系统原生认证界面，指纹/PIN 与系统锁屏一致）
        val activity = context as? Activity
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = { activity?.finish() }) {
                Text(stringResource(R.string.quick_record_back_to_lockscreen))
            }
            TextButton(onClick = {
                if (activity == null) return@TextButton
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val keyguardManager =
                        activity.getSystemService(KeyguardManager::class.java)
                    keyguardManager?.requestDismissKeyguard(
                        activity,
                        object : KeyguardManager.KeyguardDismissCallback() {
                            override fun onDismissSucceeded() {
                                activity.finish()
                            }
                        }
                    )
                } else {
                    activity.finish()
                }
            }) {
                Text(stringResource(R.string.quick_record_unlock))
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        when {
            activeType == RecorderType.AUDIO -> {
                recorderModel.recordedTime?.let {
                    Text(
                        text = DateUtils.formatElapsedTime(it / 10),
                        style = MaterialTheme.typography.displayLarge
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                }
                Text(
                    text = stringResource(R.string.quick_record_recording),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(40.dp))
                Button(
                    onClick = { recorderModel.stopRecording(context) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEE665B),
                        contentColor = Color.White
                    )
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.stop))
                }
            }

            stoppedOnce -> {
                Text(
                    text = stringResource(R.string.quick_record_finished),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(modifier = Modifier.height(40.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        // 重新开始录音：倒计时同步重置为系统熄屏时间
                        remainingSec = systemScreenOffSec(context)
                        sleepRequested = false
                        (context as? LockscreenRecordActivity)?.startPendingRecording()
                    }) {
                        Text(stringResource(R.string.quick_record_again))
                    }
                    Button(onClick = { activity?.finish() }) {
                        Text(stringResource(R.string.quick_record_done))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
        // 立即熄屏：复用倒计时到期的 goToSleep 链路。录音中点击则录音
        // 继续（页面保留）；录制完成页点击则页面随之回收
        OutlinedButton(onClick = {
            XposedVolumeKeyState.requestScreenOff(context)
        }) {
            Text(stringResource(R.string.quick_record_screen_off_now))
        }
        Spacer(modifier = Modifier.height(10.dp))
        // 熄屏倒计时：三个模板值在设置页可改
        Text(
            text = stringResource(
                R.string.quick_record_screen_off_in,
                DateUtils.formatElapsedTime(remainingSec.toLong())
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                Preferences.screenOffTemplate1Key to Preferences.screenOffTemplate1Default,
                Preferences.screenOffTemplate2Key to Preferences.screenOffTemplate2Default,
                Preferences.screenOffTemplate3Key to Preferences.screenOffTemplate3Default
            ).forEach { (key, def) ->
                val minutes = Preferences.prefs.getInt(key, def).coerceIn(1, 600)
                OutlinedButton(onClick = {
                    remainingSec = minutes * 60
                    sleepRequested = false
                }) {
                    Text(stringResource(R.string.quick_record_minutes, minutes))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
