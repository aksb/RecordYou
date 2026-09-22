package com.bnyro.recorder.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.bnyro.recorder.R
import com.bnyro.recorder.ui.common.DialogButton

/**
 * Shown once, right before the floating ball is actually turned on, so
 * people know long-pressing it stops an ongoing recording before they run
 * into that by accident. Only shown going from off -> on; turning the ball
 * back off never needs this. Dismissible for good via the checkbox, tracked
 * through Preferences.floatingBallHintDismissedKey.
 */
@Composable
fun FloatingBallHintDialog(
    onDismissRequest: () -> Unit,
    onConfirm: (dontShowAgain: Boolean) -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        text = {
            Column {
                Text(stringResource(R.string.floating_ball_hint_message))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it }
                    )
                    Text(stringResource(R.string.dont_show_again))
                }
            }
        },
        confirmButton = {
            DialogButton(stringResource(R.string.okay)) {
                onConfirm.invoke(dontShowAgain)
            }
        }
    )
}
