package com.bnyro.recorder.util

import android.content.Context
import android.content.SharedPreferences

object Preferences {
    private const val PREF_FILE_NAME = "RecordYou"
    lateinit var prefs: SharedPreferences

    const val targetFolderKey = "targetFolder"
    const val audioSourceKey = "audioSource"
    const val audioSampleRateKey = "audioSampleRate"
    const val audioBitrateKey = "audioBitrate"
    const val audioChannelsKey = "audioChannels"
    // 话筒+内部音频时的麦克风占比（0-100），其余部分给内部音频
    const val audioMixRatioKey = "audioMixRatio"
    // 录屏的音频参数与录音分开，避免互相影响
    const val videoAudioSourceKey = "videoAudioSource"
    const val videoAudioSampleRateKey = "videoAudioSampleRate"
    const val videoAudioBitrateKey = "videoAudioBitrate"
    const val videoAudioChannelsKey = "videoAudioChannels"
    const val videoAudioMixRatioKey = "videoAudioMixRatio"
    private const val videoAudioSettingsMigratedKey = "videoAudioSettingsMigrated"
    const val videoCodecKey = "videoCodec"
    const val videoBitrateKey = "videoBitrate"
    const val themeModeKey = "themeMode"
    const val losslessRecorderKey = "losslessRecorder"
    const val namingPatternKey = "namingPattern"
    const val showOverlayAnnotationToolKey = "annotationTool"
    const val showVisualizerTimestamps = "visualizerTimestamp"
    // 旧版只有一个悬浮球，仅供迁移使用
    const val floatingBallKey = "floatingBall"
    const val audioBallKey = "audioBall"
    const val videoBallKey = "videoBall"
    const val hideBallInRecordingsKey = "hideBallInRecordings"
    private const val floatingBallSplitMigratedKey = "floatingBallSplitMigrated"
    const val videoResolutionKey = "videoResolution"
    // 旧版单球位置，仅供迁移使用
    const val ballPosXPortraitKey = "ballPosXPortrait"
    const val ballPosYPortraitKey = "ballPosYPortrait"
    const val ballPosXLandscapeKey = "ballPosXLandscape"
    const val ballPosYLandscapeKey = "ballPosYLandscape"
    const val audioBallPosXPortraitKey = "audioBallPosXPortrait"
    const val audioBallPosYPortraitKey = "audioBallPosYPortrait"
    const val audioBallPosXLandscapeKey = "audioBallPosXLandscape"
    const val audioBallPosYLandscapeKey = "audioBallPosYLandscape"
    const val videoBallPosXPortraitKey = "videoBallPosXPortrait"
    const val videoBallPosYPortraitKey = "videoBallPosYPortrait"
    const val videoBallPosXLandscapeKey = "videoBallPosXLandscape"
    const val videoBallPosYLandscapeKey = "videoBallPosYLandscape"
    const val annotationXKey = "annotationX"
    const val annotationYKey = "annotationY"
    // 录屏前先选择要录制的屏幕区域（需要悬浮窗权限）
    const val regionRecordingKey = "regionRecording"
    // 上次确认的区域录制选区，按真实屏幕宽高的千分比保存（0..1000）
    const val regionLeftKey = "regionLeft"
    const val regionTopKey = "regionTop"
    const val regionRightKey = "regionRight"
    const val regionBottomKey = "regionBottom"
    // 音量键长按停止（需要系统无障碍服务）
    const val volumeKeyStopKey = "volumeKeyStop"
    const val volumeKeyStopHoldMsKey = "volumeKeyStopHoldMs"
    const val volumeKeyStopHoldDefaultMs = 800

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        migrateVideoAudioSettings()
        migrateFloatingBallSettings()
    }

    /**
     * 旧版本录音与录屏共用同一套音频参数。升级后录屏改用独立 key，
     * 这里把旧值复制一份，保证升级后录屏的音频行为不变。
     */
    private fun migrateVideoAudioSettings() {
        if (prefs.getBoolean(videoAudioSettingsMigratedKey, false)) return

        val editor = prefs.edit()
        if (prefs.contains(audioSourceKey)) {
            editor.putInt(videoAudioSourceKey, prefs.getInt(audioSourceKey, 0))
        }
        if (prefs.contains(audioSampleRateKey)) {
            editor.putInt(videoAudioSampleRateKey, prefs.getInt(audioSampleRateKey, -1))
        }
        if (prefs.contains(audioBitrateKey)) {
            editor.putInt(videoAudioBitrateKey, prefs.getInt(audioBitrateKey, -1))
        }
        if (prefs.contains(audioChannelsKey)) {
            editor.putInt(videoAudioChannelsKey, prefs.getInt(audioChannelsKey, 0))
        }
        editor.putBoolean(videoAudioSettingsMigratedKey, true)
        editor.apply()
    }

    /**
     * 旧版本只有一个悬浮球。拆分后把原来的开关状态和位置交给录屏球，
     * 升级后用户看到的球仍在原来的位置、仍然能录屏。
     */
    private fun migrateFloatingBallSettings() {
        if (prefs.getBoolean(floatingBallSplitMigratedKey, false)) return

        val editor = prefs.edit()
        if (prefs.getBoolean(floatingBallKey, false)) {
            editor.putBoolean(videoBallKey, true)
        }
        copyInt(editor, ballPosXPortraitKey, videoBallPosXPortraitKey)
        copyInt(editor, ballPosYPortraitKey, videoBallPosYPortraitKey)
        copyInt(editor, ballPosXLandscapeKey, videoBallPosXLandscapeKey)
        copyInt(editor, ballPosYLandscapeKey, videoBallPosYLandscapeKey)
        editor.putBoolean(floatingBallSplitMigratedKey, true)
        editor.apply()
    }

    private fun copyInt(editor: SharedPreferences.Editor, from: String, to: String) {
        if (prefs.contains(from)) {
            editor.putInt(to, prefs.getInt(from, Int.MIN_VALUE))
        }
    }

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(action).apply()
    }

    fun getString(key: String, defValue: String) = prefs.getString(key, defValue) ?: defValue
}
