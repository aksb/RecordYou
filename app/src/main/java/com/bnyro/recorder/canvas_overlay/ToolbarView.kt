package com.bnyro.recorder.canvas_overlay

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bnyro.recorder.R

@Composable
fun ToolbarView(
    modifier: Modifier = Modifier,
    hideCanvas: (Boolean) -> Unit,
    canvasViewModel: CanvasViewModel = viewModel()
) {
    var currentDrawMode by remember { mutableStateOf(DrawMode.Eraser) }
    Card(modifier) {
        Row {
            IconButton(
                onClick = {
                    currentDrawMode = if (currentDrawMode == DrawMode.Eraser) {
                        hideCanvas(false)
                        DrawMode.Pen
                    } else {
                        DrawMode.Eraser
                    }
                    canvasViewModel.currentPath.drawMode = currentDrawMode
                }
            ) {
                if (currentDrawMode == DrawMode.Eraser) {
                    Icon(
                        imageVector = Icons.Rounded.Draw,
                        contentDescription = stringResource(R.string.draw_mode)
                    )
                } else {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_eraser_black_24dp),
                        contentDescription = stringResource(R.string.erase_mode)
                    )
                }
            }
            IconButton(onClick = {
                hideCanvas(true)
                canvasViewModel.paths.clear()
            }) {
                Icon(Icons.Rounded.Clear, "Show/Hide Canvas")
            }
        }
    }
}
