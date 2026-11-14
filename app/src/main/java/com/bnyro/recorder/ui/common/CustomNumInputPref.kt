package com.bnyro.recorder.ui.common

import android.view.SoundEffectConstants
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bnyro.recorder.R
import com.bnyro.recorder.util.Preferences

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomNumInputPref(
    key: String,
    title: String,
    defValue: Int,
    enabled: Boolean = true,
    valueRange: IntRange? = null,
    standardValues: List<Int> = emptyList(),
    quickValues: List<Int> = emptyList(),
    hintText: String? = null
) {
    val pref = Preferences.prefs.getInt(key, -1).takeIf { it != -1 }
    val view = LocalView.current
    var showDialog by remember {
        mutableStateOf(false)
    }
    var isAutoEnabled by remember {
        mutableStateOf(pref == null)
    }
    var input by remember {
        mutableStateOf((pref ?: defValue).toString())
    }

    Button(onClick = {
        view.playSoundEffect(SoundEffectConstants.CLICK)
        showDialog = true
    }, enabled = enabled) {
        Text(text = title)
    }

    if (showDialog) {
        val parsedInput = input.toIntOrNull()
        // 超出范围或无法解析时不写入偏好设置
        val outOfRange = !isAutoEnabled &&
            (parsedInput == null || (valueRange != null && parsedInput !in valueRange))
        val nonStandard = !isAutoEnabled && parsedInput != null &&
            standardValues.isNotEmpty() && parsedInput !in standardValues

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = title) },
            confirmButton = {
                DialogButton(stringResource(R.string.okay), enabled = !outOfRange) {
                    Preferences.edit {
                        putInt(key, if (isAutoEnabled) -1 else parsedInput ?: -1)
                    }
                    showDialog = false
                }
            },
            dismissButton = {
                DialogButton(stringResource(R.string.cancel)) {
                    showDialog = false
                }
            },
            text = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isAutoEnabled, onCheckedChange = { isAutoEnabled = it })
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = stringResource(R.string.auto))
                    }
                    Spacer(modifier = Modifier.height(5.dp))
                    OutlinedTextField(
                        modifier = Modifier.padding(horizontal = 5.dp),
                        value = input,
                        onValueChange = {
                            // 只接受数字，避免非法字符
                            if (it.all(Char::isDigit)) input = it
                        },
                        enabled = !isAutoEnabled,
                        readOnly = isAutoEnabled,
                        isError = outOfRange,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(title) },
                        supportingText = {
                            when {
                                valueRange != null && outOfRange -> {
                                    Text(
                                        stringResource(
                                            R.string.value_out_of_range,
                                            valueRange.first,
                                            valueRange.last
                                        )
                                    )
                                }

                                nonStandard -> {
                                    Text(stringResource(R.string.sample_rate_nonstandard))
                                }

                                hintText != null -> {
                                    Text(hintText)
                                }

                                valueRange != null -> {
                                    Text(
                                        stringResource(
                                            R.string.value_range_hint,
                                            valueRange.first,
                                            valueRange.last
                                        )
                                    )
                                }
                            }
                        }
                    )
                    if (quickValues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(5.dp))
                        ChipSelector(
                            entries = quickValues.map { it.toString() },
                            values = quickValues,
                            selections = if (isAutoEnabled) {
                                emptyList()
                            } else {
                                listOfNotNull(parsedInput)
                            },
                            enabled = !isAutoEnabled
                        ) { index, selected ->
                            if (selected) input = quickValues[index].toString()
                        }
                    }
                }
            }
        )
    }
}
