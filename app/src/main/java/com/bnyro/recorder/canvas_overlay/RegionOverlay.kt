package com.bnyro.recorder.canvas_overlay

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import com.bnyro.recorder.R

/**
 * 录屏开始前的选区界面：全屏半透明，拖动选择一个矩形区域，
 * 确认后才真正开始采集。
 *
 * 手势：四角或四条边拖动缩放，按住选区内部拖动整个选区，
 * 在选区外拖动则重新框选。上次确认过的选区会作为初始选区恢复。
 */
@RequiresApi(Build.VERSION_CODES.O)
class RegionSelectorOverlay(
    private val context: Context,
    private val initialRegion: Rect?,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit
) {
    private val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var selectionView: SelectionView? = null
    private var removed = false
    // 按钮条当前位置（相对全屏容器）。直接读 bar.left 会拿到上一帧的旧值，
    // 同一帧里连续来的 MOVE 事件就会互相覆盖，快速拖动时表现为跟不上手指
    private var barLeft = 0f
    private var barTop = 0f
    private var barPosReady = false
    // 状态栏 / 导航栏占用的安全区，按钮条拖动时不能进入这些区域（否则会被系统栏盖住）
    private val safeInsets = Rect()

    fun show(): Boolean {
        remove()
        removed = false
        return runCatching {
            val container = FrameLayout(context)
            val view = SelectionView(context, initialRegion, screenWidth, screenHeight)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // 按钮条整体可以长按拖动，和选区的手势互不影响
            lateinit var bar: DraggableBar
            bar = DraggableBar(context) { dx, dy -> moveBar(bar, dx, dy) }

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            // 四个按钮统一最小宽度并强制单行，避免文字被挤成两行
            fun barButton(): Button = Button(context).apply {
                minWidth = dp(72)
                minimumWidth = dp(72)
                setSingleLine()
                setPadding(dp(8), 0, dp(8), 0)
                textSize = 13f
            }
            val cancelButton = barButton().apply {
                text = context.getString(R.string.cancel)
                setOnClickListener {
                    remove()
                    onCancel()
                }
            }
            val defaultButton = barButton().apply {
                text = context.getString(R.string.region_recording_default)
                setOnClickListener {
                    // 底部预留按钮条自身的高度，默认选区不会挡住它
                    view.selectDefault(bar.height + dp(72))
                }
            }
            val fullScreenButton = barButton().apply {
                text = context.getString(R.string.region_recording_full_screen)
                setOnClickListener { view.selectFullScreen() }
            }
            // “录制”用悬浮球同款蓝色，和上面的圆形球保持一致的视觉语言
            val confirmButton = barButton().apply {
                text = context.getString(R.string.region_recording_start)
                backgroundTintList = ColorStateList.valueOf(Color.parseColor("#1565C0"))
                setTextColor(Color.WHITE)
                setOnClickListener {
                    val rect = view.selectedRect()
                    if (rect.width() < MIN_REGION_PX || rect.height() < MIN_REGION_PX) return@setOnClickListener
                    remove()
                    onConfirm(rect)
                }
            }
            row.addView(cancelButton)
            row.addView(defaultButton)
            row.addView(fullScreenButton)
            row.addView(confirmButton)
            bar.addView(row)
            bar.addView(
                TextView(context).apply {
                    text = context.getString(R.string.region_recording_bar_drag_hint)
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    alpha = 0.75f
                    setPadding(0, dp(2), 0, 0)
                }
            )
            container.addView(
                bar,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(72)
                }
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(container, params)
            root = container
            selectionView = view
            barPosReady = false
            readSafeInsets()
            // 悬浮球和注释层重新入栈，显示在遮罩之上
            OverlayCoordinator.raiseAll(includeCanvas = false)
            true
        }.onFailure {
            Log.e(TAG, "Unable to show the region selector", it)
        }.getOrDefault(false)
    }

    fun remove() {
        if (removed) return
        removed = true
        // 立即移除：普通 removeView 是异步的，全屏遮罩撤掉后底下的界面
        // 有时会残留一帧旧内容（模拟器上表现为选区框"幽灵"留在屏幕上）
        runCatching { root?.let { windowManager.removeViewImmediate(it) } }
        root = null
        selectionView = null
    }

    /** 状态栏、导航栏（手势条/dock）占用的区域，按钮条不能被拖进去 */
    private fun readSafeInsets() {
        safeInsets.setEmpty()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowInsets = windowManager.currentWindowMetrics.windowInsets
                val bars = windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                var left = bars.left
                var top = bars.top
                var right = bars.right
                var bottom = bars.bottom
                // 平板/桌面模式下的任务栏属于 systemOverlays，也一并避开
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val overlays = windowInsets
                        .getInsetsIgnoringVisibility(WindowInsets.Type.systemOverlays())
                    left = maxOf(left, overlays.left)
                    top = maxOf(top, overlays.top)
                    right = maxOf(right, overlays.right)
                    bottom = maxOf(bottom, overlays.bottom)
                }
                safeInsets.set(left, top, right, bottom)
            } else {
                val statusBar = systemDimen("status_bar_height")
                val navBar = systemDimen("navigation_bar_height")
                safeInsets.set(0, statusBar, 0, navBar)
            }
        }.onFailure {
            Log.e(TAG, "Unable to read the system bar insets", it)
        }
    }

    private fun systemDimen(name: String): Int {
        val id = context.resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }

    private fun moveBar(bar: View, dx: Float, dy: Float) {
        val container = root ?: return
        val lp = bar.layoutParams as? FrameLayout.LayoutParams ?: return
        if (!barPosReady) {
            // 第一次拖动时布局已完成，用实际位置作为起点
            barLeft = bar.left.toFloat()
            barTop = bar.top.toFloat()
            barPosReady = true
        }
        // 不能进入系统栏区域，否则会被状态栏/导航栏盖住，点不到
        val minLeft = safeInsets.left
        val minTop = safeInsets.top
        val maxLeft = (container.width - bar.width - safeInsets.right).coerceAtLeast(minLeft)
        val maxTop = (container.height - bar.height - safeInsets.bottom).coerceAtLeast(minTop)
        lp.gravity = Gravity.TOP or Gravity.START
        // 切到 TOP|START 后，原来的 bottomMargin 仍会参与测量（child 可用高度
        // 要同时扣掉上下两个 margin），越往下拖可用高度越小，最后整条消失
        lp.bottomMargin = 0
        lp.rightMargin = 0
        barLeft = (barLeft + dx).coerceIn(minLeft.toFloat(), maxLeft.toFloat())
        barTop = (barTop + dy).coerceIn(minTop.toFloat(), maxTop.toFloat())
        lp.leftMargin = barLeft.toInt()
        lp.topMargin = barTop.toInt()
        bar.layoutParams = lp
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "RegionSelectorOverlay"
        const val MIN_REGION_PX = 100
        private const val CORNER_TOUCH_DP = 32
        private const val EDGE_TOUCH_DP = 24
    }

    /**
     * 长按约 400ms 后进入拖动状态，之后手指移动会带动整个按钮条；
     * 没有长按就抬起时，照常把点击交给里面的按钮。
     */
    private class DraggableBar(
        context: Context,
        private val onDrag: (Float, Float) -> Unit
    ) : LinearLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var downRawX = 0f
        private var downRawY = 0f
        private var lastRawX = 0f
        private var lastRawY = 0f
        private var dragging = false
        private val longPress = Runnable {
            dragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = 20f * resources.displayMetrics.density
                setColor(Color.argb(150, 0, 0, 0))
            }
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (8 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (6 * resources.displayMetrics.density).toInt()
            )
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    dragging = false
                    postDelayed(longPress, LONG_PRESS_MS)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.rawX - downRawX) > touchSlop ||
                            kotlin.math.abs(event.rawY - downRawY) > touchSlop)
                    ) {
                        removeCallbacks(longPress)
                    }
                    if (dragging) {
                        // 从拦截这一刻开始计位移，避免手指一直没动时突然跳一下
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
            }
            return dragging
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) {
                        val dx = event.rawX - lastRawX
                        val dy = event.rawY - lastRawY
                        lastRawX = event.rawX
                        lastRawY = event.rawY
                        if (dx != 0f || dy != 0f) onDrag(dx, dy)
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longPress)
                    dragging = false
                }
            }
            return true
        }

        companion object {
            private const val LONG_PRESS_MS = 400L
        }
    }

    private class SelectionView(
        context: Context,
        private val initialRegion: Rect?,
        private val screenWidth: Int,
        private val screenHeight: Int
    ) : View(context) {
        private enum class Mode {
            NONE, DRAW, MOVE, LEFT, TOP, RIGHT, BOTTOM,
            TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
        }

        private val density = resources.displayMetrics.density
        // 屏幕尺寸会随旋转变化，不能一直用创建时的值来换算（否则横屏录制区域会错位）
        private var displayWidth = screenWidth
        private var displayHeight = screenHeight
        private val selection = Rect()
        private val startRect = Rect()
        private var restored = false
        private var mode = Mode.NONE
        private var downX = 0f
        private var downY = 0f
        private var drawStartX = 0f
        private var drawStartY = 0f

        private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
            color = Color.WHITE
        }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
            color = Color.argb(90, 255, 255, 255)
        }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.WHITE
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        private val hint = context.getString(R.string.region_recording_hint)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            if (!restored) {
                restored = true
                val saved = initialRegion?.let { toViewRect(it) }
                if (saved != null &&
                    saved.width() >= RegionSelectorOverlay.MIN_REGION_PX &&
                    saved.height() >= RegionSelectorOverlay.MIN_REGION_PX
                ) {
                    selection.set(saved)
                } else {
                    selection.set(0, 0, w, h)
                }
            } else if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) {
                // 旋转/尺寸变化：选区按比例跟着屏幕走，保持用户框选的相对位置
                val sx = w.toFloat() / oldw
                val sy = h.toFloat() / oldh
                selection.set(
                    (selection.left * sx).toInt().coerceIn(0, w),
                    (selection.top * sy).toInt().coerceIn(0, h),
                    (selection.right * sx).toInt().coerceIn(0, w),
                    (selection.bottom * sy).toInt().coerceIn(0, h)
                )
            }
            selection.intersect(0, 0, w, h)
            // 全屏悬浮窗的尺寸就是当前屏幕尺寸，交回给服务前按它换算
            displayWidth = w
            displayHeight = h
        }

        fun selectFullScreen() {
            selection.set(0, 0, width, height)
            mode = Mode.NONE
            invalidate()
        }

        /**
         * 默认选区：屏幕宽度的 70%、16:9 的横向矩形，居中放在顶部提示和
         * 底部按钮条之间；横屏时按剩余高度等比缩小，保证不遮挡按钮。
         */
        fun selectDefault(bottomReserve: Int) {
            val topReserve = (96f * density).toInt()
            val usableTop = topReserve
            val usableBottom = (height - bottomReserve)
                .coerceAtLeast(usableTop + height / 4)
            val usableHeight = (usableBottom - usableTop).coerceAtLeast(RegionSelectorOverlay.MIN_REGION_PX)

            var rectWidth = (width * 0.7f).toInt()
            var rectHeight = (rectWidth * 9f / 16f).toInt()
            if (rectHeight > usableHeight) {
                rectHeight = usableHeight
                rectWidth = (rectHeight * 16f / 9f).toInt()
            }
            rectWidth = rectWidth.coerceAtMost(width)

            val left = (width - rectWidth) / 2
            val top = usableTop + (usableHeight - rectHeight) / 2
            selection.set(left, top, left + rectWidth, top + rectHeight)
            mode = Mode.NONE
            invalidate()
        }

        /** 返回真实屏幕坐标系下的选区，交给服务去编码 */
        fun selectedRect(): Rect {
            if (width <= 0 || height <= 0) return Rect(selection)
            return Rect(
                selection.left * displayWidth / width,
                selection.top * displayHeight / height,
                selection.right * displayWidth / width,
                selection.bottom * displayHeight / height
            )
        }

        private fun toViewRect(rect: Rect): Rect {
            if (width <= 0 || height <= 0 || displayWidth <= 0 || displayHeight <= 0) {
                return Rect(rect)
            }
            return Rect(
                rect.left * width / displayWidth,
                rect.top * height / displayHeight,
                rect.right * width / displayWidth,
                rect.bottom * height / displayHeight
            )
        }

        private fun isFullScreen(): Boolean =
            selection.left <= 0 && selection.top <= 0 &&
                selection.right >= width && selection.bottom >= height

        private fun hitTest(x: Float, y: Float): Mode {
            val corner = CORNER_TOUCH_DP * density
            val edge = EDGE_TOUCH_DP * density
            fun nearCorner(cx: Float, cy: Float): Boolean =
                kotlin.math.abs(x - cx) <= corner && kotlin.math.abs(y - cy) <= corner

            val left = selection.left.toFloat()
            val top = selection.top.toFloat()
            val right = selection.right.toFloat()
            val bottom = selection.bottom.toFloat()

            if (nearCorner(left, top)) return Mode.TOP_LEFT
            if (nearCorner(right, top)) return Mode.TOP_RIGHT
            if (nearCorner(left, bottom)) return Mode.BOTTOM_LEFT
            if (nearCorner(right, bottom)) return Mode.BOTTOM_RIGHT

            if (kotlin.math.abs(y - top) <= edge && x in left..right) return Mode.TOP
            if (kotlin.math.abs(y - bottom) <= edge && x in left..right) return Mode.BOTTOM
            if (kotlin.math.abs(x - left) <= edge && y in top..bottom) return Mode.LEFT
            if (kotlin.math.abs(x - right) <= edge && y in top..bottom) return Mode.RIGHT

            if (x > left && x < right && y > top && y < bottom) {
                // 满屏时没有空白处可以重新框选，内部拖动就当作重新框选
                return if (isFullScreen()) Mode.DRAW else Mode.MOVE
            }
            return Mode.DRAW
        }

        private fun resizeTo(x: Float, y: Float) {
            val dx = (x - downX).toInt()
            val dy = (y - downY).toInt()
            val minPx = RegionSelectorOverlay.MIN_REGION_PX
            var left = startRect.left
            var top = startRect.top
            var right = startRect.right
            var bottom = startRect.bottom

            when (mode) {
                Mode.LEFT, Mode.TOP_LEFT, Mode.BOTTOM_LEFT ->
                    left = (startRect.left + dx).coerceIn(0, (right - minPx).coerceAtLeast(0))
                Mode.RIGHT, Mode.TOP_RIGHT, Mode.BOTTOM_RIGHT ->
                    right = (startRect.right + dx).coerceIn((left + minPx).coerceAtMost(width), width)
                else -> {}
            }
            when (mode) {
                Mode.TOP, Mode.TOP_LEFT, Mode.TOP_RIGHT ->
                    top = (startRect.top + dy).coerceIn(0, (bottom - minPx).coerceAtLeast(0))
                Mode.BOTTOM, Mode.BOTTOM_LEFT, Mode.BOTTOM_RIGHT ->
                    bottom = (startRect.bottom + dy).coerceIn((top + minPx).coerceAtMost(height), height)
                else -> {}
            }
            selection.set(left, top, right, bottom)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    startRect.set(selection)
                    drawStartX = event.x
                    drawStartY = event.y
                    mode = hitTest(event.x, event.y)
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    when (mode) {
                        Mode.NONE -> {}

                        Mode.DRAW -> {
                            selection.set(
                                minOf(drawStartX, event.x).toInt(),
                                minOf(drawStartY, event.y).toInt(),
                                maxOf(drawStartX, event.x).toInt(),
                                maxOf(drawStartY, event.y).toInt()
                            )
                            selection.intersect(0, 0, width, height)
                        }

                        Mode.MOVE -> {
                            val dx = (event.x - downX).toInt()
                                .coerceIn(-startRect.left, (width - startRect.right).coerceAtLeast(0))
                            val dy = (event.y - downY).toInt()
                                .coerceIn(-startRect.top, (height - startRect.bottom).coerceAtLeast(0))
                            selection.set(startRect)
                            selection.offset(dx, dy)
                        }

                        else -> resizeTo(event.x, event.y)
                    }
                    invalidate()
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (mode == Mode.DRAW &&
                        (selection.width() < RegionSelectorOverlay.MIN_REGION_PX ||
                            selection.height() < RegionSelectorOverlay.MIN_REGION_PX)
                    ) {
                        // 拖动范围太小（多半是误触），回到拖动前的选区
                        if (startRect.isEmpty) {
                            selection.set(0, 0, width, height)
                        } else {
                            selection.set(startRect)
                        }
                    }
                    mode = Mode.NONE
                    invalidate()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
            canvas.drawRect(selection, clearPaint)
            canvas.restoreToCount(layer)

            canvas.drawRect(selection, borderPaint)
            // 三等分辅助线
            val thirdWidth = selection.width() / 3f
            val thirdHeight = selection.height() / 3f
            for (i in 1..2) {
                val x = selection.left + thirdWidth * i
                val y = selection.top + thirdHeight * i
                canvas.drawLine(x, selection.top.toFloat(), x, selection.bottom.toFloat(), guidePaint)
                canvas.drawLine(selection.left.toFloat(), y, selection.right.toFloat(), y, guidePaint)
            }

            drawHandles(canvas)

            canvas.drawText(hint, width / 2f, 72f * density, hintPaint)

            val label = "${selection.width()} × ${selection.height()}"
            val labelX = selection.exactCenterX()
            val labelY = (selection.bottom - 12f * density).coerceAtLeast(selection.top + 20f * density)
            canvas.drawText(label, labelX, labelY, textPaint)
        }

        /** 四角和四条边中点的小箭头，提示这些位置可以拖动缩放 */
        private fun drawHandles(canvas: Canvas) {
            val left = selection.left.toFloat()
            val top = selection.top.toFloat()
            val right = selection.right.toFloat()
            val bottom = selection.bottom.toFloat()
            val centerX = (left + right) / 2f
            val centerY = (top + bottom) / 2f

            drawArrow(canvas, left, top, -1f, -1f)
            drawArrow(canvas, right, top, 1f, -1f)
            drawArrow(canvas, left, bottom, -1f, 1f)
            drawArrow(canvas, right, bottom, 1f, 1f)
            drawArrow(canvas, centerX, top, 0f, -1f)
            drawArrow(canvas, centerX, bottom, 0f, 1f)
            drawArrow(canvas, left, centerY, -1f, 0f)
            drawArrow(canvas, right, centerY, 1f, 0f)
        }

        private fun drawArrow(canvas: Canvas, cx: Float, cy: Float, dx: Float, dy: Float) {
            val length = 7f * density
            val half = 5f * density
            val tipX = cx + dx * length
            val tipY = cy + dy * length
            val baseX = cx - dx * length * 0.6f
            val baseY = cy - dy * length * 0.6f
            val perpX = -dy * half
            val perpY = dx * half
            val path = Path()
            path.moveTo(tipX, tipY)
            path.lineTo(baseX + perpX, baseY + perpY)
            path.lineTo(baseX - perpX, baseY - perpY)
            path.close()
            canvas.drawPath(path, handlePaint)
        }
    }
}

