package com.bnyro.recorder.ui.components

import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.models.RecorderModel

@Composable
fun RecorderController(
    recordScreenMode: Boolean
) {
    val recorderModel: RecorderModel = viewModel(LocalContext.current as ComponentActivity)
    val context = LocalContext.current
    val mProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    // 状态唯一来源：无论录制是从应用内还是悬浮球/磁贴启动的，都能正确显示
    val activeType = recorderModel.activeType
    val thisType = if (recordScreenMode) RecorderType.VIDEO else RecorderType.AUDIO
    val isThisTypeActive = activeType == thisType
    val isOtherTypeActive = activeType != null && !isThisTypeActive

    val requestRecording = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        recorderModel.startVideoRecorder(context, result)
    }

    val requestAudioRecording = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        recorderModel.startAudioRecorder(context, result)
    }

    fun requestScreenRecording() {
        if (!recorderModel.hasScreenRecordingPermissions(context)) return
        requestRecording.launch(
            mProjectionManager.createScreenCaptureIntent()
        )
    }

    fun requestAudioWithInternalAudio() {
        if (!recorderModel.hasScreenRecordingPermissions(context)) return
        requestAudioRecording.launch(
            mProjectionManager.createScreenCaptureIntent()
        )
    }

    Column(
        modifier = Modifier.wrapContentSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (isThisTypeActive) {
            recorderModel.recordedTime?.let {
                Text(
                    text = DateUtils.formatElapsedTime(it / 10),
                    style = MaterialTheme.typography.displayLarge
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isSystemInDarkTheme()) {
                        Color(0xA8EE665B)
                    } else {
                        Color(
                            0xffdd6f62
                        )
                    },
                    contentColor = Color.White
                ),
                shape = CircleShape
            ) {
                val buttonDescription = stringResource(
                    if (isThisTypeActive) {
                        R.string.stop
                    } else {
                        R.string.record
                    }
                )
                IconButton(
                    onClick = {
                        when {
                            isThisTypeActive -> recorderModel.stopRecording(context)
                            isOtherTypeActive -> Toast.makeText(
                                context,
                                R.string.floating_ball_other_recording,
                                Toast.LENGTH_SHORT
                            ).show()

                            recordScreenMode -> requestScreenRecording()
                            recorderModel.audioRequiresScreenCapture() -> requestAudioWithInternalAudio()
                            else -> recorderModel.startAudioRecorder(context)
                        }
                    },
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { contentDescription = buttonDescription }
                ) {
                    when {
                        isThisTypeActive -> {
                            Icon(
                                Icons.Default.Stop,
                                modifier = Modifier.size(36.dp),
                                contentDescription = stringResource(R.string.stop)
                            )
                        }

                        else -> {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x9FFFFFFF))
                            )
                            Box(
                                Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x9FFFFFFF))
                            )
                        }
                    }
                }
            }
        }
    }
}
