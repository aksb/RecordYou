package com.bnyro.recorder.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.bnyro.recorder.util.XposedVolumeKeyState

/**
 * 开机后把快捷录音总开关重新推送给 LSPosed 模块。
 * 模块活在 system_server 里，手机重启后配置丢失，必须在这里补一次同步。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        XposedVolumeKeyState.syncQuickConfig(context)
    }
}
