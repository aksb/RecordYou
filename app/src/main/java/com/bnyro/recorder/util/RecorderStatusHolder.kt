package com.bnyro.recorder.util

import com.bnyro.recorder.enums.RecorderState

/**
 * Process-wide, lightweight source of truth for the current [RecorderState].
 *
 * [com.bnyro.recorder.services.RecorderService] pushes state changes here whenever
 * recording starts/pauses/resumes/stops. Since the floating ball
 * ([com.bnyro.recorder.services.OverlayBallService]) lives in the very same app
 * process, it can simply subscribe to this holder instead of binding to the
 * recorder service or relying on broadcasts just to know "is something being
 * recorded right now".
 */
object RecorderStatusHolder {
    var state: RecorderState = RecorderState.IDLE
        private set

    private val listeners = mutableListOf<(RecorderState) -> Unit>()

    fun addListener(listener: (RecorderState) -> Unit) {
        listeners.add(listener)
        // immediately report the current state so late subscribers don't miss it
        listener(state)
    }

    fun removeListener(listener: (RecorderState) -> Unit) {
        listeners.remove(listener)
    }

    fun updateState(newState: RecorderState) {
        state = newState
        listeners.forEach { it(newState) }
    }
}
