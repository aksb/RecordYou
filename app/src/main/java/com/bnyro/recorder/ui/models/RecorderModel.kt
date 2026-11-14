package com.bnyro.recorder.ui.models

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.canvas_overlay.CanvasOverlay
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.services.AudioRecorderService
import com.bnyro.recorder.services.LosslessRecorderService
import com.bnyro.recorder.services.RecorderService
import com.bnyro.recorder.services.ScreenRecorderService
import com.bnyro.recorder.util.PermissionHelper
import com.bnyro.recorder.util.Preferences

class RecorderModel : ViewModel() {
    private val supportsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    var recorderState by mutableStateOf(RecorderState.IDLE)
    var recordedTime by mutableStateOf<Long?>(null)
    val recordedAmplitudes = mutableStateListOf<Int>()
    private var activityResult: ActivityResult? = null
    private var canvasOverlay: CanvasOverlay? = null

    private val handler = Handler(Looper.getMainLooper())

    @SuppressLint("StaticFieldLeak")
    private var recorderService: RecorderService? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            recorderService = (service as RecorderService.LocalBinder).getService()
            recorderService?.onRecorderStateChanged = {
                recorderState = it
            }
            activityResult?.let { result ->
                (recorderService as? ScreenRecorderService)?.prepare(result)
                (recorderService as? AudioRecorderService)?.prepare(result)
            }
            // Only screen recording ever creates a canvasOverlay (see
            // startVideoRecorder below) - guarding on the service type here
            // as well (not just relying on canvasOverlay being null for audio
            // recording) makes sure a leftover overlay from an earlier video
            // session can never resurface during an audio-only session.
            if (supportsOverlay && recorderService is ScreenRecorderService) {
                canvasOverlay?.show()
            }
            recorderService?.start()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            recorderService = null
        }
    }

    /**
     * @param startDelayMs 延迟多少毫秒后才真正开始采集。从悬浮球/桌面快捷方式
     * 启动时，授权弹窗关闭后会先有回到上个应用的过渡动画，稍等一下再开始录，
     * 可以避免把这段动画录进视频的前几帧。
     */
    fun startVideoRecorder(context: Context, result: ActivityResult, startDelayMs: Long = 0L) {
        activityResult = result
        val serviceIntent = Intent(context, ScreenRecorderService::class.java).apply {
            putExtra(RecorderService.START_DELAY_EXTRA_KEY, startDelayMs)
        }
        startRecorderService(context, serviceIntent)
        val showOverlayAnnotation =
            Preferences.prefs.getBoolean(Preferences.showOverlayAnnotationToolKey, false)
        if (supportsOverlay && showOverlayAnnotation) {
            canvasOverlay = CanvasOverlay(context)
        }
    }

    @SuppressLint("NewApi")
    fun startAudioRecorder(context: Context, result: ActivityResult? = null): Boolean {
        activityResult = result

        val audioPermission = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            audioPermission.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (!PermissionHelper.checkPermissions(context, audioPermission.toTypedArray())) {
            Toast.makeText(
                context,
                context.getString(R.string.no_sufficient_permissions), Toast.LENGTH_SHORT
            )
                .show()
            return false
        }

        val audioSource = AudioSource.fromInt(
            Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
        )
        // 无损 WAV 仅话筒模式可用
        val lossless = Preferences.prefs.getBoolean(Preferences.losslessRecorderKey, false) &&
            audioSource == AudioSource.MICROPHONE

        val serviceIntent =
            if (lossless) {
                Intent(context, LosslessRecorderService::class.java)
            } else {
                Intent(context, AudioRecorderService::class.java)
            }

        startRecorderService(context, serviceIntent)
        return true
    }

    /** 内部/混合音频需要 MediaProjection 授权 */
    fun audioRequiresScreenCapture() = AudioSource.fromInt(
        Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
    ) != AudioSource.MICROPHONE

    private fun startRecorderService(context: Context, intent: Intent) {
        runCatching {
            context.unbindService(connection)
        }

        listOfNotNull(
            AudioRecorderService::class.java,
            ScreenRecorderService::class.java,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) LosslessRecorderService::class.java else null
        ).forEach {
            runCatching {
                context.stopService(Intent(context, it))
            }
        }
        ContextCompat.startForegroundService(context, intent)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)

        startElapsedTimeCounter()
        handler.postDelayed(this::updateAmplitude, 100)
    }

    fun stopRecording() {
        recorderService?.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) canvasOverlay?.remove()
        // Without this, the reference stays around after the view is
        // removed - if a subsequent recording session then reconnects to
        // `connection` (see onServiceConnected above), it would try to
        // re-show this same, already-removed CanvasOverlay, resulting in a
        // visible-but-broken overlay (can't be dragged, buttons don't
        // respond) turning up in scenarios that never created one, most
        // noticeably a plain audio recording right after a video one.
        canvasOverlay = null
        recordedTime = null
        recordedAmplitudes.clear()
    }

    private fun updateTime() {
        if (recorderState != RecorderState.ACTIVE) return

        recordedTime = recordedTime?.plus(1)
        handler.postDelayed(this::updateTime, 100)
    }

    private fun updateAmplitude() {
        if (recorderState != RecorderState.ACTIVE) return

        recorderService?.getCurrentAmplitude()?.let {
            if (recordedAmplitudes.size >= 90) recordedAmplitudes.removeAt(0)
            recordedAmplitudes.add(it)
        }

        handler.postDelayed(this::updateAmplitude, 100)
    }

    private fun startElapsedTimeCounter() {
        recordedTime = 0L
        handler.postDelayed(this::updateTime, 100)
    }

    @SuppressLint("NewApi")
    fun hasScreenRecordingPermissions(context: Context): Boolean {
        // 三种音频来源都需要录音权限（播放捕获同样要求 RECORD_AUDIO）
        val requiredPermissions = arrayListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val granted = PermissionHelper.checkPermissions(context, requiredPermissions.toTypedArray())
        if (!granted) {
            Toast.makeText(
                context,
                context.getString(R.string.no_sufficient_permissions), Toast.LENGTH_SHORT
            )
                .show()
        }
        return granted
    }
}
