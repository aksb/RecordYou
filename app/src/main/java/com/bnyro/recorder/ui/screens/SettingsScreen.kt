package com.bnyro.recorder.ui.screens

import android.net.Uri
import android.os.Build
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.AudioChannels
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.ui.common.CheckboxPref
import com.bnyro.recorder.ui.common.ChipSelector
import com.bnyro.recorder.ui.common.ClickableIcon
import com.bnyro.recorder.ui.common.CustomNumInputPref
import com.bnyro.recorder.ui.common.SelectionDialog
import com.bnyro.recorder.ui.components.NamingPatternPref
import com.bnyro.recorder.ui.dialogs.AboutDialog
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.util.PickFolderContract
import com.bnyro.recorder.util.Preferences

private const val BITRATE_PRESET_LOW = 1_500_000
private const val BITRATE_PRESET_MEDIUM = 4_000_000
private const val BITRATE_PRESET_HIGH = 8_000_000
private val BITRATE_PRESETS = listOf(BITRATE_PRESET_LOW, BITRATE_PRESET_MEDIUM, BITRATE_PRESET_HIGH)
// Not a real bitrate value, never persisted - only used to tell the "自定义"
// chip apart from the others in the selector below.
private const val CUSTOM_BITRATE_SENTINEL = -2
// AAC-LC 常用采样率，填写其它值时仅提示不阻止
private val SAMPLE_RATE_STANDARD_VALUES =
    listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000)
