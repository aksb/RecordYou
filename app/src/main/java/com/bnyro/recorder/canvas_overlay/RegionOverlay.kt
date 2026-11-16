package com.bnyro.recorder.canvas_overlay

import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import com.bnyro.recorder.R

/**
 * 录屏开始前的选区界面：全屏半透明，拖动选择一个矩形区域，
 * 确认后才真正开始采集。
 */
@RequiresApi(Build.VERSION_CODES.O)
class RegionSelectorOverlay(
    private val context: Context,
    private val onConfirm: (Rect) -> Unit,
    private val onCancel: () -> Unit
) {
    private val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var selectionView: SelectionView? = null
    private var removed = false

    fun show(): Boolean {
        remove()
        removed = false
        return runCatching {
            val container = FrameLayout(context)
            val view = SelectionView(context)
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            val cancelButton = Button(context).apply {
                text = context.getString(R.string.cancel)
                setOnClickListener {
                    remove()
                    onCancel()
                }
            }
            val fullScreenButton = Button(context).apply {
                text = context.getString(R.string.region_recording_full_screen)
                setOnClickListener { view.selectFullScreen() }
            }
            val confirmButton = Button(context).apply {
                text = context.getString(R.string.region_recording_start)
                setOnClickListener {
                    val rect = view.selectedRect()
                    if (rect.width() < MIN_REGION_PX || rect.height() < MIN_REGION_PX) return@setOnClickListener
                    remove()
                    onConfirm(rect)
                }
            }
            bar.addView(cancelButton)
            bar.addView(fullScreenButton)
            bar.addView(confirmButton)
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
            true
        }.onFailure {
            Log.e(TAG, "Unable to show the region selector", it)
        }.getOrDefault(false)
    }

    fun remove() {
        if (removed) return
        removed = true
        runCatching { root?.let { windowManager.removeView(it) } }
        root = null
        selectionView = null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "RegionSelectorOverlay"
        const val MIN_REGION_PX = 100
    }

    private class SelectionView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val selection = Rect()
        private var touchStartX = 0f
        private var touchStartY = 0f
        private var dragging = false

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
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 15f * density
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 0f, 0f, Color.BLACK)
        }
        private val hint = context.getString(R.string.region_recording_hint)

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (selection.isEmpty) {
                selection.set(0, 0, w, h)
            }
            selection.intersect(0, 0, w, h)
        }

        fun selectFullScreen() {
            selection.set(0, 0, width, height)
            dragging = false
            invalidate()
        }

        fun selectedRect(): Rect = Rect(selection)

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!dragging &&
                        (kotlin.math.abs(event.x - touchStartX) > 8 * density ||
                            kotlin.math.abs(event.y - touchStartY) > 8 * density)
                    ) {
                        dragging = true
                    }
                    if (dragging) {
                        selection.set(
                            minOf(touchStartX, event.x).toInt(),
                            minOf(touchStartY, event.y).toInt(),
                            maxOf(touchStartX, event.x).toInt(),
                            maxOf(touchStartY, event.y).toInt()
                        )
                        selection.intersect(0, 0, width, height)
                        invalidate()
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging &&
                        (selection.width() < RegionSelectorOverlay.MIN_REGION_PX ||
                            selection.height() < RegionSelectorOverlay.MIN_REGION_PX)
                    ) {
                        // 拖动范围太小（多半是误触），恢复成全屏
                        selection.set(0, 0, width, height)
                    }
                    dragging = false
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

            canvas.drawText(hint, width / 2f, 72f * density, hintPaint)

            val label = "${selection.width()} × ${selection.height()}"
            val labelX = selection.exactCenterX()
            val labelY = (selection.bottom - 12f * density).coerceAtLeast(selection.top + 20f * density)
            canvas.drawText(label, labelX, labelY, textPaint)
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
            true
        }.onFailure {
            Log.e(TAG, "Unable to show the region mask", it)
        }.getOrDefault(false)
    }

    fun remove() {
        if (removed) return
        removed = true
        runCatching { root?.let { windowManager.removeView(it) } }
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