/**
 * 录制期间的区域外遮罩：只画选区以外的四块半透明黑，并且不拦截触摸，
 * 选区外的操作照常可以点到。
 */
@RequiresApi(Build.VERSION_CODES.O)
class RegionMaskOverlay(
    private val context: Context,
    private val region: Rect
) {
    private val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private var root: View? = null
    private var removed = false

    fun show(): Boolean {
        if (root != null) return true
        removed = false
        return runCatching {
            val view = MaskView(context, region)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            windowManager.addView(view, params)
            root = view
            // 区域遮罩同样全屏，重新入栈让悬浮球和注释层保持在上层
            OverlayCoordinator.raiseAll(includeCanvas = true)
            true
        }.onFailure {
            Log.e(TAG, "Unable to show the region mask", it)
        }.getOrDefault(false)
    }

    fun remove() {
        if (removed) return
        removed = true
        runCatching { root?.let { windowManager.removeViewImmediate(it) } }
        root = null
    }

    private class MaskView(context: Context, private val region: Rect) : View(context) {
        private val paint = Paint().apply { color = Color.argb(150, 0, 0, 0) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val width = width.toFloat()
            val height = height.toFloat()
            val left = region.left.coerceIn(0, this.width).toFloat()
            val right = region.right.coerceIn(0, this.width).toFloat()
            val top = region.top.coerceIn(0, this.height).toFloat()
            val bottom = region.bottom.coerceIn(0, this.height).toFloat()

            canvas.drawRect(0f, 0f, width, top, paint)
            canvas.drawRect(0f, bottom, width, height, paint)
            canvas.drawRect(0f, top, left, bottom, paint)
            canvas.drawRect(right, top, width, bottom, paint)
        }
    }

    companion object {
        private const val TAG = "RegionMaskOverlay"
    }
}

