package com.bnyro.recorder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.services.AudioBallService
import com.bnyro.recorder.services.VideoBallService
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.ui.theme.RecordYouTheme
import com.bnyro.recorder.util.FloatingBallHelper
import com.bnyro.recorder.util.RecorderStatusHolder

class MainActivity : ComponentActivity() {
    private var initialRecorder = RecorderType.NONE
    private var exitAfterRecordingStart = false
    private var pendingAudioRecording = false
    private var launchedFromShortcut = false
    private lateinit var mProjectionManager: MediaProjectionManager
    private val recorderModel: RecorderModel by viewModels()
    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeModel: ThemeModel by viewModels()
        launcher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    if (pendingAudioRecording) {
                        pendingAudioRecording = false
                        recorderModel.startAudioRecorder(this, result)
                    } else {
                        // 从悬浮球/快捷方式启动时，授权弹窗关闭后本应用会马上退回后台，
                        // 中间有一段任务切换动画；稍等下再开始采集，避免录进前几帧
                        recorderModel.startVideoRecorder(
                            this,
                            result,
                            if (launchedFromShortcut) SHORTCUT_START_DELAY_MS else 0L
                        )
                    }
                }
            }
        mProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        processIntent(intent)
        ensureFloatingBallsRunning()
        enableEdgeToEdge()

        setContent {
            RecordYouTheme(
                when (themeModel.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK, ThemeMode.AMOLED -> true
                    else -> false
                },
                amoledDark = themeModel.themeMode == ThemeMode.AMOLED
            ) {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        navController = navController,
                        modifier = Modifier,
                        initialRecorder = initialRecorder
                    )
                }
            }
        }
    }

    /**
     * If the user has a floating ball enabled and the overlay permission is
     * still granted, (re-)start its service. This is a no-op if it's already
     * running - it just makes sure the ball comes back after the
     * process/service got killed by the system while RecordYou was in the
     * background.
     */
    private fun ensureFloatingBallsRunning() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Settings.canDrawOverlays(this)) return
        if (FloatingBallHelper.isEnabled(this, RecorderType.AUDIO)) {
            startService(Intent(this, AudioBallService::class.java))
        }
        if (FloatingBallHelper.isEnabled(this, RecorderType.VIDEO)) {
            startService(Intent(this, VideoBallService::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        processIntent(intent)
        super.onNewIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        if (intent.action == ACTION_ENABLE_FLOATING_BALL) {
            val type = intent.getStringExtra(EXTRA_BALL_TYPE_KEY)?.let { name ->
                runCatching { RecorderType.valueOf(name) }.getOrNull()
            } ?: RecorderType.NONE
            handleEnableFloatingBallShortcut(type)
            return
        }

        val initialRecorderType = intent.getStringExtra(EXTRA_ACTION_KEY)?.let {
            RecorderType.valueOf(it)
        } ?: RecorderType.NONE

        // 已有录制在跑时不再启动第二个，否则会和正在收尾的旧服务抢占资源，
        // 造成 0KB 文件 / 崩溃 / 无法停止
        if (initialRecorderType != RecorderType.NONE && RecorderStatusHolder.activeType != null) {
            Toast.makeText(this, R.string.floating_ball_other_recording, Toast.LENGTH_SHORT).show()
            intent.removeExtra(EXTRA_ACTION_KEY)
            return
        }

        initialRecorder = initialRecorderType
        launchedFromShortcut = initialRecorderType != RecorderType.NONE
        if (initialRecorderType == RecorderType.AUDIO) {
            if (recorderModel.audioRequiresScreenCapture()) {
                // 内部/混合音频需要先获取 MediaProjection 授权
                if (recorderModel.hasScreenRecordingPermissions(this)) {
                    pendingAudioRecording = true
                    launcher.launch(mProjectionManager.createScreenCaptureIntent())
                }
            } else if (recorderModel.startAudioRecorder(this)) {
                // audio recording starts synchronously (no system consent dialog needed
                // once the mic permission is granted), so we can move the task back
                // right away instead of waiting for a pause/resume cycle.
                exitAfterRecordingStart = true
            }
        } else if (initialRecorderType == RecorderType.VIDEO) {
            if (recorderModel.hasScreenRecordingPermissions(this)) {
                launcher.launch(mProjectionManager.createScreenCaptureIntent())
            }
        }
        intent.removeExtra(EXTRA_ACTION_KEY)
    }

    /**
     * Handles the "开启录音球/录屏球" launcher shortcut (long-press the app
     * icon). If the overlay permission is already granted, turns the ball on
     * and quietly returns to whatever app the user was in - same "invisible
     * start" pattern used for the audio/video quick actions. Otherwise sends
     * the user to the system permission screen; they'll need to trigger the
     * shortcut again afterwards.
     */
    private fun handleEnableFloatingBallShortcut(type: RecorderType) {
        if (type == RecorderType.NONE) return
        if (FloatingBallHelper.setEnabled(this, type, true)) {
            exitAfterRecordingStart = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (initialRecorder == RecorderType.VIDEO) {
            exitAfterRecordingStart = true
            initialRecorder = RecorderType.NONE
        } else if (initialRecorder == RecorderType.AUDIO && pendingAudioRecording) {
            // 正在等待内部音频的屏幕捕获授权，授权后直接退回后台
            exitAfterRecordingStart = true
            initialRecorder = RecorderType.NONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (exitAfterRecordingStart) {
            exitAfterRecordingStart = false
            moveTaskToBack(true)
        }
    }

    companion object {
        const val EXTRA_ACTION_KEY = "action"
        const val EXTRA_BALL_TYPE_KEY = "ballType"
        const val ACTION_ENABLE_FLOATING_BALL = "com.bnyro.recorder.action.ENABLE_FLOATING_BALL"

        // 从悬浮球/快捷方式启动录屏时，晚一点再真正开始采集，
        // 避开"授权弹窗关闭 -> 退回上个应用"的过渡动画
        internal const val SHORTCUT_START_DELAY_MS = 700L
    }
}
