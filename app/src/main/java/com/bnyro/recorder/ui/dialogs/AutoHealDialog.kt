package com.bnyro.recorder.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bnyro.recorder.R
import com.bnyro.recorder.util.VolumeKeyStopHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class RootState { UNKNOWN, BUSY, GRANTED, WRITTEN, UNAVAILABLE }

/**
 * 无障碍自动恢复的配置卡片。
 *
 * 设置页入口、首页警告条和警告弹窗都打开这一个弹窗，保证三处信息一致。
 * adb 教程直接内联展开，不再叠加第二层弹窗。
 */
@Composable
fun AutoHealDialog(
    onDismiss: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var adbGranted by remember {
        mutableStateOf(VolumeKeyStopHelper.canWriteSecureSettings(context))
    }
    var tutorialExpanded by remember { mutableStateOf(false) }
    var rootState by remember { mutableStateOf(RootState.UNKNOWN) }
    val command = remember { VolumeKeyStopHelper.secureSettingsCommand(context) }

    // 从系统设置或其他界面回来时刷新 adb 授权状态
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                adbGranted = VolumeKeyStopHelper.canWriteSecureSettings(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun detectRoot() {
        if (rootState == RootState.BUSY) return
        rootState = RootState.BUSY
        scope.launch {
            val healed = withContext(Dispatchers.IO) {
                VolumeKeyStopHelper.grantWithRoot(context)
            }
            adbGranted = VolumeKeyStopHelper.canWriteSecureSettings(context)
            rootState = when {
                adbGranted -> RootState.GRANTED
                healed -> RootState.WRITTEN
                else -> RootState.UNAVAILABLE
            }
        }
    }

    val rootLabel = stringResource(
        when (rootState) {
            RootState.UNKNOWN -> R.string.root_status_unknown
            RootState.BUSY -> R.string.root_status_busy
            RootState.GRANTED -> R.string.root_status_granted
            RootState.WRITTEN -> R.string.root_status_written
            RootState.UNAVAILABLE -> R.string.root_status_unavailable
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_heal_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(R.string.auto_heal_desc),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = stringResource(
                        R.string.auto_heal_adb_status,
                        stringResource(
                            if (adbGranted) {
                                R.string.adb_grant_granted
                            } else {
                                R.string.adb_grant_missing
                            }
                        )
                    ),
                    fontSize = 13.sp
                )
                TextButton(onClick = { tutorialExpanded = !tutorialExpanded }) {
                    Text(
                        stringResource(
                            if (tutorialExpanded) {
                                R.string.auto_heal_hide_adb_tutorial
                            } else {
                                R.string.auto_heal_view_adb_tutorial
                            }
                        )
                    )
                }
                if (tutorialExpanded) {
                    AdbTutorial(command = command) { copyCommand(context, command) }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = stringResource(R.string.auto_heal_root_status, rootLabel),
                    fontSize = 13.sp
                )
                TextButton(
                    onClick = { detectRoot() },
                    enabled = rootState != RootState.BUSY
                ) {
                    Text(stringResource(R.string.auto_heal_root_action))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onOpenAccessibilitySettings()
                }
            ) {
                Text(stringResource(R.string.open_accessibility_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close))
            }
        }
    )
}

@Composable
private fun AdbTutorial(command: String, onCopy: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            SelectionContainer {
                Text(
                    text = command,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.copy_command))
            }
            Spacer(modifier = Modifier.height(4.dp))
            listOf(
                R.string.adb_tutorial_terminal,
                R.string.adb_tutorial_once,
                R.string.adb_tutorial_reinstall,
                R.string.adb_tutorial_restart,
                R.string.adb_tutorial_optional,
                R.string.adb_tutorial_local
            ).forEach { line ->
                Text(
                    text = "· ${stringResource(line)}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun copyCommand(context: Context, command: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return
    manager.setPrimaryClip(ClipData.newPlainText("RecordYou", command))
    // Android 13 起系统自己会弹剪贴板浮层，不再重复 Toast
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.command_copied, Toast.LENGTH_SHORT).show()
    }
}
