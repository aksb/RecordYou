package com.bnyro.recorder.enums

import android.media.MediaRecorder
import android.os.Build
import com.bnyro.recorder.util.Preferences

data class VideoFormat(
    val name: String,
    val codec: Int,
    val extension: String,
    val format: Int
) {
    companion object {
        // WebM (VP8/VP9) removed: MediaRecorder failed to actually produce a
        // usable file with these on the devices this was tested on (always a
        // fixed ~6KB empty file - the encoder never got initialized, and the
        // failure was silently swallowed). H.264/H.265 cover every real use
        // case for this app anyway.
        val codecs = mutableListOf(
            VideoFormat(
                "H.264",
                MediaRecorder.VideoEncoder.H264,
                "mp4",
                MediaRecorder.OutputFormat.MPEG_4
            )
        ).also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                it.add(
                    VideoFormat(
                        "H.265",
                        MediaRecorder.VideoEncoder.HEVC,
                        "mp4",
                        MediaRecorder.OutputFormat.MPEG_4
                    )
                )
            }
        }

        fun getCurrent() = codecs.firstOrNull {
            it.codec == Preferences.prefs.getInt(
                Preferences.videoCodecKey,
                MediaRecorder.VideoEncoder.H264
            )
        } ?: codecs.first() // falls back to H.264 if a previously-saved prefs
        // value pointed at the now-removed WebM codecs
    }
}
