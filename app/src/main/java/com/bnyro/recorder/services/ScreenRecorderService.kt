package com.bnyro.recorder.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.VideoResolution
import com.bnyro.recorder.util.MediaMerger
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.SystemAudioRecorder
import java.io.File

class ScreenRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_screen)

    override val recorderType = RecorderType.VIDEO

    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var activityResult: ActivityResult? = null
    private var systemAudioRecorder: SystemAudioRecorder? = null

    private var tempVideoFile: File? = null
    private var tempVideoDescriptor: ParcelFileDescriptor? = null
    private var tempAudioFile: File? = null
    private var videoStartNanos = 0L
    private var audioStartNanos = 0L
    private var isDestroying = false
    private var startDelayMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startDelayMs = intent?.getLongExtra(START_DELAY_EXTRA_KEY, 0L) ?: 0L
        return super.onStartCommand(intent, flags, startId)
    }

    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val audioSource = AudioSource.fromInt(
                Preferences.prefs.getInt(
                    Preferences.videoAudioSourceKey,
                    AudioSource.MICROPHONE.value
                )
            )
            val usesMicrophone = audioSource == AudioSource.MICROPHONE ||
                audioSource == AudioSource.MICROPHONE_AND_INTERNAL
            if (usesMicrophone) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        } else {
            null
        }

    fun prepare(data: ActivityResult) {
        this.activityResult = data
        initMediaProjection()
    }

    private fun initMediaProjection() {
        val mProjectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        try {
            mediaProjection = mProjectionManager.getMediaProjection(
                Activity.RESULT_OK,
                activityResult?.data!!
            )
        } catch (e: Exception) {
            Log.e("Media Projection Error", e.toString())
            onDestroy()
            return
        }
        val projection = mediaProjection ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    onDestroy()
                }
            }, null)
        }
    }

    override fun start() {
        // 需要等待时先挂起真正的采集，避免把"授权弹窗关闭后回到上个应用"
        // 的过渡动画录进视频的开头
        if (startDelayMs > 0) {
            mainHandler.postDelayed({
                if (!isDestroying) startCapture()
            }, startDelayMs)
        } else {
            startCapture()
        }
    }

    private fun startCapture() {
        val audioSource = AudioSource.fromInt(
            Preferences.prefs.getInt(Preferences.videoAudioSourceKey, AudioSource.MICROPHONE.value)
        )
        val needsSystemAudio = audioSource != AudioSource.MICROPHONE
        val includeMicrophone = audioSource == AudioSource.MICROPHONE_AND_INTERNAL

        val resolution = getScreenResolution()
        val videoFormat = VideoFormat.getCurrent()

        recorder = PlayerHelper.newRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            if (!needsSystemAudio) {
                // 话筒模式：音视频都交给 MediaRecorder，保持原有行为
                setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            }

            setOutputFormat(videoFormat.format)
            setVideoFrameRate(resolution.frameRate)
            setVideoEncoder(videoFormat.codec)

            val bitratePref = Preferences.prefs.getInt(Preferences.videoBitrateKey, -1)
            val autoBitrate = (BPP * resolution.frameRate * resolution.width * resolution.height).toInt()
            setVideoEncodingBitRate(bitratePref.takeIf { it > 0 } ?: autoBitrate)

            if (!needsSystemAudio) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                // Sample rate / bitrate / channel count must be set after
                // setOutputFormat() + setAudioEncoder(), not before.
                Preferences.prefs.getInt(Preferences.videoAudioSampleRateKey, -1).takeIf {
                    it > 0
                }?.let {
                    setAudioSamplingRate(it)
                }

                Preferences.prefs.getInt(Preferences.videoAudioBitrateKey, -1).takeIf { it > 0 }
                    ?.let {
                        setAudioEncodingBitRate(it)
                    }
                Preferences.prefs.getInt(
                    Preferences.videoAudioChannelsKey,
                    AudioChannels.MONO.value
                ).let {
                    setAudioChannels(it)
                }
            }

            setVideoSize(resolution.width, resolution.height)

            virtualDisplay = mediaProjection!!.createVirtualDisplay(
                getString(R.string.app_name),
                resolution.width,
                resolution.height,
                resolution.density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null,
                null,
                null
            )

            outputFile = (application as App).fileRepository.getOutputFile("mp4")
            if (outputFile == null) {
                Toast.makeText(this@ScreenRecorderService, R.string.cant_access_selected_folder, Toast.LENGTH_LONG).show()
                onDestroy()
                return
            }

            if (needsSystemAudio) {
                // 视频先写到缓存文件，停止后再把音频轨合流进最终文件
                val videoFile = File(cacheDir, "screen_video_${System.currentTimeMillis()}.mp4")
                tempVideoFile = videoFile
                tempVideoDescriptor = ParcelFileDescriptor.open(
                    videoFile,
                    ParcelFileDescriptor.MODE_READ_WRITE or
                        ParcelFileDescriptor.MODE_CREATE or
                        ParcelFileDescriptor.MODE_TRUNCATE
                )
                setOutputFile(tempVideoDescriptor!!.fileDescriptor)
            } else {
                fileDescriptor = contentResolver.openFileDescriptor(outputFile!!.uri, "w")
                setOutputFile(fileDescriptor?.fileDescriptor)
            }

            runCatching {
                prepare()
            }

            start()
            videoStartNanos = SystemClock.elapsedRealtimeNanos()

            virtualDisplay?.surface = surface
        }

        if (needsSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSystemAudioCapture(includeMicrophone)
        }

        super.start()
    }

    /**
     * 内部/混合音频模式：视频轨仍由 MediaRecorder 负责（不设置音频源），
     * 音频轨通过播放捕获单独编码成 m4a，停止后合流进 mp4。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSystemAudioCapture(includeMicrophone: Boolean) {
        val audioFile = File(cacheDir, "screen_audio_${System.currentTimeMillis()}.m4a")
        tempAudioFile = audioFile
        val audioDescriptor = ParcelFileDescriptor.open(
            audioFile,
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
        runCatching {
            SystemAudioRecorder(
                mediaProjection = mediaProjection!!,
                fileDescriptor = audioDescriptor,
                includeMicrophone = includeMicrophone,
                bitRate = Preferences.prefs.getInt(Preferences.videoAudioBitrateKey, 192_000)
                    .takeIf { it > 0 } ?: 192_000,
                micPercent = Preferences.prefs.getInt(
                    Preferences.videoAudioMixRatioKey,
                    SystemAudioRecorder.DEFAULT_MIC_PERCENT
                )
            ).also {
                it.start()
                audioStartNanos = it.startNanos
                systemAudioRecorder = it
            }
        }.onFailure {
            runCatching { audioDescriptor.close() }
            Log.e(TAG, "Unable to capture internal audio", it)
            Toast.makeText(this, R.string.internal_audio_failed, Toast.LENGTH_LONG).show()
        }
    }

    override suspend fun onRecordingStopped() {
        val videoFile = tempVideoFile ?: return
        runCatching { tempVideoDescriptor?.close() }
        tempVideoDescriptor = null

        val finalFile = outputFile
        val audioFile = tempAudioFile
        try {
            if (finalFile != null) {
                val merged = mergeInto(
                    videoFile,
                    audioFile,
                    finalFile.uri
                )
                Log.i(
                    "ScreenRecorderService",
                    "合并结果=$merged 视频字节=${videoFile.length()} 音频字节=${audioFile?.length()}"
                )
                if (!merged) {
                    // 合流失败时至少保留视频，避免用户什么都拿不到
                    runCatching {
                        contentResolver.openOutputStream(finalFile.uri)?.use { output ->
                            videoFile.inputStream().use { it.copyTo(output) }
                        }
                    }
                }
            }
        } finally {
            runCatching { videoFile.delete() }
            runCatching { audioFile?.delete() }
            tempVideoFile = null
            tempAudioFile = null
        }
    }

    private fun mergeInto(
        videoFile: File,
        audioFile: File?,
        outputUri: android.net.Uri
    ): Boolean {
        if (audioFile == null || audioFile.length() <= 0) return false
        val output = contentResolver.openFileDescriptor(outputUri, "wt") ?: return false
        return try {
            val offsetUs = if (audioStartNanos > 0 && videoStartNanos > 0) {
                ((audioStartNanos - videoStartNanos) / 1000).coerceIn(0L, 1_000_000L)
            } else {
                0L
            }
            MediaMerger.merge(videoFile, audioFile, output, offsetUs)
        } finally {
            runCatching { output.close() }
        }
    }

    private fun getScreenResolution(): VideoResolution {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)

        // TODO Use the window API instead on newer devices
        val metrics = DisplayMetrics()
        display.getRealMetrics(metrics)

        val (width, height) = scaleToQualityPreset(metrics.widthPixels, metrics.heightPixels)

        return VideoResolution(
            width,
            height,
            metrics.densityDpi,
            display.refreshRate.toInt()
        )
    }

    /**
     * Scales the screen's native resolution down to the selected quality preset
     * (target short edge in px, see [Preferences.videoResolutionKey]), keeping the
     * original aspect ratio. Both dimensions are rounded down to the nearest even
     * number since H.264/H.265 encoders require even width/height. Returns the
     * resolution unchanged when "original" quality is selected (value <= 0), or
     * when the screen is already smaller than the target.
     */
    private fun scaleToQualityPreset(width: Int, height: Int): Pair<Int, Int> {
        val targetShortEdge = Preferences.prefs.getInt(Preferences.videoResolutionKey, 0)
        if (targetShortEdge <= 0) return width to height

        val shortEdge = minOf(width, height)
        if (shortEdge <= targetShortEdge) return width to height

        val scale = targetShortEdge.toFloat() / shortEdge
        var scaledWidth = (width * scale).toInt()
        var scaledHeight = (height * scale).toInt()
        if (scaledWidth % 2 != 0) scaledWidth -= 1
        if (scaledHeight % 2 != 0) scaledHeight -= 1

        return scaledWidth to scaledHeight
    }

    override fun onDestroy() {
        if (isDestroying) return
        isDestroying = true

        systemAudioRecorder?.let {
            it.stop()
            it.release()
        }
        systemAudioRecorder = null
        // 临时视频文件的 fd 必须等 MediaRecorder 停止后再关闭（在
        // onRecordingStopped 里处理），否则 moov 无法正常写入。

        super.onDestroy()
        virtualDisplay?.release()
        // Without this, the MediaProjection session (and the persistent
        // "screen recording/casting" status bar icon tied to it) stays
        // alive even after we're done - releasing the VirtualDisplay only
        // stops feeding it frames, it doesn't hand the projection token
        // back. Previously the icon would only clear once the whole app
        // process was killed.
        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun getCurrentAmplitude(): Int? =
        systemAudioRecorder?.currentAmplitude ?: recorder?.maxAmplitude

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val BPP = 0.25f
    }
}
