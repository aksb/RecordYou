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
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.util.PlayerHelper
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.SystemAudioRecorder

class AudioRecorderService : RecorderService() {
    override val notificationTitle: String
        get() = getString(R.string.recording_audio)

    override val recorderType = RecorderType.AUDIO

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

        // 全版本注册：系统侧（状态栏胶囊、锁屏、用户撤销授权）停止投屏时
        // 需要正常收尾并保存录音
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                onDestroy()
            }
        }, null)
    }

    override fun start() {
        // 同一个服务实例只启动一次：重复的启动请求（重复绑定/重复点击）
        // 会创建第二个 MediaRecorder，而停止时只能停掉一个。
        if (!acquireStart()) return

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

        super.onDestroy()
    }

    override fun onStopCapture() {
        // 运行在后台线程：写 m4a 的 moov、释放编码器可能阻塞几十秒，
        // 放在主线程会让停止广播排队，看起来就是“怎么都停不下来”。
        systemAudioRecorder?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        systemAudioRecorder = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
    }

    // 停止录制时 UI 的振幅轮询可能比 release() 晚一拍，此时 getMaxAmplitude
    // 会抛 IllegalStateException 直接把整个进程带崩（无障碍服务也会一起被杀）
    override fun getCurrentAmplitude(): Int? = runCatching {
        systemAudioRecorder?.currentAmplitude ?: recorder?.maxAmplitude
    }.getOrNull()

    companion object {
        private const val TAG = "AudioRecorderService"
    }
}
