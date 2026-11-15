package com.bnyro.recorder.services

import android.app.Activity
import android.content.Context
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.annotation.RequiresApi
import com.bnyro.recorder.App
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.SystemAudioRecorder

class AudioRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_audio)

    private var mediaProjection: MediaProjection? = null
    private var systemAudioRecorder: SystemAudioRecorder? = null
    private var isDestroying = false

    override val fgServiceType: Int?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (currentAudioSource()) {
                AudioSource.INTERNAL -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                AudioSource.MICROPHONE_AND_INTERNAL ->
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE

                AudioSource.MICROPHONE -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
        } else {
            null
        }

    private fun currentAudioSource() = AudioSource.fromInt(
        Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
    )

    fun prepare(data: ActivityResult) {
        val projectionManager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager
        mediaProjection = runCatching {
            projectionManager.getMediaProjection(Activity.RESULT_OK, data.data!!)
        }.onFailure {
            Log.e(TAG, "无法获取 MediaProjection", it)
        }.getOrNull()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    onDestroy()
                }
            }, null)
        }
    }

    override fun start() {
        val audioSource = currentAudioSource()
        if (audioSource != AudioSource.MICROPHONE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (!startSystemAudioRecording(audioSource == AudioSource.MICROPHONE_AND_INTERNAL)) {
                return
            }
        } else {
            startMicrophoneRecording()
        }

        super.start()
    }

    private fun startMicrophoneRecording() {
        recorder = PlayerHelper.newRecorder(this).apply {
            setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)

            // Sample rate / bitrate / channel count must be set after
            // setOutputFormat() + setAudioEncoder(), not before -
            // MediaRecorder's documented state machine only accepts these
            // calls at this point (see the same fix in ScreenRecorderService).
            val sampleRatePref = Preferences.prefs.getInt(Preferences.audioSampleRateKey, -1).takeIf { it > 0 }
            val audioBitrate = Preferences.prefs.getInt(Preferences.audioBitrateKey, -1).takeIf { it > 0 }
            if (sampleRatePref != null) {
                setAudioSamplingRate(sampleRatePref)
            }
            // 比特率为“自动”时交给系统决定，避免用采样率推算出一个异常高的码率
            if (audioBitrate != null) {
                setAudioEncodingBitRate(audioBitrate)
            }

            Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value).let {
                setAudioChannels(it)
            }

            outputFile = (application as App).fileRepository.getOutputFile("m4a")
            if (outputFile == null) {
                Toast.makeText(
                    this@AudioRecorderService,
                    R.string.cant_access_selected_folder,
                    Toast.LENGTH_LONG
                ).show()
                onDestroy()
                return
            }

            fileDescriptor = contentResolver.openFileDescriptor(outputFile!!.uri, "w")
            setOutputFile(fileDescriptor?.fileDescriptor)

            runCatching {
                prepare()
            }

            start()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startSystemAudioRecording(includeMicrophone: Boolean): Boolean {
        val projection = mediaProjection
        if (projection == null) {
            Toast.makeText(this, R.string.internal_audio_failed, Toast.LENGTH_LONG).show()
            onDestroy()
            return false
        }

        outputFile = (application as App).fileRepository.getOutputFile("m4a")
        if (outputFile == null) {
            Toast.makeText(this, R.string.cant_access_selected_folder, Toast.LENGTH_LONG).show()
            onDestroy()
            return false
        }

        val descriptor = contentResolver.openFileDescriptor(outputFile!!.uri, "w")
        if (descriptor == null) {
            Toast.makeText(this, R.string.cant_access_selected_folder, Toast.LENGTH_LONG).show()
            onDestroy()
            return false
        }

        return runCatching {
            SystemAudioRecorder(
                mediaProjection = projection,
                fileDescriptor = descriptor,
                includeMicrophone = includeMicrophone,
                bitRate = Preferences.prefs.getInt(Preferences.audioBitrateKey, 192_000)
                    .takeIf { it > 0 } ?: 192_000,
                micPercent = Preferences.prefs.getInt(
                    Preferences.audioMixRatioKey,
                    SystemAudioRecorder.DEFAULT_MIC_PERCENT
                )
            ).also {
                it.start()
                systemAudioRecorder = it
            }
            true
        }.onFailure {
            runCatching { descriptor.close() }
            Log.e(TAG, "无法采集内部音频", it)
            Toast.makeText(this, R.string.internal_audio_failed, Toast.LENGTH_LONG).show()
            onDestroy()
        }.getOrDefault(false)
    }

    override fun onDestroy() {
        if (isDestroying) return
        isDestroying = true

        systemAudioRecorder?.let {
            it.stop()
            it.release()
        }
        systemAudioRecorder = null

        super.onDestroy()

        mediaProjection?.stop()
        mediaProjection = null
    }

    override fun getCurrentAmplitude(): Int? =
        systemAudioRecorder?.currentAmplitude ?: recorder?.maxAmplitude

    companion object {
        private const val TAG = "AudioRecorderService"
    }
}
