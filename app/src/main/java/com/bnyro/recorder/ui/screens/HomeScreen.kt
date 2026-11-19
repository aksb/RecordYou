package com.bnyro.recorder.ui.screens

import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.SortOrder
import com.bnyro.recorder.ui.Destination
import com.bnyro.recorder.ui.common.ClickableIcon
import com.bnyro.recorder.ui.dialogs.AutoHealDialog
import com.bnyro.recorder.ui.dialogs.ConfirmationDialog
import com.bnyro.recorder.ui.models.PlayerModel
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.util.FloatingBallHelper
import com.bnyro.recorder.util.LsposedModuleDetector
import com.bnyro.recorder.util.VolumeKeyStopHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 从悬浮球/磁贴/快捷方式开始的录制，打开应用时自动切到对应页面，
    // 避免“另一页正在录、这一页看起来却像空闲”的困惑
    LaunchedEffect(recorderModel.activeType) {
        val active = recorderModel.activeType ?: return@LaunchedEffect
        val target = if (active == RecorderType.VIDEO) 1 else 0
        if (pagerState.currentPage != target) pagerState.animateScrollToPage(target)
    }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var audioBallEnabled by remember {
        mutableStateOf(FloatingBallHelper.isEnabled(context, RecorderType.AUDIO))
    }
    var videoBallEnabled by remember {
        mutableStateOf(FloatingBallHelper.isEnabled(context, RecorderType.VIDEO))
    }

    // 音量键长按停止依赖无障碍服务。MIUI 等系统清理最近任务时会把服务从
    // 已启用列表里摘掉，而应用内开关还是打开状态，长按音量键就静默失效了。
    // 启动/回到前台时检查一次：能自愈就静默写回，不行就常驻提醒并告知
    // 还有哪些方式可以结束录制，避免录了半天才发现长按没反应。
    var volumeKeyBroken by remember { mutableStateOf(false) }
    var volumeKeyBackupHint by remember { mutableStateOf("") }
    var showVolumeKeyDialog by remember { mutableStateOf(false) }
    var showAutoHealDialog by remember { mutableStateOf(false) }
    var volumeKeySelfHealTried by remember { mutableStateOf(false) }
    var volumeKeySelfHealing by remember { mutableStateOf(false) }

    // 关掉自动恢复卡片后重新读一次状态，但不自动重试自愈：
    // 用户可能刚去授权，也可能就是不想修复，不该再弹 root 授权框
    fun recheckVolumeKeyState() {
        volumeKeyBroken = VolumeKeyStopHelper.needsAttention(context)
        if (volumeKeyBroken) {
            volumeKeyBackupHint = VolumeKeyStopHelper.backupStopHint(context)
        }
    }

    fun refreshVolumeKeyState() {
        if (!VolumeKeyStopHelper.isWanted() || VolumeKeyStopHelper.isReady(context)) {
            volumeKeyBroken = false
            return
        }
        // 无障碍不可用：先异步问 LSPosed 模块是否在兜底（30 秒内不重复探测），
        // 探测期间 needsAttention 返回 false，不会先闪一条警告再消失
        LsposedModuleDetector.detect(context)
        if (!VolumeKeyStopHelper.needsAttention(context)) {
            volumeKeyBroken = false
            return
        }
        volumeKeyBackupHint = VolumeKeyStopHelper.backupStopHint(context)
        // 自愈正在后台进行时先不下结论，等结果回来再显示提示
        if (volumeKeySelfHealing) return
        if (volumeKeySelfHealTried) {
            volumeKeyBroken = true
            return
        }
        volumeKeySelfHealTried = true
        volumeKeySelfHealing = true
        scope.launch {
            val healed = withContext(Dispatchers.IO) {
                VolumeKeyStopHelper.selfHeal(context) ||
                    VolumeKeyStopHelper.selfHealWithRoot(context)
            }
            volumeKeySelfHealing = false
            if (healed && !VolumeKeyStopHelper.needsAttention(context)) {
                volumeKeyBroken = false
                Toast.makeText(
                    context,
                    R.string.volume_key_stop_healed,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                volumeKeyBroken = true
                showVolumeKeyDialog = true
            }
        }
    }

    // Turning a ball on when the overlay permission is missing sends the
    // person to a system settings screen rather than updating anything here
    // directly - re-check the real state whenever this screen comes back
    // into the foreground, so the buttons reflect what they actually did
    // over there instead of going stale.
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                audioBallEnabled = FloatingBallHelper.isEnabled(context, RecorderType.AUDIO)
                videoBallEnabled = FloatingBallHelper.isEnabled(context, RecorderType.VIDEO)
                refreshVolumeKeyState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 冷启动时 Activity 可能已经处于 RESUMED，ON_RESUME 不会再触发一次
    LaunchedEffect(Unit) { refreshVolumeKeyState() }

    // LSPosed 探测结果落地后重新评估一次警告：兜底生效就不再提示
    LaunchedEffect(LsposedModuleDetector.status) { refreshVolumeKeyState() }

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

    // 开启一个球会自动关闭另一个（正在录制时则直接拒绝），所以这里统一
    // 按真实状态回读两个开关，而不是只翻转被点的那个。
    fun toggleFloatingBall(type: RecorderType) {
        val enabled = if (type == RecorderType.AUDIO) audioBallEnabled else videoBallEnabled
        FloatingBallHelper.setEnabled(context, type, !enabled)
        audioBallEnabled = FloatingBallHelper.isEnabled(context, RecorderType.AUDIO)
        videoBallEnabled = FloatingBallHelper.isEnabled(context, RecorderType.VIDEO)
    }

    Scaffold(modifier = Modifier.fillMaxSize(), topBar = {
        Column {
            TopAppBar(title = { Text(stringResource(R.string.app_name)) }, actions = {
                ClickableIcon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings)
                ) {
                    onNavigate(Destination.Settings)
                }
            })
            // 两个悬浮球各自独立开关，放在标题下方单独一行，
            // 避免和标题、设置图标挤在一起换行。
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FloatingBallToggle(
                    enabled = audioBallEnabled,
                    label = R.string.tile_audio_ball
                ) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    toggleFloatingBall(RecorderType.AUDIO)
                }
                FloatingBallToggle(
                    enabled = videoBallEnabled,
                    label = R.string.tile_video_ball
                ) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    toggleFloatingBall(RecorderType.VIDEO)
                }
            }
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
            if (volumeKeyBroken) {
                VolumeKeyWarningCard(
                    backupHint = volumeKeyBackupHint,
                    onShowFix = { showAutoHealDialog = true }
                )
            }
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

    // 只在空闲时弹，正在录制时用常驻警告条就够了，不要打断录制
    if (showVolumeKeyDialog && recorderModel.recorderState == RecorderState.IDLE) {
        AlertDialog(
            onDismissRequest = { showVolumeKeyDialog = false },
            title = { Text(stringResource(R.string.volume_key_stop_broken_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.volume_key_stop_broken_desc))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = volumeKeyBackupHint,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVolumeKeyDialog = false
                        showAutoHealDialog = true
                    }
                ) {
                    Text(stringResource(R.string.auto_heal_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVolumeKeyDialog = false }) {
                    Text(stringResource(R.string.volume_key_stop_dialog_ignore))
                }
            }
        )
    }

    if (showAutoHealDialog) {
        AutoHealDialog(
            onDismiss = {
                showAutoHealDialog = false
                recheckVolumeKeyState()
            },
            onOpenAccessibilitySettings = {
                VolumeKeyStopHelper.openAccessibilitySettings(context)
            }
        )
    }
}

@Composable
private fun VolumeKeyWarningCard(
    backupHint: String,
    onShowFix: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clickable(onClick = onShowFix),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.volume_key_stop_broken_title),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.volume_key_stop_broken_desc),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = backupHint,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onShowFix) {
                    Text(stringResource(R.string.volume_key_stop_view_fix))
                }
            }
        }
    }
}

@Composable
private fun FloatingBallToggle(
    enabled: Boolean,
    @StringRes label: Int,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick) {
        Icon(
            imageVector = if (enabled) {
                Icons.Default.RadioButtonChecked
            } else {
                Icons.Default.RadioButtonUnchecked
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = stringResource(label))
    }
}
