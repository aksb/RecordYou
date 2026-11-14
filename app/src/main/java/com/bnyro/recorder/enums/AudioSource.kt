package com.bnyro.recorder.enums

/**
 * 录音/录屏共用的音频来源。
 *
 * 内部音频通过 MediaProjection 的播放捕获获取，只是旁路复制混音输出，
 * 不会改变音频路由，因此录制时外放/耳机仍然正常出声；代价是每次开始
 * 录制都需要系统弹出的屏幕捕获授权（可由 LSPosed 等方案自动同意）。
 */
enum class AudioSource(val value: Int) {
    MICROPHONE(1),

    // 系统内部音频：播放捕获
    INTERNAL(2),

    // 话筒 + 系统内部音频混音
    MICROPHONE_AND_INTERNAL(3);

    companion object {
        // 旧版本里 0 表示“无音频”，现在统一回退到话筒
        fun fromInt(value: Int) = values().firstOrNull { it.value == value } ?: MICROPHONE
    }
}
