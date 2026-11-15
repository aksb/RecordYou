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
    const val floatingBallKey = "floatingBall"
    const val videoResolutionKey = "videoResolution"
    const val ballPosXPortraitKey = "ballPosXPortrait"
    const val ballPosYPortraitKey = "ballPosYPortrait"
    const val ballPosXLandscapeKey = "ballPosXLandscape"
    const val ballPosYLandscapeKey = "ballPosYLandscape"
    const val annotationXKey = "annotationX"
    const val annotationYKey = "annotationY"

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        migrateVideoAudioSettings()
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

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(action).apply()
    }

    fun getString(key: String, defValue: String) = prefs.getString(key, defValue) ?: defValue
}
