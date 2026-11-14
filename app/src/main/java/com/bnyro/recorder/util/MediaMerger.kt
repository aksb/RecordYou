package com.bnyro.recorder.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 把无声的视频文件和单独的音频文件合流成一个 mp4。
 * 轨道数据直接复制，不重新编码，速度快且没有二次损失。
 */
object MediaMerger {
    private const val TAG = "MediaMerger"
    private const val TIMEOUT_US = 10_000L

    fun merge(
        videoFile: File,
        audioFile: File,
        output: ParcelFileDescriptor,
        audioOffsetUs: Long
    ): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val videoTrack = findTrack(videoExtractor, "video/")
            if (videoTrack < 0) {
                Log.e(TAG, "视频文件中没有视频轨")
                return false
            }

            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            val audioTrack = findTrack(audioExtractor, "audio/")

            muxer = MediaMuxer(
                output.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            videoExtractor.selectTrack(videoTrack)
            val videoOutputTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))

            var audioOutputTrack = -1
            if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack)
                audioOutputTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            }

            muxer.start()
            muxerStarted = true

            copyTrack(videoExtractor, muxer, videoOutputTrack, 0L)
            if (audioOutputTrack >= 0) {
                copyTrack(
                    audioExtractor,
                    muxer,
                    audioOutputTrack,
                    audioOffsetUs.coerceAtLeast(0L)
                )
            }

            muxer.stop()
            muxerStarted = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "合流失败", e)
            false
        } finally {
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith(mimePrefix) == true) return i
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        offsetUs: Long
    ) {
        // 视频码率高时单个关键帧可能远超 1MB，缓冲区必须按样本实际大小
        // 动态扩容，否则 readSampleData 会抛 IllegalArgumentException，
        // 导致整个合流失败（表现为 mp4 没有声音）。
        var buffer = ByteBuffer.allocateDirect(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.sampleSize.toInt()
            if (sampleSize < 0) break
            if (sampleSize > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(sampleSize)
            }
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            val sampleTime = extractor.sampleTime
            if (sampleTime + offsetUs >= 0) {
                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleTime + offsetUs
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
            }
            extractor.advance()
        }
    }
}