private val SAMPLE_RATE_RANGE = 8000..48000
private val AUDIO_BITRATE_RANGE = 16_000..320_000
private val AUDIO_BITRATE_QUICK_VALUES = listOf(64_000, 96_000, 128_000, 192_000, 256_000)
private val VIDEO_BITRATE_RANGE = 100_000..50_000_000
private val VIDEO_BITRATE_QUICK_VALUES = listOf(1_500_000, 4_000_000, 8_000_000, 12_000_000, 20_000_000)
private val VIDEO_BITRATE_QUICK_LABELS = listOf("1.5M", "4M", "8M", "12M", "20M")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val themeModel: ThemeModel = viewModel(LocalContext.current as ComponentActivity)
    var audioChannels by remember {
        mutableStateOf(
            AudioChannels.fromInt(
                Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value)
            )
        )
    }
    var audioSource by remember {
        mutableStateOf(
            AudioSource.fromInt(
                Preferences.prefs.getInt(Preferences.audioSourceKey, AudioSource.MICROPHONE.value)
            )
        )
    }
    // 录屏使用独立的音频参数，与录音互不影响
    var videoAudioChannels by remember {
        mutableStateOf(
            AudioChannels.fromInt(
                Preferences.prefs.getInt(
                    Preferences.videoAudioChannelsKey,
                    AudioChannels.MONO.value
                )
            )
        )
    }
    var videoAudioSource by remember {
        mutableStateOf(
            AudioSource.fromInt(
                Preferences.prefs.getInt(
                    Preferences.videoAudioSourceKey,
                    AudioSource.MICROPHONE.value
                )
            )
        )
    }
    var videoEncoder by remember {
        mutableStateOf(VideoFormat.getCurrent())
    }

    val context = LocalContext.current

    val directoryPicker = rememberLauncherForActivityResult(PickFolderContract()) {
        it ?: return@rememberLauncherForActivityResult
        Preferences.edit { putString(Preferences.targetFolderKey, it.toString()) }
    }

    var showAbout by remember {
        mutableStateOf(false)
    }
    var showThemePref by remember {
        mutableStateOf(false)
    }

    val view = LocalView.current

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            actions = {
                ClickableIcon(
                    imageVector = Icons.Default.DarkMode,
                    contentDescription = stringResource(R.string.theme)
                ) {
                    showThemePref = true
                }
                ClickableIcon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.about)
                ) {
                    showAbout = true
                }
            }
        )
    }) { paddingValues ->
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState)
        ) {
            SectionTitle(stringResource(R.string.section_general))
            Spacer(modifier = Modifier.height(5.dp))
            Button(
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    val lastDir = Preferences.prefs.getString(Preferences.targetFolderKey, "")
                        .takeIf { !it.isNullOrBlank() }
                    directoryPicker.launch(lastDir?.let { Uri.parse(it) })
                }
            ) {
                Text(stringResource(R.string.choose_dir))
            }
            Spacer(modifier = Modifier.height(10.dp))
            NamingPatternPref()

            SectionDivider()
            SectionTitle(stringResource(R.string.section_audio_recording))
            Spacer(modifier = Modifier.height(5.dp))
            val audioSourceValues = AudioSource.values().map { it.value }
            ChipSelector(
                title = stringResource(R.string.audio_source),
                entries = listOf(
                    R.string.microphone,
                    R.string.internal_audio_playback,
                    R.string.microphone_and_internal_audio
                ).map {
                    stringResource(it)
                },
                values = audioSourceValues,
                selections = listOf(audioSource.value)
            ) { index, newValue ->
                if (newValue) {
                    audioSource = AudioSource.fromInt(audioSourceValues[index])
                    Preferences.edit {
                        putInt(Preferences.audioSourceKey, audioSourceValues[index])
                        // 无损 WAV 仅话筒模式可用
                        if (audioSource != AudioSource.MICROPHONE) {
                            putBoolean(Preferences.losslessRecorderKey, false)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row {
                CustomNumInputPref(
                    key = Preferences.audioSampleRateKey,
                    title = stringResource(R.string.sample_rate),
                    defValue = 44_100,
                    valueRange = SAMPLE_RATE_RANGE,
                    standardValues = SAMPLE_RATE_STANDARD_VALUES,
                    quickValues = SAMPLE_RATE_STANDARD_VALUES,
                    hintText = stringResource(R.string.sample_rate_hint)
                )
                Spacer(modifier = Modifier.width(10.dp))
                CustomNumInputPref(
                    key = Preferences.audioBitrateKey,
                    title = stringResource(R.string.audio_bitrate),
                    defValue = 192_000,
                    valueRange = AUDIO_BITRATE_RANGE,
                    quickValues = AUDIO_BITRATE_QUICK_VALUES,
                    hintText = stringResource(R.string.audio_bitrate_hint)
                )
            }
            val audioChannelsValues = AudioChannels.values().map { it.value }
            ChipSelector(
                entries = listOf(R.string.mono, R.string.stereo).map {
                    stringResource(it)
                },
                values = audioChannelsValues,
                selections = listOf(audioChannels.value)
            ) { index, newValue ->
                if (newValue) {
                    audioChannels = AudioChannels.fromInt(audioChannelsValues[index])
                    Preferences.edit {
                        putInt(Preferences.audioChannelsKey, audioChannelsValues[index])
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                CheckboxPref(
                    prefKey = Preferences.losslessRecorderKey,
                    title = stringResource(R.string.lossless_audio),
                    summary = stringResource(
                        if (audioSource == AudioSource.MICROPHONE) {
                            R.string.lossless_audio_desc
                        } else {
                            R.string.lossless_audio_microphone_only
                        }
                    ),
                    enabled = audioSource == AudioSource.MICROPHONE
                )
            }

            SectionDivider()
            SectionTitle(stringResource(R.string.section_video_recording))
            Spacer(modifier = Modifier.height(5.dp))
            SubSectionTitle(stringResource(R.string.section_audio))
            Spacer(modifier = Modifier.height(5.dp))
            val videoAudioSourceValues = AudioSource.values().map { it.value }
            ChipSelector(
                title = stringResource(R.string.audio_source),
                entries = listOf(
                    R.string.microphone,
                    R.string.internal_audio_playback,
                    R.string.microphone_and_internal_audio
                ).map {
                    stringResource(it)
                },
                values = videoAudioSourceValues,
                selections = listOf(videoAudioSource.value)
            ) { index, newValue ->
                if (newValue) {
                    videoAudioSource = AudioSource.fromInt(videoAudioSourceValues[index])
                    Preferences.edit {
                        putInt(Preferences.videoAudioSourceKey, videoAudioSourceValues[index])
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 内部音频固定为 48 kHz 立体声，采样率与声道仅对话筒来源生效
            val microphoneSource = videoAudioSource == AudioSource.MICROPHONE
            Row {
                CustomNumInputPref(
                    key = Preferences.videoAudioSampleRateKey,
                    title = stringResource(R.string.sample_rate),
                    defValue = 44_100,
                    enabled = microphoneSource,
                    valueRange = SAMPLE_RATE_RANGE,
                    standardValues = SAMPLE_RATE_STANDARD_VALUES,
                    quickValues = SAMPLE_RATE_STANDARD_VALUES,
                    hintText = stringResource(R.string.sample_rate_hint)
                )
                Spacer(modifier = Modifier.width(10.dp))
                CustomNumInputPref(
                    key = Preferences.videoAudioBitrateKey,
                    title = stringResource(R.string.audio_bitrate),
                    defValue = 192_000,
                    valueRange = AUDIO_BITRATE_RANGE,
                    quickValues = AUDIO_BITRATE_QUICK_VALUES,
                    hintText = stringResource(R.string.audio_bitrate_hint)
                )
            }
            ChipSelector(
                entries = listOf(R.string.mono, R.string.stereo).map {
                    stringResource(it)
                },
                values = AudioChannels.values().map { it.value },
                selections = listOf(videoAudioChannels.value),
                enabled = microphoneSource
            ) { index, newValue ->
                if (newValue) {
                    val value = AudioChannels.values()[index].value
                    videoAudioChannels = AudioChannels.fromInt(value)
                    Preferences.edit {
                        putInt(Preferences.videoAudioChannelsKey, value)
                    }
                }
            }
            if (!microphoneSource) {
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = stringResource(R.string.internal_audio_fixed_format),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(15.dp))
            SubSectionTitle(stringResource(R.string.section_video))
            Spacer(modifier = Modifier.height(5.dp))
            ChipSelector(
                title = stringResource(R.string.video_codec),
                entries = VideoFormat.codecs.map { it.name },
                values = VideoFormat.codecs.map { it.codec },
                selections = listOf(videoEncoder.codec)
            ) { index, newValue ->
                if (newValue) {
                    videoEncoder = VideoFormat.codecs[index]
                    Preferences.edit { putInt(Preferences.videoCodecKey, videoEncoder.codec) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            var videoResolution by remember {
                mutableStateOf(Preferences.prefs.getInt(Preferences.videoResolutionKey, 0))
            }
            ChipSelector(
                title = stringResource(R.string.video_quality),
                entries = listOf(
                    stringResource(R.string.quality_original),
                    "360p",
                    "480p",
                    "720p",
                    "1080p"
                ),
                values = listOf(0, 360, 480, 720, 1080),
                selections = listOf(videoResolution)
            ) { index, newValue ->
                if (newValue) {
                    videoResolution = listOf(0, 360, 480, 720, 1080)[index]
                    Preferences.edit { putInt(Preferences.videoResolutionKey, videoResolution) }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            var videoBitrate by remember {
                mutableStateOf(Preferences.prefs.getInt(Preferences.videoBitrateKey, -1))
            }
            // "Custom" isn't a real stored bitrate value - it's just which
            // chip/row is showing. Tracked separately so tapping "自定义"
            // reveals the input row without first requiring a valid saved
            // number, and so the row always starts pre-filled with whatever
            // is actually persisted right now instead of a stale value from
            // whenever this screen first composed.
            var customBitrateSelected by remember {
                mutableStateOf(videoBitrate > 0 && videoBitrate !in BITRATE_PRESETS)
            }
            var customBitrateInput by remember {
                mutableStateOf(if (customBitrateSelected) videoBitrate.toString() else "")
            }
            ChipSelector(
                title = stringResource(R.string.video_bitrate),
                entries = listOf(
                    stringResource(R.string.quality_low),
                    stringResource(R.string.quality_medium),
                    stringResource(R.string.quality_high),
                    stringResource(R.string.auto),
                    stringResource(R.string.custom)
                ),
                values = listOf(
                    BITRATE_PRESET_LOW,
                    BITRATE_PRESET_MEDIUM,
                    BITRATE_PRESET_HIGH,
                    -1,
                    CUSTOM_BITRATE_SENTINEL
                ),
                selections = listOf(if (customBitrateSelected) CUSTOM_BITRATE_SENTINEL else videoBitrate)
            ) { index, newValue ->
                if (newValue) {
                    val picked = listOf(
                        BITRATE_PRESET_LOW,
                        BITRATE_PRESET_MEDIUM,
                        BITRATE_PRESET_HIGH,
                        -1,
                        CUSTOM_BITRATE_SENTINEL
                    )[index]
                    if (picked == CUSTOM_BITRATE_SENTINEL) {
                        customBitrateSelected = true
                        customBitrateInput = videoBitrate.takeIf { it > 0 }?.toString().orEmpty()
                    } else {
                        customBitrateSelected = false
                        videoBitrate = picked
                        Preferences.edit { putInt(Preferences.videoBitrateKey, picked) }
                    }
                }
            }
            if (customBitrateSelected) {
                Spacer(modifier = Modifier.height(5.dp))
                val customBitrateValue = customBitrateInput.toIntOrNull()
                val customBitrateValid =
                    customBitrateValue != null && customBitrateValue in VIDEO_BITRATE_RANGE
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        modifier = Modifier.padding(horizontal = 5.dp),
                        value = customBitrateInput,
                        onValueChange = { customBitrateInput = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = customBitrateInput.isNotEmpty() && !customBitrateValid,
                        label = { Text(stringResource(R.string.video_bitrate)) },
                        supportingText = {
                            Text(
                                if (customBitrateInput.isNotEmpty() && !customBitrateValid) {
                                    stringResource(
                                        R.string.value_out_of_range,
                                        VIDEO_BITRATE_RANGE.first,
                                        VIDEO_BITRATE_RANGE.last
                                    )
                                } else {
                                    stringResource(R.string.video_bitrate_hint)
                                }
                            )
                        }
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Button(
                        onClick = {
                            customBitrateValue?.let {
                                videoBitrate = it
                                Preferences.edit { putInt(Preferences.videoBitrateKey, it) }
                            }
                        },
                        enabled = customBitrateValid
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                ChipSelector(
                    entries = VIDEO_BITRATE_QUICK_LABELS,
                    values = VIDEO_BITRATE_QUICK_VALUES,
                    selections = listOfNotNull(customBitrateValue)
                ) { index, selected ->
                    if (selected) customBitrateInput = VIDEO_BITRATE_QUICK_VALUES[index].toString()
                }
            }

            SectionDivider()
            SectionTitle(stringResource(R.string.section_other))
            Spacer(modifier = Modifier.height(5.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CheckboxPref(
                    prefKey = Preferences.showOverlayAnnotationToolKey,
                    title = stringResource(R.string.screen_recorder_annotation),
                    summary = stringResource(R.string.screen_recorder_annotation_desc)
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            CheckboxPref(
                prefKey = Preferences.showVisualizerTimestamps,
                title = stringResource(R.string.audio_visualizer_timestamps),
                summary = stringResource(R.string.audio_visualizer_timestamps_description)
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }

    if (showThemePref) {
        SelectionDialog(
            onDismissRequest = { showThemePref = false },
            title = stringResource(R.string.theme),
            entries = listOf(
                R.string.system,
                R.string.light,
                R.string.dark,
                R.string.amoled_dark
            ).map {
                stringResource(it)
            }
        ) {
            themeModel.themeMode = ThemeMode.values()[it]
            Preferences.edit { putString(Preferences.themeModeKey, ThemeMode.values()[it].name) }
        }
    }

    if (showAbout) {
        AboutDialog {
            showAbout = false
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp
    )
}

@Composable
private fun SectionDivider() {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Spacer(modifier = Modifier.height(16.dp))
}

@Composable
private fun SubSectionTitle(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary
    )
}
