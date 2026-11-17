package com.bnyro.recorder.canvas_overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderStatusHolder
import kotlin.math.abs

@SuppressLint("ClickableViewAccessibility")
@RequiresApi(Build.VERSION_CODES.O)
class CanvasOverlay(private val context: Context) {
    private val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private val displayMetrics = context.resources.displayMetrics
    private var removed = false
    private var shown = false
    private var firstStateSkipped = false

    // 选区/区域遮罩是全屏悬浮窗，会盖住注释层，遮罩出现时重新入栈
    private val overlayRaiser: (Boolean) -> Unit = { raiseCanvas -> raiseViews(raiseCanvas) }

    // 选区阶段状态一直是 IDLE，不会触发状态监听，取消时要靠协调器显式收尾
    private val cancelListener: () -> Unit = { remove() }

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

    private var canvasView = DrawingView(context)
    private var canvasShown = false

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var dragging = false

    /**
     * 工具栏用原生 View（画布同理）：不依赖 Activity 生命周期，
     * 悬浮窗被 removeViewImmediate + addView 重新入栈后点击和拖动都不受影响。
     */
    private inner class DraggableContainer(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = toolbarViewParams.x
                    initialY = toolbarViewParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    dragging = false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging) {
                        dragging = abs(dx) > touchSlop || abs(dy) > touchSlop
                    }
                    if (dragging) {
                        // 不能拖出屏幕，否则工具栏飞到屏幕外就再也点不到了
                        toolbarViewParams.x = (initialX - dx.toInt()).coerceIn(0, maxToolbarX())
                        toolbarViewParams.y = (initialY + dy.toInt()).coerceIn(0, maxToolbarY())
                        runCatching {
                            windowManager.updateViewLayout(this, toolbarViewParams)
                        }.onFailure { Log.e("Move Overlay", it.toString()) }
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        dragging = false
                        // remember where the user left the toolbar
                        Preferences.edit {
                            putInt(Preferences.annotationXKey, toolbarViewParams.x)
                            putInt(Preferences.annotationYKey, toolbarViewParams.y)
                        }
                        return true
                    }
                }
            }
            return super.dispatchTouchEvent(event)
        }
    }

    private var currentDrawMode = DrawMode.Eraser

    private fun setDrawMode(mode: DrawMode) {
        currentDrawMode = mode
        canvasView.drawMode = mode
        drawModeButton.setImageResource(
            if (mode == DrawMode.Eraser) R.drawable.ic_draw_pen
            else R.drawable.ic_eraser_black_24dp
        )
    }

    /**
     * 工具栏用原生 View：悬浮窗重新入栈后 Compose 的指针输入会失效
     * （按钮点不动），原生 View 的点击和拖动都不受影响。
     */
    private val drawModeButton = ImageButton(context).apply {
        val size = dp(44)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setImageResource(R.drawable.ic_draw_pen)
        contentDescription = context.getString(R.string.draw_mode)
        setOnClickListener {
            if (currentDrawMode == DrawMode.Eraser) {
                setDrawMode(DrawMode.Pen)
                showCanvas()
            } else {
                setDrawMode(DrawMode.Eraser)
            }
        }
    }

    private val clearButton = ImageButton(context).apply {
        val size = dp(44)
        layoutParams = LinearLayout.LayoutParams(size, size)
        setBackgroundColor(Color.TRANSPARENT)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dp(10), dp(10), dp(10), dp(10))
        imageTintList = ColorStateList.valueOf(Color.WHITE)
        setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
        contentDescription = context.getString(R.string.annotation_clear)
        setOnClickListener {
            canvasView.clear()
            hideCanvas()
            // 清除后回到初始状态：否则图标还是"橡皮擦"而画布已经隐藏，
            // 用户要连点两次才能继续画
            if (currentDrawMode == DrawMode.Pen) setDrawMode(DrawMode.Eraser)
        }
    }

    private val toolbarView = DraggableContainer(context).apply {
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(4), dp(4), dp(4))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(Color.argb(210, 38, 38, 38))
                }
                addView(drawModeButton)
                addView(clearButton)
            }
        )
    }

    /**
     * 画布是全屏窗口，隐藏后仍然会拦截触摸，导致工具栏和选区手势被吞掉，
     * 所以隐藏时还要加上 FLAG_NOT_TOUCHABLE。
     */
    private val canvasViewParams =
        getWindowLayoutParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

    private val toolbarViewParams =
        getWindowLayoutParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = Preferences.prefs.getInt(Preferences.annotationXKey, 0)
            y = Preferences.prefs.getInt(Preferences.annotationYKey, 0)
        }

    fun show() {
        // 服务可能重复绑定，重复 show 会重复注册状态监听，最后只被清掉一次
        if (shown) return
        shown = true
        firstStateSkipped = false
        // keep the saved position inside the current screen bounds
        toolbarViewParams.x = toolbarViewParams.x
            .coerceIn(0, displayMetrics.widthPixels)
        toolbarViewParams.y = toolbarViewParams.y
            .coerceIn(0, (displayMetrics.heightPixels - dpToPx(56)).coerceAtLeast(0))
        RecorderStatusHolder.addListener(stateListener)
        OverlayCoordinator.registerCancelListener(cancelListener)
        hideCanvas()
        OverlayCoordinator.register(priority = 0, overlayRaiser)
        try {
            if (canvasView.parent == null) {
                windowManager.addView(canvasView, canvasViewParams)
            }
            if (toolbarView.parent == null) {
                windowManager.addView(toolbarView, toolbarViewParams)
            }
            // 尺寸要等布局完成后才知道，这时再把记住的位置收回屏幕内
            toolbarView.post { clampToolbarToScreen() }
            // 注释窗口是后添加的，会压在悬浮球之上（两者重叠时球就点不到了），
            // 把球重新入栈，保证任何时候都能用球停止录制。
            OverlayCoordinator.raiseAll(includeCanvas = false)
        } catch (e: Exception) {
            Log.e("Show Overlay", e.toString())
        }
    }

    /** 工具栏窗口用 TOP|END 定位，x 是距右边缘的偏移，所以上限要扣掉自身宽度 */
    private fun maxToolbarX(): Int = (displayMetrics.widthPixels - toolbarView.width).coerceAtLeast(0)

    private fun maxToolbarY(): Int =
        (displayMetrics.heightPixels - toolbarView.height).coerceAtLeast(0)

    private fun clampToolbarToScreen() {
        if (toolbarView.parent == null || toolbarView.width <= 0) return
        val x = toolbarViewParams.x.coerceIn(0, maxToolbarX())
        val y = toolbarViewParams.y.coerceIn(0, maxToolbarY())
        if (x == toolbarViewParams.x && y == toolbarViewParams.y) return
        toolbarViewParams.x = x
        toolbarViewParams.y = y
        runCatching { windowManager.updateViewLayout(toolbarView, toolbarViewParams) }
            .onFailure { Log.e("Move Overlay", it.toString()) }
    }

    /**
     * 重新添加悬浮窗：先画布再工具栏，保证工具栏在画布之上；
     * 球会在这之后再入栈，从而盖在整个遮罩上面。
     * 选区阶段传入 false，不重加全屏画布，避免挡住选区手势。
     */
    private fun raiseViews(raiseCanvas: Boolean) {
        if (raiseCanvas) {
            runCatching {
                if (canvasView.parent != null) {
                    windowManager.removeViewImmediate(canvasView)
                    windowManager.addView(canvasView, canvasViewParams)
                }
            }.onFailure { Log.e("Raise Overlay", it.toString()) }
        }
        runCatching {
            if (toolbarView.parent != null) {
                windowManager.removeViewImmediate(toolbarView)
                windowManager.addView(toolbarView, toolbarViewParams)
            }
        }.onFailure { Log.e("Raise Overlay", it.toString()) }
    }

    private fun dpToPx(dp: Int): Int = (dp * displayMetrics.density).toInt()

    private fun dp(value: Int): Int = dpToPx(value)

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
        canvasShown = true
        canvasView.isVisible = true
        setCanvasTouchable(false)
        // 画布是后添加的全屏窗口，可触摸之后会盖住先添加的悬浮球，
        // 点球就停不了录制了。这里把球重新入栈到画布之上。
        canvasView.post { OverlayCoordinator.raiseAll(includeCanvas = false) }
    }

    fun hideCanvas() {
        canvasShown = false
        canvasView.isInvisible = true
        setCanvasTouchable(true)
    }

    private fun setCanvasTouchable(notTouchable: Boolean) {
        if (canvasView.parent == null) return
        val current = canvasViewParams.flags
        val wanted = if (notTouchable) {
            current or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            current and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        if (wanted == current) return
        canvasViewParams.flags = wanted
        runCatching { windowManager.updateViewLayout(canvasView, canvasViewParams) }
            .onFailure { Log.e("Update Overlay", it.toString()) }
    }

    fun remove() {
        if (removed) return
        removed = true
        shown = false
        RecorderStatusHolder.removeListener(stateListener)
        OverlayCoordinator.unregisterCancelListener(cancelListener)
        OverlayCoordinator.unregister(overlayRaiser)
        // 两个窗口分开删，避免一个失败导致另一个残留
        runCatching {
            windowManager.removeViewImmediate(canvasView)
            canvasView.invalidate()
        }.onFailure { Log.e("Remove Overlay", it.toString()) }
        runCatching {
            windowManager.removeViewImmediate(toolbarView)
            toolbarView.invalidate()
        }.onFailure { Log.e("Remove Overlay", it.toString()) }
    }
}
