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
import com.bnyro.recorder.enums.AudioDeviceSource
import com.bnyro.recorder.enums.AudioSource
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.enums.VideoFormat
import com.bnyro.recorder.obj.AudioFormat
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val themeModel: ThemeModel = viewModel(LocalContext.current as ComponentActivity)
    var audioFormat by remember {
        mutableStateOf(AudioFormat.getCurrent())
    }
    var audioChannels by remember {
        mutableStateOf(
            AudioChannels.fromInt(
                Preferences.prefs.getInt(Preferences.audioChannelsKey, AudioChannels.MONO.value)
            )
        )
    }
    var audioDeviceSource by remember {
        mutableStateOf(
            AudioDeviceSource.fromInt(
                Preferences.prefs.getInt(
                    Preferences.audioDeviceSourceKey,
                    AudioDeviceSource.DEFAULT.value
                )
            )
        )
    }
    var screenAudioSource by remember {
        mutableStateOf(
            AudioSource.fromInt(Preferences.prefs.getInt(Preferences.audioSourceKey, 0))
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
            Text(
                text = stringResource(R.string.directory),
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp
            )
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
            ChipSelector(
                title = stringResource(R.string.audio_format),
                entries = AudioFormat.formats.map { it.name },
                values = AudioFormat.formats.map { it.format },
                selections = listOf(audioFormat.format)
            ) { index, newValue ->
                if (newValue) {
                    audioFormat = AudioFormat.formats[index]
                    Preferences.edit { putString(Preferences.audioFormatKey, audioFormat.name) }
                }
            }
            Row {
                CustomNumInputPref(
                    key = Preferences.audioSampleRateKey,
                    title = stringResource(R.string.sample_rate),
                    defValue = 44_100
                )
                Spacer(modifier = Modifier.width(10.dp))
                CustomNumInputPref(
                    key = Preferences.audioBitrateKey,
                    title = stringResource(R.string.bitrate),
                    defValue = 192_000
                )
            }
            val audioDeviceSourceValues = AudioDeviceSource.values().map { it.value }
            ChipSelector(
                entries = listOf(
                    R.string.default_audio,
                    R.string.microphone,
                    R.string.camcorder,
                    R.string.unprocessed,
                    R.string.internal_audio
                ).map {
                    stringResource(it)
                },
                values = audioDeviceSourceValues,
                selections = listOf(audioDeviceSource.value)
            ) { index, newValue ->
                if (newValue) {
                    audioDeviceSource = AudioDeviceSource.fromInt(
                        audioDeviceSourceValues[index]
                    )
                    Preferences.edit {
                        putInt(Preferences.audioDeviceSourceKey, audioDeviceSourceValues[index])
                    }
                }
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
            val audioValues = AudioSource.values().map { it.value }
            ChipSelector(
                title = stringResource(R.string.screen_recorder),
                entries = listOf(R.string.no_audio, R.string.microphone).map {
                    stringResource(it)
                },
                values = audioValues,
                selections = listOf(screenAudioSource.value)
            ) { index, newValue ->
                if (newValue) {
                    screenAudioSource = AudioSource.fromInt(audioValues[index])
                    Preferences.edit { putInt(Preferences.audioSourceKey, audioValues[index]) }
                }
            }
            ChipSelector(
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
                title = stringResource(R.string.bitrate),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        modifier = Modifier.padding(horizontal = 5.dp),
                        value = customBitrateInput,
                        onValueChange = { customBitrateInput = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.bitrate)) }
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Button(onClick = {
                        customBitrateInput.toIntOrNull()?.takeIf { it > 0 }?.let {
                            videoBitrate = it
                            Preferences.edit { putInt(Preferences.videoBitrateKey, it) }
                        }
                    }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                CheckboxPref(
                    prefKey = Preferences.losslessRecorderKey,
                    title = stringResource(R.string.lossless_audio),
                    summary = stringResource(R.string.lossless_audio_desc)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                CheckboxPref(
                    prefKey = Preferences.showOverlayAnnotationToolKey,
                    title = stringResource(R.string.screen_recorder_annotation),
                    summary = stringResource(R.string.screen_recorder_annotation_desc)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            CheckboxPref(
                prefKey = Preferences.showVisualizerTimestamps,
                title = stringResource(R.string.audio_visualizer_timestamps),
                summary = stringResource(R.string.audio_visualizer_timestamps_description)
            )
            Spacer(modifier = Modifier.height(10.dp))
            NamingPatternPref()
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
