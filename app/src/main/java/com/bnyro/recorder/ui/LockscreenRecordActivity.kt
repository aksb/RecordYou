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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.ui.theme.RecordYouTheme
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.XposedVolumeKeyState

/**
 * 锁屏快捷录音的全屏页面：盖在锁屏之上，不解锁即可操作。
 *
 * 由 LSPosed 模块在「息屏 + 双键长按」后拉起（见 receivers/XposedQuickRecordReceiver）。
 * 三个状态页：
 *  - 录音中：动态时长 + 停止按钮
 *  - 录制完成：停止后显示，点「完成」回到等待页
 *  - 等待录音：点「开始」再次录音
 *
 * 只支持话筒模式（内录需要 MediaProjection 弹窗，锁屏下无法确认），
 * 录制期间熄屏由系统超时控制，录音服务本身不受影响。
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

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        // 已有录制在进行时不重复启动（接收器已把这种情况转为停止，
        // 这里再兜一层，避免竞态下出现第二个 MediaRecorder）
        if (recorderModel.activeType != null) return
        recorderModel.startAudioRecorder(this, forceMicrophone = true)
    }

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }
}

@Composable
private fun QuickRecordPage(recorderModel: RecorderModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeType = recorderModel.activeType

    // 录制从「有」变「无」说明刚停止（无论是页面按钮还是音量键停的），
    // 切到「录制完成」页
    var stoppedOnce by remember { mutableStateOf(false) }
    var prevActive by remember { mutableStateOf(activeType) }
    LaunchedEffect(activeType) {
        if (prevActive != null && activeType == null) stoppedOnce = true
        prevActive = activeType
    }

    // 熄屏倒计时：默认取系统熄屏时间，点模板按钮改为指定分钟数。
    // 页面持了 KEEP_SCREEN_ON，到期后由 LSPosed 模块执行 goToSleep
    var remainingSec by remember {
        mutableStateOf(
            runCatching {
                Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_OFF_TIMEOUT
                ) / 1000
            }.getOrDefault(30).coerceAtLeast(5)
        )
    }
    var sleepRequested by remember { mutableStateOf(false) }
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
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 顶部按钮条：解锁（弹系统原生认证界面，指纹/PIN 与系统锁屏一致）、
        // 返回锁屏（关掉本页，录音若在跑不受影响）
        val activity = context as? Activity
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
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
            TextButton(onClick = { activity?.finish() }) {
                Text(stringResource(R.string.quick_record_back_to_lockscreen))
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
                Button(onClick = { stoppedOnce = false }) {
                    Text(stringResource(R.string.quick_record_done))
                }
            }

            else -> {
                Text(
                    text = stringResource(R.string.quick_record_waiting),
                    style = MaterialTheme.typography.displaySmall
                )
                Spacer(modifier = Modifier.height(40.dp))
                Button(onClick = {
                    recorderModel.startAudioRecorder(context, forceMicrophone = true)
                }) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(stringResource(R.string.quick_record_start))
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))
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
