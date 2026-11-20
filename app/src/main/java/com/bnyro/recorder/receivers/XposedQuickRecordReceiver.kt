package com.bnyro.recorder.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.bnyro.recorder.R
import com.bnyro.recorder.ui.LockscreenRecordActivity
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderControl
import com.bnyro.recorder.util.RecorderStatusHolder

/**
 * 接收 LSPosed 模块在「息屏 + 双键长按」后发来的快捷录音指令。
 *
 * - 空闲：拉起盖在锁屏上的全屏录音页并开始话筒录音；
 * - 已有录制在进行：转为停止（重复按键 = 停止）；
 * - 上一段还在收尾：忽略，避免与未释放的采集器抢资源。
 *
 * 清单里用 STATUS_BAR 权限保护，只有系统进程（模块）能送达。
 */
class XposedQuickRecordReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (!Preferences.prefs.getBoolean(Preferences.quickRecordLockscreenKey, false)) return

        val activeType = RecorderStatusHolder.activeType
        if (activeType != null) {
            RecorderControl.requestStop(context, activeType)
            Toast.makeText(context, R.string.volume_key_stop_toast, Toast.LENGTH_SHORT).show()
            return
        }
        if (RecorderStatusHolder.isFinalizing) return

        // 从广播后台拉起 Activity 依赖「显示在其他应用上层」权限的 BAL 豁免，
        // 没有这个权限时系统会直接拦截，提前提示而不是静默失败
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, R.string.quick_record_need_overlay, Toast.LENGTH_LONG).show()
            return
        }

        runCatching {
            context.startActivity(
                Intent(context, LockscreenRecordActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(LockscreenRecordActivity.EXTRA_AUTOSTART, true)
            )
        }.onFailure {
            Toast.makeText(context, R.string.quick_record_need_overlay, Toast.LENGTH_LONG).show()
        }
    }
}