/**
 * 同一进程内的悬浮窗协调器。
 *
 * 1. 把悬浮球和注释悬浮窗重新添加到窗口栈顶：同一应用、同一种窗口类型
 *    （TYPE_APPLICATION_OVERLAY）的叠放顺序就是添加顺序，所以全屏遮罩
 *    出现后必须把它们移除再添加一次。直接调用而不是发广播，避免广播异步
 *    投递导致重新入栈晚于遮罩，或者跨组件收不到。
 * 2. 在取消录制时通知注释层收尾：选区阶段状态一直是 IDLE，不会触发现有
 *    的状态监听，需要显式通知。
 */
object OverlayCoordinator {
    /** priority 越大越靠上：注释层先重加，悬浮球最后重加 */
    private val listeners = mutableListOf<Pair<Int, (Boolean) -> Unit>>()
    private val cancelListeners = mutableListOf<() -> Unit>()

    fun register(priority: Int, listener: (Boolean) -> Unit) {
        listeners.removeAll { it.second === listener }
        listeners.add(priority to listener)
    }

    fun unregister(listener: (Boolean) -> Unit) {
        listeners.removeAll { it.second === listener }
    }

    /** @param includeCanvas 全屏注释画布是否也重新入栈（选区阶段传 false，避免挡住手势） */
    fun raiseAll(includeCanvas: Boolean) {
        listeners.sortedBy { it.first }.forEach { (_, listener) ->
            runCatching { listener(includeCanvas) }.onFailure {
                Log.e("OverlayCoordinator", "Unable to raise an overlay", it)
            }
        }
    }

    fun registerCancelListener(listener: () -> Unit) {
        cancelListeners.removeAll { it === listener }
        cancelListeners.add(listener)
    }

    fun unregisterCancelListener(listener: () -> Unit) {
        cancelListeners.removeAll { it === listener }
    }

    fun notifyCancelled() {
        cancelListeners.toList().forEach { listener ->
            runCatching { listener() }.onFailure {
                Log.e("OverlayCoordinator", "Unable to hide an overlay", it)
            }
        }
    }
}
