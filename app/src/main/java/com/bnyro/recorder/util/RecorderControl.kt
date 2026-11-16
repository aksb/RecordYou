package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.services.RecorderService

/**
 * 统一的停止入口。
 *
 * 悬浮球、应用内按钮（以及后续的摇一摇、音量键等触发方式）都通过这里
 * 发送带类型的停止广播，避免每个入口各写一份、行为和状态对不上。
 */
object RecorderControl {
    fun requestStop(context: Context, type: RecorderType) {
        context.sendBroadcast(
            Intent(RecorderService.RECORDER_INTENT_ACTION)
                .putExtra(RecorderService.ACTION_EXTRA_KEY, RecorderService.STOP_ACTION)
                .putExtra(RecorderService.TYPE_EXTRA_KEY, type.name)
        )
    }

    fun requestStopActive(context: Context) {
        RecorderStatusHolder.activeType?.let { requestStop(context, it) }
    }
}
