package com.bnyro.recorder.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import com.bnyro.recorder.ui.dialogs.AutoHealDialog
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.util.LsposedModuleDetector
import com.bnyro.recorder.util.PickFolderContract
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.VolumeKeyStopHelper
import kotlin.math.roundToInt

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
    var audioMixRatio by remember {
        mutableStateOf(
            Preferences.prefs.getInt(Preferences.audioMixRatioKey, 50)
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
    var videoAudioMixRatio by remember {
        mutableStateOf(
            Preferences.prefs.getInt(Preferences.videoAudioMixRatioKey, 50)
        )
    }
    var videoEncoder by remember {
        mutableStateOf(VideoFormat.getCurrent())
    }

    val context = LocalContext.current

    // 无障碍服务需要用户去系统设置里手动开启，开关状态本身不代表服务已生效
    var accessibilityEnabled by remember {
        mutableStateOf(VolumeKeyStopHelper.isReady(context))
    }
    var showAutoHeal by remember { mutableStateOf(false) }
    var volumeKeyStopEnabled by remember {
        mutableStateOf(Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false))
    }
    var volumeKeyHoldMs by remember {
        mutableStateOf(
            Preferences.prefs.getInt(
                Preferences.volumeKeyStopHoldMsKey,
                Preferences.volumeKeyStopHoldDefaultMs
            )
        )
    }
    // 摇一摇 / 翻转停止
    var motionStopEnabled by remember {
        mutableStateOf(Preferences.prefs.getBoolean(Preferences.motionStopKey, false))
    }
    var motionSensitivity by remember {
        mutableStateOf(
            Preferences.prefs.getInt(
                Preferences.motionStopSensitivityKey,
                Preferences.motionStopSensitivityDefault
            )
        )
    }
    // 区域录制的选区界面需要“显示在其他应用上层”权限
    var overlayPermissionGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || Settings.canDrawOverlays(context)
        )
    }
    var regionRecordingEnabled by remember {
        mutableStateOf(Preferences.prefs.getBoolean(Preferences.regionRecordingKey, false))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    // 从系统无障碍 / 悬浮窗设置回来后重新检查，避免界面状态和实际是否开启对不上
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = VolumeKeyStopHelper.isReady(context)
                // 从系统设置回来后同步刷新 LSPosed 探测结果（内部有 30 秒缓存）
                if (volumeKeyStopEnabled) LsposedModuleDetector.detect(context)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    overlayPermissionGranted = Settings.canDrawOverlays(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            MixRatioPref(
                micPercent = audioMixRatio,
                enabled = audioSource == AudioSource.MICROPHONE_AND_INTERNAL,
                onValueChange = { audioMixRatio = it },
                onValueChangeFinished = {
                    Preferences.edit { putInt(Preferences.audioMixRatioKey, audioMixRatio) }
                }
            )
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
            MixRatioPref(
                micPercent = videoAudioMixRatio,
                enabled = videoAudioSource == AudioSource.MICROPHONE_AND_INTERNAL,
                onValueChange = { videoAudioMixRatio = it },
                onValueChangeFinished = {
                    Preferences.edit {
                        putInt(Preferences.videoAudioMixRatioKey, videoAudioMixRatio)
                    }
                }
            )
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
            // 区域录制决定的是采集范围，属于画面设置，放在编码参数之前
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CheckboxPref(
                    prefKey = Preferences.regionRecordingKey,
                    title = stringResource(R.string.region_recording),
                    summary = stringResource(R.string.region_recording_desc),
                    onCheckedChange = { checked ->
                        regionRecordingEnabled = checked
                        if (checked && !overlayPermissionGranted) {
                            openOverlaySettings(context)
                        }
                    }
                )
                if (regionRecordingEnabled && !overlayPermissionGranted) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = stringResource(R.string.region_recording_permission_missing),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Button(onClick = { openOverlaySettings(context) }) {
                        Text(stringResource(R.string.open_overlay_settings))
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
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
            SectionTitle(stringResource(R.string.section_quick_actions))
            Spacer(modifier = Modifier.height(5.dp))
            CheckboxPref(
                prefKey = Preferences.volumeKeyStopKey,
                title = stringResource(R.string.volume_key_stop),
                summary = stringResource(R.string.volume_key_stop_desc),
                onCheckedChange = { checked ->
                    volumeKeyStopEnabled = checked
                    // 打开总开关只展开下面的子项，不跳转任何设置页；
                    // 是否去开无障碍由用户自己点「无障碍设置」行决定
                    if (checked) LsposedModuleDetector.detect(context)
                }
            )
            if (volumeKeyStopEnabled) {
                Spacer(modifier = Modifier.height(5.dp))
                // 两种授权方式各自的生效范围，先讲清楚再展示状态
                Text(
                    text = stringResource(R.string.volume_key_stop_modes_desc),
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                // 子项1：无障碍状态只做检测展示，是否开启由系统设置决定
                PermissionStatusRow(
                    title = stringResource(R.string.a11y_status_title),
                    status = stringResource(
                        if (accessibilityEnabled) {
                            R.string.a11y_status_ok
                        } else {
                            R.string.a11y_status_missing
                        }
                    ),
                    ok = accessibilityEnabled
                )
                Spacer(modifier = Modifier.height(5.dp))
                // 无障碍设置入口：打开系统设置与自动恢复都在同一个弹窗里
                AccessibilitySettingsPref { showAutoHeal = true }
                Spacer(modifier = Modifier.height(10.dp))
                // 子项2：LSPosed 状态只做展示，探测在进入页面 / 打开开关时自动触发
                val lsStatus = LsposedModuleDetector.status
                PermissionStatusRow(
                    title = stringResource(R.string.lsposed_status_title),
                    status = stringResource(
                        when (lsStatus) {
                            LsposedModuleDetector.Status.ACTIVE -> R.string.lsposed_status_ok
                            LsposedModuleDetector.Status.INACTIVE -> R.string.lsposed_status_missing
                            else -> R.string.lsposed_status_checking
                        }
                    ),
                    ok = when (lsStatus) {
                        LsposedModuleDetector.Status.ACTIVE -> true
                        LsposedModuleDetector.Status.INACTIVE -> false
                        else -> null
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                HoldDurationPref(
                    holdMs = volumeKeyHoldMs,
                    onValueChange = { volumeKeyHoldMs = it },
                    onValueChangeFinished = {
                        Preferences.edit {
                            putInt(Preferences.volumeKeyStopHoldMsKey, volumeKeyHoldMs)
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            CheckboxPref(
                prefKey = Preferences.motionStopKey,
                title = stringResource(R.string.motion_stop),
                summary = stringResource(R.string.motion_stop_desc),
                onCheckedChange = { motionStopEnabled = it }
            )
            if (motionStopEnabled) {
                Spacer(modifier = Modifier.height(10.dp))
                MotionSensitivityPref(
                    sensitivity = motionSensitivity,
                    onValueChange = { motionSensitivity = it },
                    onValueChangeFinished = {
                        Preferences.edit {
                            putInt(Preferences.motionStopSensitivityKey, motionSensitivity)
                        }
                    }
                )
            }

            SectionDivider()
            SectionTitle(stringResource(R.string.section_other))
            Spacer(modifier = Modifier.height(5.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CheckboxPref(
                    prefKey = Preferences.hideBallInRecordingsKey,
                    title = stringResource(R.string.hide_floating_ball_in_recordings),
                    summary = stringResource(R.string.hide_floating_ball_in_recordings_desc)
                )
                Spacer(modifier = Modifier.height(10.dp))
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

    if (showAutoHeal) {
        AutoHealDialog(
            onDismiss = {
                showAutoHeal = false
                accessibilityEnabled = VolumeKeyStopHelper.isReady(context)
            },
            onOpenAccessibilitySettings = {
                VolumeKeyStopHelper.openAccessibilitySettings(context)
            }
        )
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

/**
 * 「无障碍设置」入口行。做成可点击的整行而不是 Checkbox，
 * 避免和上面的状态展示混淆；右侧箭头表示会打开配置弹窗。
 */
@Composable
private fun AccessibilitySettingsPref(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.auto_heal_title),
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 授权状态展示行：只有标题和状态两行文字，不做成开关—— * 能不能用由系统里的实际授权决定，这里只负责如实显示。
 * ok 为 null 表示正在检测，用中性色显示。
 * 状态文字固定预留两行高度，避免 ✔/✖ 文案长度不同导致下方元素跳动。
 */
@Composable
private fun PermissionStatusRow(title: String, status: String, ok: Boolean?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = status,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                modifier = Modifier.heightIn(min = 30.dp),
                color = when (ok) {
                    false -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
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

/**
 * 话筒 / 内部音频的混音配比，两者互补，合计恒为 100%。
 * 仅在“话筒 + 内部音频”来源下可用。
 */
@Composable
private fun MixRatioPref(
    micPercent: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Text(
        text = stringResource(R.string.audio_mix_ratio),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.audio_mix_ratio_value, micPercent, 100 - micPercent),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        // 滑块从左到右依次是“话筒”到“内部音频”，所以位置值用内部音频占比
        value = (100 - micPercent).toFloat(),
        onValueChange = { onValueChange(100 - it.roundToInt()) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..100f,
        // 以 5% 为一档，避免拖动时出现无意义的精确值
        steps = 19,
        enabled = enabled
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = stringResource(R.string.microphone),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = stringResource(R.string.internal_audio_playback),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (!enabled) {
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.audio_mix_ratio_unavailable),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 音量键长按时长，以 100 毫秒为一档，范围 300–2000 毫秒。
 */
@Composable
private fun HoldDurationPref(
    holdMs: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Text(
        text = stringResource(R.string.volume_key_stop_hold),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.volume_key_stop_hold_value, holdMs),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = holdMs.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 300f..2000f,
        steps = 16
    )
}

/**
 * 摇一摇 / 翻转的触发灵敏度，1（最不容易触发）到 5（最容易触发）。
 */
@Composable
private fun MotionSensitivityPref(
    sensitivity: Int,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Text(
        text = stringResource(R.string.motion_stop_sensitivity),
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = stringResource(R.string.motion_stop_sensitivity_value, sensitivity),
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Slider(
        value = sensitivity.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        onValueChangeFinished = onValueChangeFinished,
        valueRange = 1f..5f,
        // 只保留整档，避免拖出 3.4 这种无意义的值
        steps = 3
    )
}

private fun openOverlaySettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
