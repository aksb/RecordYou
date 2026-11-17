package com.bnyro.recorder.xposed

import android.content.Context
import android.view.KeyEvent
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 入口：只在 system_server 里 hook 按键分发。
 *
 * 目标方法 PhoneWindowManager.interceptKeyBeforeQueueing(KeyEvent, int) 在
 * Android 11~16 上签名一致，所以这里不需要按系统版本分支。模块只在收到
 * App 的录制状态时才接管按键，其余情况完全放行。
 */
class VolumeKeyModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android" || lpparam.processName != "android") return

        runCatching {
            XposedHelpers.findAndHookMethod(
                "com.android.server.policy.PhoneWindowManager",
                lpparam.classLoader,
                "interceptKeyBeforeQueueing",
                KeyEvent::class.java,
                Integer.TYPE,
                VolumeKeyHook
            )
            VolumeKeyHook.attachSystemContext(getSystemContext())
            XposedBridge.log("[RecordYou] VolumeKey hook installed")
        }.onFailure {
            XposedBridge.log("[RecordYou] VolumeKey hook failed: $it")
        }
    }

    /** system_server 早期没有现成 Context，从 ActivityThread 取系统上下文 */
    private fun getSystemContext(): Context? = runCatching {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
        activityThread.getMethod("getSystemContext").invoke(current) as? Context
    }.getOrNull()
}
