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
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.result.ActivityResult
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
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.services.AudioRecorderService
import com.bnyro.recorder.services.LosslessRecorderService
import com.bnyro.recorder.services.RecorderService
import com.bnyro.recorder.services.ScreenRecorderService
import com.bnyro.recorder.util.PermissionHelper
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderControl
import com.bnyro.recorder.util.RecorderStatusHolder

/**
 * 录制状态的 UI 侧门面。
 *
 * 状态的唯一来源是 [RecorderStatusHolder]：无论录制是从应用内、悬浮球、
 * 磁贴还是快捷方式启动的，这里都能立刻反映出来，避免出现“球已经在录、
 * 应用界面仍显示空闲”的状态错乱。
 */
class RecorderModel : ViewModel() {
    private val supportsOverlay = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    var recorderState by mutableStateOf(RecorderState.IDLE)
        private set

    /** 当前正在录制的类型；null 表示没有任何录制。 */
    var activeType by mutableStateOf<RecorderType?>(null)
        private set

    var recordedTime by mutableStateOf<Long?>(null)
    val recordedAmplitudes = mutableStateListOf<Int>()
    private var activityResult: ActivityResult? = null
    private var canvasOverlay: CanvasOverlay? = null

    private val handler = Handler(Looper.getMainLooper())
    private var timeLoopRunning = false
    private var amplitudeLoopRunning = false

    @SuppressLint("StaticFieldLeak")
    private var recorderService: RecorderService? = null
    private var boundContext: Context? = null

    /** 本次绑定是否由本模型发起（只有发起方需要调用 start） */
    private var pendingStart = false

