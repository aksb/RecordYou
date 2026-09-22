package com.bnyro.recorder.services

import android.app.Activity
import android.content.Context
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.widget.Toast
import androidx.activity.result.ActivityResult
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.VideoResolution
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences

class ScreenRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_screen)

    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var activityResult: ActivityResult? = null
    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val usesMicrophone = Preferences.prefs.getInt(
                Preferences.audioSourceKey,
                AudioSource.NONE.value
            ) == AudioSource.MICROPHONE.value
            if (usesMicrophone) {
                // Screen recording with "microphone audio" enabled captures
                // the mic in addition to the screen, but this service was
                // only ever declared/started as a "mediaProjection" type
                // foreground service - never "microphone" too. On at least
                // some devices/ROMs, Android's audio policy silences a
                // background app's mic capture unless the foreground
                // service that's using the mic is actually declared with
                // the "microphone" type (see AndroidManifest.xml, which now
                // declares both types on this service to match). This is
                // very likely the root cause of recordings that randomly go
                // silent after a few seconds once the app is backgrounded.
                // Applied from Q onwards (both type constants exist since
                // API 29) - deliberately NOT gated behind Android 14 the
                // way AudioRecorderService's own microphone type is, since
                // this needs to actually take effect on Android 13 devices
                // to be testable/useful there.
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
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    onDestroy()
                }
            }, null)
        }
    }

    override fun start() {
        val audioSource = AudioSource.fromInt(
            Preferences.prefs.getInt(Preferences.audioSourceKey, 0)
        )
        val resolution = getScreenResolution()
        val videoFormat = VideoFormat.getCurrent()

        recorder = PlayerHelper.newRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)

            if (audioSource == AudioSource.MICROPHONE) {
                Preferences.prefs.getInt(
                    Preferences.audioDeviceSourceKey,
                    AudioDeviceSource.DEFAULT.value
                ).let {
                    setAudioSource(it)
                }
            }

            setOutputFormat(videoFormat.format)
            setVideoFrameRate(resolution.frameRate)
            setVideoEncoder(videoFormat.codec)

            val bitratePref = Preferences.prefs.getInt(Preferences.videoBitrateKey, -1)
            val autoBitrate = (BPP * resolution.frameRate * resolution.width * resolution.height).toInt()
            setVideoEncodingBitRate(bitratePref.takeIf { it > 0 } ?: autoBitrate)

            if (audioSource == AudioSource.MICROPHONE) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

                // Sample rate / bitrate / channel count must be set after
                // setOutputFormat() + setAudioEncoder(), not before -
                // MediaRecorder's documented state machine only accepts
                // these calls at this point. They used to be set earlier
                // (right after setAudioSource()), which is out of order per
                // the API contract even though it doesn't throw outright on
                // every device.
                Preferences.prefs.getInt(Preferences.audioSampleRateKey, -1).takeIf {
                    it > 0
                }?.let {
                    setAudioSamplingRate(it)
                    setAudioEncodingBitRate(it * 32 * 2)
                }

                Preferences.prefs.getInt(Preferences.audioBitrateKey, -1).takeIf { it > 0 }?.let {
                    setAudioEncodingBitRate(it)
                }
                Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value).let {
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

            outputFile = (application as App).fileRepository.getOutputFile(videoFormat.extension)
            if (outputFile == null) {
                Toast.makeText(this@ScreenRecorderService, R.string.cant_access_selected_folder, Toast.LENGTH_LONG).show()
                onDestroy()
                return
            }

            fileDescriptor = contentResolver.openFileDescriptor(outputFile!!.uri, "w")
            setOutputFile(fileDescriptor?.fileDescriptor)

            runCatching {
                prepare()
            }

            start()

            virtualDisplay?.surface = surface
        }

        super.start()
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

    override fun getCurrentAmplitude() = recorder?.maxAmplitude

    companion object {
        private const val BPP = 0.25f
    }
}
