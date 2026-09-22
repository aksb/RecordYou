package com.bnyro.recorder.ui.screens

import android.view.SoundEffectConstants
import androidx.activity.ComponentActivity
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.SortOrder
import com.bnyro.recorder.ui.Destination
import com.bnyro.recorder.ui.common.ClickableIcon
import com.bnyro.recorder.ui.dialogs.ConfirmationDialog
import com.bnyro.recorder.ui.dialogs.FloatingBallHintDialog
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.util.FloatingBallHelper
import com.bnyro.recorder.util.Preferences
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    initialRecorder: RecorderType,
    onNavigate: (Destination) -> Unit,
    recorderModel: RecorderModel = viewModel(LocalContext.current as ComponentActivity),
    playerModel: PlayerModel = viewModel(factory = PlayerModel.Factory)
) {
    val pagerState =
        rememberPagerState(initialPage = if (initialRecorder == RecorderType.VIDEO) 1 else 0) { 2 }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var floatingBallEnabled by remember {
        mutableStateOf(FloatingBallHelper.isEnabled(context))
    }
    // Turning the ball on when the overlay permission is missing sends the
    // person to a system settings screen rather than updating anything here
    // directly - re-check the real state whenever this screen comes back
    // into the foreground, so the button reflects what they actually did
    // over there instead of going stale.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                floatingBallEnabled = FloatingBallHelper.isEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Whichever type's page you're currently on - the sort/select-all/delete
    // actions in the top bar all act on this list, never both at once.
    val currentItems = if (pagerState.currentPage == 1) {
        playerModel.screenRecordingItems
    } else {
        playerModel.audioRecordingItems
    }

    // Selections shouldn't silently carry over when switching from the
    // audio page to the video page (or back) - the checkbox would otherwise
    // keep showing "some selected" for items you can no longer see.
    LaunchedEffect(pagerState.currentPage) {
        playerModel.selectedFiles = emptyList()
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showFloatingBallHintDialog by remember { mutableStateOf(false) }

    // Turning the ball on for real - shared by the "confirm" button of the
    // hint dialog and by the direct-enable path when the hint has already
    // been dismissed for good.
    fun enableFloatingBall() {
        if (FloatingBallHelper.setEnabled(context, true)) {
            floatingBallEnabled = true
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        Column {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                TextButton(
                    onClick = {
                        view.playSoundEffect(SoundEffectConstants.CLICK)
                        if (floatingBallEnabled) {
                            // Turning the ball off never needs the hint.
                            if (FloatingBallHelper.setEnabled(context, false)) {
                                floatingBallEnabled = false
                            }
                        } else if (Preferences.prefs.getBoolean(
                                Preferences.floatingBallHintDismissedKey,
                                false
                            )
                        ) {
                            enableFloatingBall()
                        } else {
                            showFloatingBallHintDialog = true
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (floatingBallEnabled) {
                            Icons.Default.RadioButtonChecked
                        } else {
                            Icons.Default.RadioButtonUnchecked
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(
                            if (floatingBallEnabled) {
                                R.string.close_floating_ball
                            } else {
                                R.string.shortcut_enable_floating_ball
                            }
                        )
                    )
                }
                ClickableIcon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings)
                ) {
                    onNavigate(Destination.Settings)
                }
            })
            // Kept off the title row on purpose - cramming these in with the
            // floating-ball toggle and settings icon pushed the title onto a
            // second line and made the actions wrap unpredictably.
            if (currentItems.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        var showSortMenu by remember { mutableStateOf(false) }
                        ClickableIcon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = stringResource(R.string.sort)
                        ) {
                            showSortMenu = true
                        }

                        val sortOptions = listOf(
                            SortOrder.MODIFIED to R.string.modified,
                            SortOrder.MODIFIED_REV to R.string.modified_rev,
                            SortOrder.ALPHABETIC to R.string.alphabetic,
                            SortOrder.ALPHABETIC_REV to R.string.alphabetic_rev,
                            SortOrder.SIZE to R.string.size,
                            SortOrder.SIZE_REV to R.string.size_rev
                        )
                        DropdownMenu(showSortMenu, { showSortMenu = false }) {
                            sortOptions.forEach { sortOrder ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(sortOrder.second)) },
                                    onClick = {
                                        playerModel.sortItems(sortOrder.first)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    if (playerModel.selectedFiles.isNotEmpty()) {
                        val selectedAll = playerModel.selectedFiles.size == currentItems.size
                        Checkbox(
                            modifier = Modifier.align(Alignment.CenterVertically),
                            checked = selectedAll,
                            onCheckedChange = {
                                playerModel.selectedFiles = if (selectedAll) {
                                    listOf()
                                } else {
                                    currentItems
                                }
                            }
                        )
                    }
                    ClickableIcon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.delete_all)
                    ) {
                        showDeleteDialog = true
                    }
                }
            }
        }
    }, bottomBar = {
        // Always visible now, even mid-recording - that's the whole point:
        // glancing at which tab is highlighted is how you can tell whether
        // you're recording audio or screen without having to wait for the
        // recording to finish. Tapping it mid-recording is disabled below
        // rather than removing the bar itself, since recorderState is one
        // shared value for both modes - switching tabs while it's not IDLE
        // wouldn't actually start a separate recording for the other mode,
        // it would just show the same in-progress recording under the
        // wrong tab.
        val isIdle = recorderModel.recorderState == RecorderState.IDLE
        NavigationBar {
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = stringResource(
                            id = R.string.record_sound
                        )
                    )
                },
                label = { Text(stringResource(R.string.record_sound)) },
                selected = (pagerState.currentPage == 0),
                enabled = isIdle,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                }
            )
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = stringResource(
                            id = R.string.record_screen
                        )
                    )
                },
                label = { Text(stringResource(R.string.record_screen)) },
                selected = (pagerState.currentPage == 1),
                enabled = isIdle,
                onClick = {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                }
            )
        }
    }) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            HorizontalPager(
                state = pagerState,
                // Same reasoning as the nav bar above: swiping between tabs
                // mid-recording would land on the other tab while the
                // shared recorder state still shows the recording in
                // progress, not a fresh, empty screen for that mode.
                userScrollEnabled = recorderModel.recorderState == RecorderState.IDLE,
                modifier = Modifier.fillMaxSize()
            ) { index ->
                RecorderView(recordScreenMode = (index == 1))
            }
        }
    }

    if (showDeleteDialog) {
        ConfirmationDialog(
            title = if (playerModel.selectedFiles.isEmpty()) R.string.delete_all else R.string.delete,
            onDismissRequest = { showDeleteDialog = false }
        ) {
            playerModel.deleteFiles(fallbackItems = currentItems)
        }
    }

    if (showFloatingBallHintDialog) {
        FloatingBallHintDialog(
            onDismissRequest = { showFloatingBallHintDialog = false }
        ) { dontShowAgain ->
            if (dontShowAgain) {
                Preferences.edit { putBoolean(Preferences.floatingBallHintDismissedKey, true) }
            }
            showFloatingBallHintDialog = false
            enableFloatingBall()
        }
    }
}
