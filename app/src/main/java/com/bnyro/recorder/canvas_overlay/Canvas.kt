package com.bnyro.recorder.canvas_overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.view.MotionEvent
import android.view.View

enum class DrawMode {
    Pen, Eraser
}

/**
 * 注释画布。
 *
 * 之前用 ComposeView 承载：从悬浮球开始录制时宿主 Activity 处于 STOPPED，
 * Compose 的 recomposer / 指针输入会跟着暂停，表现为怎么画都没反应；
 * 悬浮窗重新入栈后指针输入还会彻底失效。改成普通 View 后不依赖任何
 * Activity 生命周期，和工具栏的处理方式保持一致。
 */
class DrawingView(context: Context) : View(context) {
    private class Stroke(val path: Path, val eraser: Boolean)

    private val strokes = mutableListOf<Stroke>()
    private var currentStroke: Stroke? = null
    private val density = resources.displayMetrics.density

    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.RED
        strokeWidth = 4f * density
    }

    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 16f * density
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    var drawMode = DrawMode.Eraser

    fun clear() {
        strokes.clear()
        currentStroke = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val stroke = Stroke(Path(), drawMode == DrawMode.Eraser)
                stroke.path.moveTo(event.x, event.y)
                currentStroke = stroke
                strokes.add(stroke)
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val stroke = currentStroke
                if (stroke != null) {
                    // 一次 MOVE 可能带多个采样点，逐个连上，快速划动才不会断续
                    for (i in 0 until event.historySize) {
                        stroke.path.lineTo(event.getHistoricalX(i), event.getHistoricalY(i))
                    }
                    stroke.path.lineTo(event.x, event.y)
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentStroke = null
                invalidate()
            }
        }
        // 其余动作（多指按下/抬起等）也返回 true，避免手势中途被父 View 接管
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (strokes.isEmpty()) return
        // 橡皮擦靠 CLEAR 清除已经画上去的笔迹，必须限制在同一个图层里
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        for (stroke in strokes) {
            canvas.drawPath(stroke.path, if (stroke.eraser) eraserPaint else penPaint)
        }
        canvas.restoreToCount(layer)
    }
}
