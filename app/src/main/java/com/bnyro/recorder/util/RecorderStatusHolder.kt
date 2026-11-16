package com.bnyro.recorder.util

import android.os.SystemClock
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType

/**
 * Process-wide, lightweight source of truth for the current recording state
 * of each [RecorderType].
 *
 * [com.bnyro.recorder.services.RecorderService] pushes state changes here whenever
 * recording starts/stops. Since the floating balls
 * ([com.bnyro.recorder.services.FloatingBallService]) live in the very same app
 * process, they can simply subscribe to this holder instead of binding to the
 * recorder service or relying on broadcasts just to know "is something being
 * recorded right now" - and, unlike a single global state, they can tell
 * whether it is *their* type that is recording.
 */
object RecorderStatusHolder {
    private val states = mutableMapOf(
        RecorderType.AUDIO to RecorderState.IDLE,
        RecorderType.VIDEO to RecorderState.IDLE
    )

    private val listeners = mutableListOf<(RecorderType, RecorderState) -> Unit>()

    /** 每种录制开始的时间（elapsedRealtime），用于跨入口恢复计时。 */
    private val startedAt = mutableMapOf<RecorderType, Long>()

    fun stateOf(type: RecorderType): RecorderState = states[type] ?: RecorderState.IDLE

    /** true if a recording of any type is currently running. */
    val isRecording: Boolean
        get() = states.values.any { it == RecorderState.ACTIVE }

    /** 当前正在录制的类型；null 表示空闲。 */
    val activeType: RecorderType?
        get() = states.entries.firstOrNull { it.value == RecorderState.ACTIVE }?.key

    /** 指定类型的录制开始时刻，用于显示已经录了多久。 */
    fun activeSince(type: RecorderType): Long =
        startedAt[type] ?: SystemClock.elapsedRealtime()

    fun addListener(listener: (RecorderType, RecorderState) -> Unit) {
        listeners.add(listener)
        // immediately report ongoing recordings so late subscribers don't miss them
        states.forEach { (type, state) ->
            if (state == RecorderState.ACTIVE) listener(type, state)
        }
    }

    fun removeListener(listener: (RecorderType, RecorderState) -> Unit) {
        listeners.remove(listener)
    }

    fun updateState(type: RecorderType, newState: RecorderState) {
        states[type] = newState
        if (newState == RecorderState.ACTIVE) {
            startedAt[type] = SystemClock.elapsedRealtime()
        } else {
            startedAt.remove(type)
        }
        // copy: a listener may remove itself (or others) while being notified
        listeners.toList().forEach { it(type, newState) }
    }
}
