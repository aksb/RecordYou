package com.bnyro.recorder.util

import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import android.media.AudioFormat as AndroidAudioFormat

/**
 * 捕获系统内部音频（MediaProjection 播放捕获），可选同时采集麦克风并在 PCM 层混音。
 *
 * 与 REMOTE_SUBMIX 不同，播放捕获只是旁路复制混音输出，不会改变音频路由，
 * 因此录制过程中外放/耳机仍然正常出声。
 *
 * 输出为 AAC-LC 的 m4a。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioRecorder(
    mediaProjection: MediaProjection,
    private val fileDescriptor: ParcelFileDescriptor,
    includeMicrophone: Boolean,
    bitRate: Int = DEFAULT_BIT_RATE,
    micPercent: Int = DEFAULT_MIC_PERCENT
) {
    companion object {
        private const val TAG = "SystemAudioRecorder"
        const val SAMPLE_RATE = 48_000
        const val DEFAULT_BIT_RATE = 192_000

        /** 话筒与内部音频各占一半 */
        const val DEFAULT_MIC_PERCENT = 50
        private const val CHANNEL_COUNT = 2
        private const val BYTES_PER_SAMPLE = 2
        private const val TIMEOUT_US = 10_000L
        private const val CHUNK_BYTES = SAMPLE_RATE / 10 * BYTES_PER_SAMPLE * CHANNEL_COUNT
    }

    /**
     * 话筒占比换算成增益，使用等功率曲线而非线性比例：
     * 滑块在中间时两路各约 70.7%，左右拖动时整体响度不会明显塌陷。
     */
    private val micGain: Float
    private val internalGain: Float

    private val encoder: MediaCodec
    private val muxer: MediaMuxer
    private val internalRecord: AudioRecord
    private var micRecord: AudioRecord?
    private val micMono: Boolean
    private val bufferInfo = MediaCodec.BufferInfo()

    @Volatile
    private var running = false
    private var worker: Thread? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFrames = 0L

    @Volatile
    var currentAmplitude = 0
        private set

    /** 开始采集时的系统时间，用于和视频轨对齐 */
    @Volatile
    var startNanos = 0L
        private set

    init {
        val angle = micPercent.coerceIn(0, 100) / 100.0 * (Math.PI / 2)
        micGain = sin(angle).toFloat()
        internalGain = cos(angle).toFloat()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val internalFormat = AndroidAudioFormat.Builder()
            .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
            .build()

        internalRecord = AudioRecord.Builder()
            .setAudioFormat(internalFormat)
            .setBufferSizeInBytes(
                max(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AndroidAudioFormat.CHANNEL_IN_STEREO,
                        AndroidAudioFormat.ENCODING_PCM_16BIT
                    ),
                    CHUNK_BYTES * 4
                )
            )
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        var mic: AudioRecord? = null
        var mono = false
        // 话筒占比为 0 时完全不打开麦克风，省电且避免占用麦克风
        if (includeMicrophone && micPercent > 0) {
            for (mask in listOf(
                AndroidAudioFormat.CHANNEL_IN_STEREO,
                AndroidAudioFormat.CHANNEL_IN_MONO
            )) {
                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.MIC)
                        .setAudioFormat(
                            AndroidAudioFormat.Builder()
                                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(mask)
                                .build()
                        )
                        .setBufferSizeInBytes(
                            max(
                                AudioRecord.getMinBufferSize(
                                    SAMPLE_RATE,
                                    mask,
                                    AndroidAudioFormat.ENCODING_PCM_16BIT
                                ) * 2,
                                CHUNK_BYTES * 4
                            )
                        )
                        .build()
                }.getOrNull()
                if (record != null && record.state == AudioRecord.STATE_INITIALIZED) {
                    mic = record
                    mono = mask == AndroidAudioFormat.CHANNEL_IN_MONO
                    break
                }
                record?.release()
            }
        }
        micRecord = mic
        micMono = mono

        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNEL_COUNT
        ).apply {
            setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK_BYTES)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        muxer = MediaMuxer(
            fileDescriptor.fileDescriptor,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        if (internalRecord.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("无法初始化内部音频采集")
        }
        Log.i(
            TAG,
            "初始化完成，麦克风=${if (mic != null) "开" else "关"} 增益=话筒${"%.2f".format(micGain)}/内部${"%.2f".format(internalGain)}"
        )
    }

    fun start() {
        if (running) return
        internalRecord.startRecording()
        // 麦克风启动失败时不能拖累内部音频：退化为仅内部音频继续录制
        micRecord?.let { mic ->
            runCatching { mic.startRecording() }.onFailure {
                Log.w(TAG, "麦克风启动失败，仅录制内部音频", it)
                runCatching { mic.release() }
                micRecord = null
            }
        }
        encoder.start()
        startNanos = SystemClock.elapsedRealtimeNanos()
        running = true
        worker = thread(name = "SystemAudioRecorder") { encodeLoop() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { worker?.join(1000) }
        worker = null
        // 先向编码器送入 EOS，再排空所有剩余数据；buffer 输入的编码器
        // 不支持 signalEndOfInputStream()，所以用空 buffer + EOS 标记。
        runCatching {
            val index = encoder.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                encoder.queueInputBuffer(
                    index,
                    0,
                    0,
                    totalFrames * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                )
            }
        }
        runCatching { drainEncoder(true) }
        runCatching { internalRecord.stop() }
        runCatching { micRecord?.stop() }
    }

    fun release() {
        Log.i(TAG, "停止采集：muxerStarted=$muxerStarted 帧数=$totalFrames")
        runCatching { internalRecord.release() }
        runCatching { micRecord?.release() }
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        if (muxerStarted) {
            runCatching { muxer.stop() }
        }
        runCatching { muxer.release() }
        runCatching { fileDescriptor.close() }
    }

    private fun encodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val internalBuffer = ByteArray(CHUNK_BYTES)
        val micBuffer = ByteArray(CHUNK_BYTES)
        val mixedBuffer = ByteArray(CHUNK_BYTES)
        try {
            while (running) {
                val read = internalRecord.read(internalBuffer, 0, CHUNK_BYTES)
                if (read < 0) {
                    Log.w(TAG, "内部音频读取失败: $read")
                    break
                }
                if (read == 0) continue

                val pcm: ByteArray
                val mic = micRecord
                if (mic != null) {
                    java.util.Arrays.fill(micBuffer, 0)
                    val micRead = readMic(mic, micBuffer)
                    mixPcm(
                        internalBuffer,
                        micBuffer,
                        read,
                        if (micRead > 0) micRead else 0,
                        mixedBuffer
                    )
                    pcm = mixedBuffer
                } else {
                    pcm = internalBuffer
                }

                currentAmplitude = peakAmplitude(pcm, read)
                feedEncoder(pcm, read)
                drainEncoder(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "音频采集线程异常", e)
        }
    }

    /**
     * 非阻塞读取麦克风：麦克风一旦读不到数据（被占用、被系统静音等），
     * 不能让整个采集循环卡死，否则录出来的文件会是空的（表现为"完全没声音"）。
     */
    private fun readMic(record: AudioRecord, buffer: ByteArray): Int {
        return try {
            record.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
        } catch (e: Exception) {
            Log.w(TAG, "麦克风读取失败，后续仅录制内部音频", e)
            runCatching { record.stop() }
            runCatching { record.release() }
            micRecord = null
            0
        }
    }

    private fun mixPcm(
        internalBuffer: ByteArray,
        micBuffer: ByteArray,
        bytes: Int,
        micBytes: Int,
        output: ByteArray
    ) {
        var i = 0
        while (i < bytes) {
            val internalSample =
                ((internalBuffer[i + 1].toInt() shl 8) or (internalBuffer[i].toInt() and 0xFF))
                    .toShort()
                    .toInt()
            val frame = i / 4
            val micOffset = if (micMono) frame * 2 else i
            val micSample = if (micOffset + 1 < micBytes) {
                ((micBuffer[micOffset + 1].toInt() shl 8) or (micBuffer[micOffset].toInt() and 0xFF))
                    .toShort()
                    .toInt()
            } else {
                0
            }
            // 按配比缩放到 0-1 的增益后再相加，避免直接满幅相加导致削波
            val sample = (internalSample * internalGain + micSample * micGain)
                .toInt()
                .coerceIn(-32768, 32767)
            output[i] = sample.toByte()
            output[i + 1] = (sample shr 8).toByte()
            i += 2
        }
    }

    private fun peakAmplitude(pcm: ByteArray, bytes: Int): Int {
        var peak = 0
        var i = 0
        while (i < bytes) {
            val sample =
                ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            peak = max(peak, abs(sample))
            i += 2
        }
        return peak
    }

    private fun feedEncoder(pcm: ByteArray, bytes: Int) {
        var inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
        while (inputIndex < 0 && running) {
            inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
            drainEncoder(false)
        }
        if (inputIndex < 0) return

        val inputBuffer = encoder.getInputBuffer(inputIndex) ?: return
        inputBuffer.clear()
        inputBuffer.put(pcm, 0, bytes)
        val presentationTimeUs = totalFrames * 1_000_000L / SAMPLE_RATE
        encoder.queueInputBuffer(inputIndex, 0, bytes, presentationTimeUs, 0)
        totalFrames += bytes / (BYTES_PER_SAMPLE * CHANNEL_COUNT)
    }

    private fun drainEncoder(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }

                index >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(index)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(index, false)
                    if (endOfStream &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    ) {
                        return
                    }
                }

                else -> {
                    if (!endOfStream) return
                    // 没收到 EOS 时最多等 1 秒，避免 stop() 卡死
                    if (++idleRounds > 100) return
                }
            }
        }
    }
}
