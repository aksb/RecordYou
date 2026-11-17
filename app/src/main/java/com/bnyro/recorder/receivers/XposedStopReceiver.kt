package com.bnyro.recorder.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.bnyro.recorder.R
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderControl
import com.bnyro.recorder.util.RecorderStatusHolder

/**
 * 接收 LSPosed 模块在息屏长按音量键后发来的停止指令。
 * 只在开关开启且确实有录制时执行，其余情况直接忽略。
 */
class XposedStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false)) return
        val type = RecorderStatusHolder.activeType ?: return

        RecorderControl.requestStop(context, type)
        Toast.makeText(context, R.string.volume_key_stop_toast, Toast.LENGTH_SHORT).show()
    }
}
