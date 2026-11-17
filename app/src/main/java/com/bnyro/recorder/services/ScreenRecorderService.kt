package com.bnyro.recorder.services

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.canvas_overlay.OverlayCoordinator
import com.bnyro.recorder.canvas_overlay.RegionMaskOverlay
import com.bnyro.recorder.canvas_overlay.RegionSelectorOverlay
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.VideoResolution
import com.bnyro.recorder.util.CroppedVideoEncoder
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
    private var tempAudioDescriptor: ParcelFileDescriptor? = null
    private var videoStartNanos = 0L
    private var audioStartNanos = 0L
    private var isDestroying = false
    private var startDelayMs = 0L

    // 区域录制：选区界面、区域外遮罩与实时裁剪编码器
    private var regionSelectorOverlay: RegionSelectorOverlay? = null
    private var regionMaskOverlay: RegionMaskOverlay? = null
    private var regionEncoder: CroppedVideoEncoder? = null

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
        // 全版本注册：Android 15 QPR1+ 的状态栏“录制中”胶囊、锁屏自动停止
        // 等系统侧停止都会走 onStop，需要正常收尾保存文件。
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                onDestroy()
            }
        }, null)
    }

    override fun start() {
        // 同一个服务实例只启动一次，避免重复启动后无法停止
        if (!acquireStart()) return

        // 需要等待时先挂起真正的采集，避免把"授权弹窗关闭后回到上个应用"
        // 的过渡动画录进视频的开头
        if (startDelayMs > 0) {
            mainHandler.postDelayed({
                // 这几百毫秒里可能已经从球/通知栏停止过了，此时不能再开始采集，
                // 否则会留下一段没人能停掉的录制
                if (!isDestroying && !isShuttingDown) beginCapture()
            }, startDelayMs)
        } else {
            beginCapture()
        }
    }

    /** 区域录制开启时先让用户框选区域，否则直接开始全屏采集 */
    private fun beginCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            Preferences.prefs.getBoolean(Preferences.regionRecordingKey, false)
        ) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    R.string.region_recording_permission_missing,
                    Toast.LENGTH_LONG
                ).show()
                startCapture()
            } else {
                showRegionSelector()
            }
        } else {
            startCapture()
        }
    }

    private fun showRegionSelector() {
        val metrics = getRealDisplayMetrics()
        val selector = RegionSelectorOverlay(
            this,
            initialRegion = loadSavedRegion(metrics),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            onConfirm = { rect ->
                regionSelectorOverlay = null
                setSelectingRegion(false)
                if (!isDestroying && !isShuttingDown) {
                    // 选区总是记住：确认成功才写入，取消保持上一次
                    // 用确认时的屏幕尺寸换算，否则旋转过之后存进去的比例是错的
                    saveRegion(rect, getRealDisplayMetrics())
                    startRegionCapture(rect)
                }
            },
            onCancel = {
                regionSelectorOverlay = null
                setSelectingRegion(false)
                // 选区阶段从未进入 ACTIVE，状态监听不会回调，注释层要显式收尾
                OverlayCoordinator.notifyCancelled()
                onDestroy()
            }
        )
        regionSelectorOverlay = selector
        if (!selector.show()) {
            regionSelectorOverlay = null
            setSelectingRegion(false)
            Toast.makeText(
                this,
                R.string.region_recording_permission_missing,
                Toast.LENGTH_LONG
            ).show()
            startCapture()
        } else {
            setSelectingRegion(true)
        }
    }

    /** 读取上次确认的选区（按真实屏幕宽高的千分比保存），非法时返回 null */
    private fun loadSavedRegion(metrics: DisplayMetrics): Rect? {
        if (!Preferences.prefs.contains(Preferences.regionLeftKey)) return null
        fun value(key: String) = Preferences.prefs.getInt(key, -1).coerceIn(0, 1000)

        val left = value(Preferences.regionLeftKey)
        val top = value(Preferences.regionTopKey)
        val right = value(Preferences.regionRightKey)
        val bottom = value(Preferences.regionBottomKey)
        if (right <= left || bottom <= top) return null

        val rect = Rect(
            left * metrics.widthPixels / 1000,
            top * metrics.heightPixels / 1000,
            right * metrics.widthPixels / 1000,
            bottom * metrics.heightPixels / 1000
        )
        if (rect.width() < RegionSelectorOverlay.MIN_REGION_PX ||
            rect.height() < RegionSelectorOverlay.MIN_REGION_PX
        ) {
            return null
        }
        return rect
    }

    private fun saveRegion(rect: Rect, metrics: DisplayMetrics) {
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return
        fun permille(value: Int, total: Int) =
            (value.toLong() * 1000 / total).toInt().coerceIn(0, 1000)

        Preferences.edit {
            putInt(Preferences.regionLeftKey, permille(rect.left, metrics.widthPixels))
            putInt(Preferences.regionTopKey, permille(rect.top, metrics.heightPixels))
            putInt(Preferences.regionRightKey, permille(rect.right, metrics.widthPixels))
            putInt(Preferences.regionBottomKey, permille(rect.bottom, metrics.heightPixels))
        }
    }

    private fun startRegionCapture(selection: Rect) {
        val metrics = getRealDisplayMetrics()
        val rect = Rect(selection)
        if (!rect.intersect(0, 0, metrics.widthPixels, metrics.heightPixels) ||
            rect.width() < RegionSelectorOverlay.MIN_REGION_PX ||
            rect.height() < RegionSelectorOverlay.MIN_REGION_PX
        ) {
            Toast.makeText(this, R.string.region_recording_failed, Toast.LENGTH_LONG).show()
            onDestroy()
            return
        }

        val audioSource = AudioSource.fromInt(
            Preferences.prefs.getInt(Preferences.videoAudioSourceKey, AudioSource.MICROPHONE.value)
        )
        val needsSystemAudio = audioSource != AudioSource.MICROPHONE

        // 编码尺寸保持选区的宽高比，同时跟随画质预设整体缩放
        val scale = getQualityScale(metrics.widthPixels, metrics.heightPixels)
        val outputWidth = makeEven((rect.width() * scale).toInt())
        val outputHeight = makeEven((rect.height() * scale).toInt())

        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val frameRate = display.refreshRate.toInt().coerceAtLeast(30)
        val mimeType = if (VideoFormat.getCurrent().codec == MediaRecorder.VideoEncoder.HEVC) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
        val bitratePref = Preferences.prefs.getInt(Preferences.videoBitrateKey, -1)
        val autoBitrate = (BPP * frameRate * outputWidth * outputHeight)
            .toInt()
            .coerceAtLeast(500_000)
        val bitRate = bitratePref.takeIf { it > 0 } ?: autoBitrate

        outputFile = (application as App).fileRepository.getOutputFile("mp4")
        if (outputFile == null) {
            Toast.makeText(
                this,
                R.string.cant_access_selected_folder,
                Toast.LENGTH_LONG
            ).show()
            onDestroy()
            return
        }

        val videoFile = File(cacheDir, "screen_region_video_${System.currentTimeMillis()}.mp4")
        tempVideoFile = videoFile
        val encoder = CroppedVideoEncoder(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            region = rect,
            width = outputWidth,
            height = outputHeight,
            mimeType = mimeType,
            bitRate = bitRate,
            frameRate = frameRate,
            outputFile = videoFile
        )
        regionEncoder = encoder

        // 话筒模式下音频单独录成 m4a，停止后再和裁剪后的视频合流
        if (!needsSystemAudio && !runCatching { startMicrophoneAudioCapture() }.isSuccess) {
            Log.e(TAG, "Unable to start microphone capture")
            failRegionCapture()
            return
        }

        val surface = runCatching { encoder.start() }.onFailure {
            Log.e(TAG, "Unable to start the region encoder", it)
        }.getOrNull()
        if (surface == null) {
            failRegionCapture()
            return
        }

        val projection = mediaProjection
        if (projection == null) {
            failRegionCapture()
            return
        }
        virtualDisplay = runCatching {
            projection.createVirtualDisplay(
                getString(R.string.app_name),
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
        }.onFailure {
            Log.e(TAG, "Unable to create the virtual display", it)
        }.getOrNull()
        if (virtualDisplay == null) {
            failRegionCapture()
            return
        }

        regionMaskOverlay = RegionMaskOverlay(this, rect).takeIf { it.show() }

        if (needsSystemAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startSystemAudioCapture(audioSource == AudioSource.MICROPHONE_AND_INTERNAL)
        }

        videoStartNanos = SystemClock.elapsedRealtimeNanos()
        super.start()
    }

    /** 区域录制没能正常开始：不留空文件，直接走正常收尾 */
    private fun failRegionCapture() {
        Toast.makeText(this, R.string.region_recording_failed, Toast.LENGTH_LONG).show()
        runCatching { outputFile?.delete() }
        outputFile = null
        onDestroy()
    }

    /** 话筒模式下的独立音轨，写进缓存文件，停止后与视频合流 */
    private fun startMicrophoneAudioCapture() {
        val audioFile = File(cacheDir, "screen_region_audio_${System.currentTimeMillis()}.m4a")
        tempAudioFile = audioFile
        val descriptor = ParcelFileDescriptor.open(
            audioFile,
            ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_TRUNCATE
        )
        tempAudioDescriptor = descriptor

        recorder = PlayerHelper.newRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            Preferences.prefs.getInt(Preferences.videoAudioSampleRateKey, -1).takeIf { it > 0 }
                ?.let { setAudioSamplingRate(it) }
            Preferences.prefs.getInt(Preferences.videoAudioBitrateKey, -1).takeIf { it > 0 }
                ?.let { setAudioEncodingBitRate(it) }
            Preferences.prefs.getInt(
                Preferences.videoAudioChannelsKey,
                AudioChannels.MONO.value
            ).let { setAudioChannels(it) }

            setOutputFile(descriptor.fileDescriptor)
            prepare()
            start()
        }
        audioStartNanos = SystemClock.elapsedRealtimeNanos()
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
        runCatching { tempAudioDescriptor?.close() }
        tempAudioDescriptor = null

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
        val display = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
        val metrics = getRealDisplayMetrics()

        val (width, height) = scaleToQualityPreset(metrics.widthPixels, metrics.heightPixels)

        return VideoResolution(
            width,
            height,
            metrics.densityDpi,
            display.refreshRate.toInt()
        )
    }

    private fun getRealDisplayMetrics(): DisplayMetrics {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        // TODO Use the window API instead on newer devices
        return DisplayMetrics().also { display.getRealMetrics(it) }
    }

    /** 画质预设在物理分辨率上的整体缩放比例，区域录制沿用同一比例 */
    private fun getQualityScale(width: Int, height: Int): Float {
        val targetShortEdge = Preferences.prefs.getInt(Preferences.videoResolutionKey, 0)
        if (targetShortEdge <= 0) return 1f
        val shortEdge = minOf(width, height)
        if (shortEdge <= targetShortEdge) return 1f
        return targetShortEdge.toFloat() / shortEdge
    }

    /** H.264/H.265 编码器要求宽高为偶数 */
    private fun makeEven(value: Int): Int = (value / 2 * 2).coerceAtLeast(2)

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

        // 先把画面采集链路断开，再让基类去停 MediaRecorder：VirtualDisplay
        // 还在往 recorder 的 surface 送帧时调用 stop()，编码器很容易卡在
        // 里面，表现就是“长按后一直停不下来，也没法开始新的录制”。
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        // 选区界面和区域外遮罩不属于录制内容，结束时要立刻消失
        setSelectingRegion(false)
        regionSelectorOverlay?.remove()
        regionSelectorOverlay = null
        regionMaskOverlay?.remove()
        regionMaskOverlay = null

        super.onDestroy()
    }

    override fun onStopCapture() {
        // 运行在后台线程：写 m4a 的 moov、停止编码器都可能阻塞几十秒，
        // 放在主线程会让停止广播排队，看起来就是“怎么都停不下来”。
        systemAudioRecorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        systemAudioRecorder = null

        // 区域录制：把最后几帧和 moov 写完，合流时才能读到完整视频
        regionEncoder?.let { encoder ->
            runCatching { encoder.stop() }
            encoder.firstFrameNanos?.let { videoStartNanos = it }
        }
        regionEncoder = null

        // Without this, the MediaProjection session (and the persistent
        // "screen recording/casting" status bar icon tied to it) stays
        // alive even after we're done - releasing the VirtualDisplay only
        // stops feeding it frames, it doesn't hand the projection token
        // back. Previously the icon would only clear once the whole app
        // process was killed.
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    // 停止录制时 UI 的振幅轮询可能比 release() 晚一拍，此时 getMaxAmplitude
    // 会抛 IllegalStateException 直接把整个进程带崩
    override fun getCurrentAmplitude(): Int? = runCatching {
        systemAudioRecorder?.currentAmplitude ?: recorder?.maxAmplitude
    }.getOrNull()

    companion object {
        private const val TAG = "ScreenRecorderService"
        private const val BPP = 0.25f

        /**
         * 是否正停在选区域界面：此时录制状态还是 IDLE，点悬浮球会再次申请
         * MediaProjection，和已持有的会话冲突并把应用卡死，所以要拦掉。
         */
        @Volatile
        var isSelectingRegion = false
            private set

        fun setSelectingRegion(selecting: Boolean) {
            isSelectingRegion = selecting
        }
    }
}
