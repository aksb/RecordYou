package com.bnyro.recorder.canvas_overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.theme.RecordYouTheme
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderStatusHolder

@SuppressLint("ClickableViewAccessibility")
@RequiresApi(Build.VERSION_CODES.O)
class CanvasOverlay(context: Context) {
    private val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private val displayMetrics = context.resources.displayMetrics
    private var removed = false
    private var firstStateSkipped = false

    // Stops the annotation overlay when the recording ends from anywhere
    // (notification, floating ball, ...), not just from inside the app.
    // 注册时若录屏已在录制中，RecorderStatusHolder 会立刻回调一次当前状态，
    // 这次要跳过，否则刚显示出来就会被当成"已停止"。
    private val stateListener: (RecorderType, RecorderState) -> Unit = { type, state ->
        if (!firstStateSkipped) {
            firstStateSkipped = true
        } else if (type == RecorderType.VIDEO && state == RecorderState.IDLE) {
            remove()
        }
    }
    private val canvasView = ComposeView(context).apply {
        val activity = context as ComponentActivity
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setContent {
            RecordYouTheme() {
                MainCanvas()
            }
        }
    }
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    @OptIn(ExperimentalComposeUiApi::class)
    private val toolbarView = ComposeView(context).apply {
        val activity = context as ComponentActivity
        setViewTreeLifecycleOwner(activity)
        setViewTreeViewModelStoreOwner(activity)
        setViewTreeSavedStateRegistryOwner(activity)
        setContent {
            RecordYouTheme {
                ToolbarView(
                    Modifier.motionEventSpy { event ->
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialX = toolbarViewParams.x
                                initialY = toolbarViewParams.y
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val newX = (
                                    initialX -
                                        (event.rawX - initialTouchX).toInt()
                                    )
                                val newY = (
                                    initialY +
                                        (event.rawY - initialTouchY).toInt()
                                    )
                                toolbarViewParams.x = newX
                                toolbarViewParams.y = newY
                                windowManager.updateViewLayout(this@apply, toolbarViewParams)
                            }

                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                // remember where the user left the toolbar
                                Preferences.edit {
                                    putInt(Preferences.annotationXKey, toolbarViewParams.x)
                                    putInt(Preferences.annotationYKey, toolbarViewParams.y)
                                }
                            }
                        }
                    },
                    hideCanvas = { hide ->
                        if (hide) {
                            hideCanvas()
                        } else {
                            showCanvas()
                        }
                    }
                )
            }
        }
    }

    private val toolbarViewParams =
        getWindowLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = Preferences.prefs.getInt(Preferences.annotationXKey, 0)
            y = Preferences.prefs.getInt(Preferences.annotationYKey, 0)
        }

    fun show() {
        firstStateSkipped = false
        // keep the saved position inside the current screen bounds
        toolbarViewParams.x = toolbarViewParams.x
            .coerceIn(0, displayMetrics.widthPixels)
        toolbarViewParams.y = toolbarViewParams.y
            .coerceIn(0, (displayMetrics.heightPixels - dpToPx(56)).coerceAtLeast(0))
        RecorderStatusHolder.addListener(stateListener)
        hideCanvas()
        try {
            if (canvasView.windowToken == null && canvasView.parent == null) {
                val params = getWindowLayoutParams(WindowManager.LayoutParams.MATCH_PARENT)
                windowManager.addView(canvasView, params)
            }
            if (toolbarView.windowToken == null && toolbarView.parent == null) {
                windowManager.addView(toolbarView, toolbarViewParams)
            }
        } catch (e: Exception) {
            Log.e("Show Overlay", e.toString())
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * displayMetrics.density).toInt()

    private fun getWindowLayoutParams(size: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSPARENT
        )
    }

    fun showCanvas() {
        canvasView.isVisible = true
    }

    fun hideCanvas() {
        canvasView.isInvisible = true
    }

    fun remove() {
        if (removed) return
        removed = true
        RecorderStatusHolder.removeListener(stateListener)
        try {
            windowManager.removeView(canvasView)
            canvasView.invalidate()
            windowManager.removeView(toolbarView)
            toolbarView.invalidate()
        } catch (e: Exception) {
            Log.e("Remove Overlay", e.toString())
        }
    }
}