    private val timeRunnable = object : Runnable {
        override fun run() {
            val active = RecorderStatusHolder.activeType
            if (active == null) {
                timeLoopRunning = false
                recordedTime = null
                return
            }
            // 用开始时刻计算，这样从悬浮球开始录制、之后才打开应用时，
            // 计时不会从 0 重新数。
            val elapsed = SystemClock.elapsedRealtime() - RecorderStatusHolder.activeSince(active)
            recordedTime = (elapsed / 100).coerceAtLeast(0)
            handler.postDelayed(this, 100)
        }
    }

    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            if (RecorderStatusHolder.activeType == null) {
                amplitudeLoopRunning = false
                return
            }
            recorderService?.getCurrentAmplitude()?.let {
                if (recordedAmplitudes.size >= 90) recordedAmplitudes.removeAt(0)
                recordedAmplitudes.add(it)
            }
            handler.postDelayed(this, 100)
        }
    }

    private val statusListener: (RecorderType, RecorderState) -> Unit = { _, _ -> syncState() }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            recorderService = (service as RecorderService.LocalBinder).getService()
            val shouldStart = pendingStart
            pendingStart = false
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
            // 只有发起启动的那个模型才调用 start()：否则重复绑定同一个
            // 服务会创建第二个 MediaRecorder
            if (shouldStart) recorderService?.start()
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            recorderService = null
        }
    }

    init {
        RecorderStatusHolder.addListener(statusListener)
        syncState()
    }

    override fun onCleared() {
        RecorderStatusHolder.removeListener(statusListener)
        handler.removeCallbacksAndMessages(null)
        detachService()
        super.onCleared()
    }

    private fun syncState() {
        val active = RecorderStatusHolder.activeType
        activeType = active
        recorderState = if (active == null) RecorderState.IDLE else RecorderState.ACTIVE

        if (active != null) {
            startTimeLoop()
            startAmplitudeLoop()
        } else {
            recordedTime = null
            recordedAmplitudes.clear()
            detachService()
        }
    }

    /**
     * @param startDelayMs 延迟多少毫秒后才真正开始采集。从悬浮球/桌面快捷方式
     * 启动时，授权弹窗关闭后会先有回到上个应用的过渡动画，稍等一下再开始录，
     * 可以避免把这段动画录进视频的前几帧。
     */
    fun startVideoRecorder(context: Context, result: ActivityResult, startDelayMs: Long = 0L) {
        if (!canStart(context)) return
        activityResult = result
        val serviceIntent = Intent(context, ScreenRecorderService::class.java).apply {
            putExtra(RecorderService.START_DELAY_EXTRA_KEY, startDelayMs)
        }
        if (!startRecorderService(context, serviceIntent)) return
        val showOverlayAnnotation =
            Preferences.prefs.getBoolean(Preferences.showOverlayAnnotationToolKey, false)
        if (supportsOverlay && showOverlayAnnotation) {
            canvasOverlay = CanvasOverlay(context)
        }
    }

    @SuppressLint("NewApi")
    fun startAudioRecorder(
        context: Context,
        result: ActivityResult? = null,
        forceMicrophone: Boolean = false
    ): Boolean {
        if (!canStart(context)) return false
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

        // 锁屏快捷录音强制话筒模式：内录需要 MediaProjection 授权弹窗，锁屏下无法确认
        val audioSource = if (forceMicrophone) {
            AudioSource.MICROPHONE
        } else {
            AudioSource.fromInt(
                Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
            )
        }
        // 无损 WAV 仅话筒模式可用
        val lossless = Preferences.prefs.getBoolean(Preferences.losslessRecorderKey, false) &&
            audioSource == AudioSource.MICROPHONE

        val serviceIntent =
            if (lossless) {
                Intent(context, LosslessRecorderService::class.java)
            } else {
                if (forceMicrophone) AudioRecorderService.forceMicRequested = true
                Intent(context, AudioRecorderService::class.java)
            }

        val started = startRecorderService(context, serviceIntent)
        if (!started) AudioRecorderService.forceMicRequested = false
        return started
    }

    /** 内部/混合音频需要 MediaProjection 授权 */
    fun audioRequiresScreenCapture() = AudioSource.fromInt(
        Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
    ) != AudioSource.MICROPHONE

    /**
     * 已经有任意录制在运行时不再启动新服务：旧实现在开始前会强行杀掉
     * 三个录制服务，如果此时旧录制还在收尾（写 moov、合流），新旧实例
     * 会同时抢占 MediaProjection / 内部音频，直接导致崩溃或 0KB 文件。
     */
    private fun canStart(context: Context): Boolean {
        if (RecorderStatusHolder.activeType == null && !RecorderStatusHolder.isFinalizing) return true
        Toast.makeText(context, R.string.floating_ball_other_recording, Toast.LENGTH_SHORT).show()
        return false
    }

    private fun startRecorderService(context: Context, intent: Intent): Boolean {
        if (RecorderStatusHolder.activeType != null || RecorderStatusHolder.isFinalizing) return false

        detachService()
        // 绑定和解绑必须使用同一个 Context：Android 以"外层 Context"为键
        // 登记 ServiceConnection，用 Activity 绑定却用 applicationContext
        // 解绑会抛 "Service not registered" 并被静默忽略，绑定泄漏后服务
        // 实例永远不会销毁，下次启动复用这个已停止的实例就再也停不下来了。
        val appContext = context.applicationContext
        // 清掉可能还没销毁完的旧实例；此时没有任何录制在进行，不会打断收尾
        runCatching { appContext.stopService(intent) }
        ContextCompat.startForegroundService(appContext, intent)
        boundContext = appContext
        pendingStart = true
        if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            pendingStart = false
        }
        return true
    }

    /**
     * 停止当前正在进行的录制（无论是哪个入口启动的）。
     * 通过广播让服务自己收尾，而不是直接调用服务的 onDestroy。
     */
    fun stopRecording(context: Context) {
        RecorderControl.requestStopActive(context)
    }

    /** 录制结束、状态回到空闲后清理界面残留。 */
    fun clearRecordingUi() {
        if (RecorderStatusHolder.activeType != null) return
        if (supportsOverlay) canvasOverlay?.remove()
        canvasOverlay = null
        recordedTime = null
        recordedAmplitudes.clear()
    }

    private fun startTimeLoop() {
        if (timeLoopRunning) return
        timeLoopRunning = true
        handler.post(timeRunnable)
    }

    private fun startAmplitudeLoop() {
        if (amplitudeLoopRunning) return
        amplitudeLoopRunning = true
        handler.post(amplitudeRunnable)
    }

    private fun detachService() {
        recorderService = null
        pendingStart = false
        boundContext?.let { ctx -> runCatching { ctx.unbindService(connection) } }
        boundContext = null
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
