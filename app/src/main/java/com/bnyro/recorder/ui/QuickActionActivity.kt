package com.bnyro.recorder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.util.RecorderStatusHolder
import com.bnyro.recorder.util.VolumeKeyStopHelper

/**
 * 悬浮球、桌面快捷方式和快捷设置磁贴的“隐形”入口。
 *
 * 这些入口以前都会拉起 [MainActivity]，系统会先绘制一帧启动画面，
 * 造成点击后屏幕闪一下。这里改用全透明、没有启动画面的 Activity：
 * 屏幕捕获授权（appop 已放行时系统会立即返回）后直接开始录制，
 * 再把任务退到后台，用户原来的应用始终留在前台。
 */
class QuickActionActivity : ComponentActivity() {
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var launcher: ActivityResultLauncher<Intent>
    private val recorderModel: RecorderModel by viewModels()
    private var pendingAudioRecording = false
    private var waitingForConsent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            waitingForConsent = false
            if (it.resultCode == Activity.RESULT_OK) {
                if (pendingAudioRecording) {
                    pendingAudioRecording = false
                    recorderModel.startAudioRecorder(this, it)
                } else {
                    // 授权返回后系统还要切回上个应用，稍等一下再开始采集，
                    // 避免把这段过渡动画录进视频的前几帧
                    recorderModel.startVideoRecorder(
                        this,
                        it,
                        MainActivity.SHORTCUT_START_DELAY_MS
                    )
                }
            }
            moveTaskToBack(true)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (waitingForConsent) return
        val type = intent.getStringExtra(MainActivity.EXTRA_ACTION_KEY)?.let {
            runCatching { RecorderType.valueOf(it) }.getOrNull()
        } ?: RecorderType.NONE

        // 已有录制在进行时不再启动第二个：旧逻辑会先杀掉正在收尾的服务，
        // 结果就是文件损坏 / 崩溃 / 停不下来。
        if (type != RecorderType.NONE && RecorderStatusHolder.activeType != null) {
            Toast.makeText(this, R.string.floating_ball_other_recording, Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
            return
        }

        // 这个入口不会显示首页，用户看不到那里的警告条；
        // 无障碍已经被系统关掉时至少提示一次，别让人以为长按还能停。
        if (type != RecorderType.NONE && VolumeKeyStopHelper.needsAttention(this)) {
            notifyVolumeKeyBroken()
        }

        when (type) {
            RecorderType.AUDIO -> {
                if (recorderModel.audioRequiresScreenCapture()) {
                    if (recorderModel.hasScreenRecordingPermissions(this)) {
                        waitingForConsent = true
                        pendingAudioRecording = true
                        launcher.launch(projectionManager.createScreenCaptureIntent())
                    } else {
                        openMainActivity(intent)
                    }
                } else if (recorderModel.startAudioRecorder(this)) {
                    // 话筒模式不需要系统授权，直接开始后立即退到后台
                    moveTaskToBack(true)
                } else {
                    openMainActivity(intent)
                }
            }

            RecorderType.VIDEO -> {
                if (recorderModel.hasScreenRecordingPermissions(this)) {
                    waitingForConsent = true
                    launcher.launch(projectionManager.createScreenCaptureIntent())
                } else {
                    openMainActivity(intent)
                }
            }

            RecorderType.NONE -> moveTaskToBack(true)
        }
    }

    /**
     * 尝试把被系统摘掉的无障碍服务写回，仍然失败就 Toast 提醒。
     * 这里不拦截录制，只是让用户知道长按音量键当前不可用。
     */
    private fun notifyVolumeKeyBroken() {
        Thread {
            val healed = runCatching {
                VolumeKeyStopHelper.selfHeal(this) ||
                    VolumeKeyStopHelper.selfHealWithRoot(this)
            }.getOrDefault(false)
            if (healed && !VolumeKeyStopHelper.needsAttention(this)) return@Thread
            runOnUiThread {
                Toast.makeText(
                    applicationContext,
                    R.string.volume_key_stop_broken_toast,
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    /**
     * 权限缺失或无法自动开始时，交给 [MainActivity] 走完整界面流程，
     * 让用户能看到提示，而不是点了没有任何反应。
     */
    private fun openMainActivity(intent: Intent) {
        startActivity(
            Intent(intent)
                .setClass(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
