package com.bnyro.recorder.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 让 Material3 Slider 变成「只能按住圆点拖动」：原生 Slider 点击轨道任意
 * 位置都会直接跳到该值，设置页里很容易误触。
 *
 * 原理：这个修饰符挂在 Slider 外层，事件在 Initial 阶段（先于 Slider 内部
 * 处理）检查按下点的横坐标是否落在当前圆点附近；不在就把整段手势全部
 * 消费掉，Slider 收不到任何事件；落在圆点上则完全不干预，拖动手感不变。
 */
@Composable
fun Modifier.thumbOnlyDrag(value: Float, valueRange: ClosedFloatingPointRange<Float>): Modifier {
    // 用 UpdatedState 读最新值：range 每次重组都是新对象且无结构相等性，
    // 不能拿它当 pointerInput 的 key，否则拖动中每次数值变化都会重启手势处理器
    val currentValue by rememberUpdatedState(value)
    val currentRange by rememberUpdatedState(valueRange)
    return this.pointerInput(Unit) {
        val touchRadius = 36.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val width = size.width.toFloat()
            if (width <= 0f) return@awaitEachGesture
            val fraction = ((currentValue - currentRange.start) /
                (currentRange.endInclusive - currentRange.start)).coerceIn(0f, 1f)
            val thumbX = fraction * width
            if (abs(down.position.x - thumbX) > touchRadius) {
                // 不是从圆点出发的手势：消费到底，Slider 完全无感
                down.consume()
                var event = awaitPointerEvent(PointerEventPass.Initial)
                while (event.changes.any { it.pressed }) {
                    event.changes.forEach { it.consume() }
                    event = awaitPointerEvent(PointerEventPass.Initial)
                }
            }
        }
    }
}
