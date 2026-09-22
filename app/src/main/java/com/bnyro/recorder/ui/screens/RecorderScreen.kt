package com.bnyro.recorder.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.ui.common.ResponsiveRecordScreenLayout
import com.bnyro.recorder.ui.components.RecorderController
import com.bnyro.recorder.ui.components.RecorderPreview
import com.bnyro.recorder.ui.components.RecordingItemList
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.models.RecorderModel

@Composable
fun RecorderView(
    recordScreenMode: Boolean
) {
    val recorderModel: RecorderModel = viewModel(LocalContext.current as ComponentActivity)
    val playerModel: PlayerModel = viewModel(factory = PlayerModel.Factory)

    LaunchedEffect(recorderModel.recorderState) {
        // update the UI when the recorder gets destroyed by the notification
        if (recorderModel.recorderState == RecorderState.IDLE) {
            recorderModel.stopRecording()
            // a recording that just finished should show up in the list
            // right away, not only after the app is restarted
            playerModel.loadFiles()
        }
    }

    val items = if (recordScreenMode) {
        playerModel.screenRecordingItems
    } else {
        playerModel.audioRecordingItems
    }

    // This screen always sits inside HomeScreen's own Scaffold, and the
    // bottom nav bar there is now visible at all times (recording included
    // - see HomeScreen.kt), so it always keeps this screen's content clear
    // of the system nav bar on its own. No extra inset needed here.
    ResponsiveRecordScreenLayout(
        modifier = Modifier.fillMaxSize(),
        PaneOne = {
            // Nothing recorded yet of this type -> show the big
            // start-recording icon like before. Otherwise, show the
            // recordings themselves instead - no need to dig into a
            // separate "recordings" screen to see what you've already
            // got.
            if (items.isEmpty()) {
                RecorderPreview(recordScreenMode)
            } else {
                RecordingItemList(items = items, isVideoList = recordScreenMode)
            }
        },
        PaneTwo = {
            RecorderController(recordScreenMode)
        }
    )
}
