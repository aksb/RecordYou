package com.bnyro.recorder.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.services.VolumeKeyStopService
import java.util.concurrent.TimeUnit

/**
 * 音量键长按停止的可用性检查。
 *
 * 开关存在 SharedPreferences 里，但真正拦截按键的是系统无障碍服务：
 * 用户清理最近任务（MIUI 等）时系统会把服务从已启用列表里摘掉，开关却还是
 * 开着的。这时长按音量键不会有任何反应，必须主动检查并提醒。
 *
 * 自愈有两条路：拿到 WRITE_SECURE_SETTINGS（可通过 adb 一次性授予）时直接
 * 写回系统设置；否则尝试 su（有 root 时可用）。两者都失败就交给界面提示。
 */
object VolumeKeyStopHelper {
    /** 应用内开关是否打开。 */
    fun isWanted(): Boolean =
        Preferences.prefs.getBoolean(Preferences.volumeKeyStopKey, false)

    /** 服务是否还在系统的“已启用无障碍服务”列表里。 */
    fun isDeclared(context: Context): Boolean {
        val enabled = enabledServices(context)
        return enabled.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == component(context) }
    }

    /** 服务是否真的连上了（列表里有但还没绑定完成时会短暂为 false）。 */
    fun isBound(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? AccessibilityManager ?: return false
        if (!manager.isEnabled) return false
        return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { ComponentName.unflattenFromString(it.id) == component(context) }
    }

    fun isReady(context: Context): Boolean = isDeclared(context) || isBound(context)

    /**
     * 用户已经声明启用了 LSPosed 模块。模块在息屏和无障碍不可用时都会接管，
     * 因此这种情况下无障碍服务不是必需项，也不应该提示用户去开启。
     */
    fun isXposedDeclared(): Boolean =
        Preferences.prefs.getBoolean(Preferences.volumeKeyStopXposedKey, false)

    /**
     * 开关打开、但两种实现都不可用。LSPosed 模块由用户自己声明启用，
     * 已声明时不再因为无障碍服务缺失而提示。
     */
    fun needsAttention(context: Context): Boolean =
        isWanted() && !isXposedDeclared() && !isReady(context)

    fun canWriteSecureSettings(context: Context): Boolean =
        context.packageManager.checkPermission(
            Manifest.permission.WRITE_SECURE_SETTINGS,
            context.packageName
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 直接把服务写回系统设置。没有 WRITE_SECURE_SETTINGS 时什么都不做，
     * 返回 false，由调用方决定是否尝试 root 或提示用户。
     */
    fun selfHeal(context: Context): Boolean {
        if (!isWanted() || isReady(context)) return true
        if (!canWriteSecureSettings(context)) return false

        return runCatching {
            val current = enabledServices(context)
            val componentName = component(context)
            val alreadyListed = current.split(':')
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any { it == componentName }
            if (!alreadyListed) {
                val component = componentName.flattenToString()
                val updated = if (current.isBlank()) component else "$current:$component"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
            }
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            isDeclared(context)
        }.getOrDefault(false)
    }

    /**
     * 通过 su 恢复（阻塞，必须在后台线程调用）。优先一次性授予
     * WRITE_SECURE_SETTINGS，之后不再需要 su；失败时退回直接写系统设置。
     * Magisk 只会在第一次弹授权；被拒绝后本次进程不再自动尝试，
     * 手动点“检测/授权”会重置这个标记。
     */
    fun selfHealWithRoot(context: Context): Boolean {
        if (!isWanted() || isReady(context)) return true
        if (rootDenied) return false
        val healed = grantWithRoot(context)
        if (!healed) rootDenied = true
        return healed && isReady(context)
    }

    /**
     * 显式请求 root 修复（用户点了“检测/授权”）。允许重试以前被拒绝过的授权。
     * @return true 表示权限已授予或服务已写回。
     */
    fun grantWithRoot(context: Context): Boolean {
        rootDenied = false
        if (canWriteSecureSettings(context)) return selfHeal(context)

        // 首选：一次性 pm grant，之后复用普通自愈路径，不再反复调用 su
        val granted = runSuCommand(
            "pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"
        )
        if (granted || canWriteSecureSettings(context)) {
            if (selfHeal(context)) return true
        }
        // 进度可能因进程被系统重启而丢失，也可能被 ROM 拒绝；直接写设置兜底
        return writeSettingsWithRoot(context)
    }

    /** adb 授权命令，供界面展示和复制。包名不能写死，debug/release 不同。 */
    fun secureSettingsCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    private fun writeSettingsWithRoot(context: Context): Boolean {
        val current = enabledServices(context)
        val componentName = component(context)
        val component = componentName.flattenToString()
        val alreadyListed = current.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any { it == componentName }
        val updated = if (alreadyListed || current.isBlank()) component else "$current:$component"

        val ok = runSuCommand("settings put secure enabled_accessibility_services '$updated'") &&
            runSuCommand("settings put secure accessibility_enabled 1")
        return ok && isDeclared(context)
    }

    /** 无障碍失效时，还有哪些结束录制的方式可用。 */
    fun backupStopHint(context: Context): String {
        val hasBall = FloatingBallHelper.isEnabled(context, RecorderType.AUDIO) ||
            FloatingBallHelper.isEnabled(context, RecorderType.VIDEO)
        return context.getString(
            if (hasBall) {
                R.string.volume_key_stop_backup_both
            } else {
                R.string.volume_key_stop_backup_notification
            }
        )
    }

    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun component(context: Context) =
        ComponentName(context, VolumeKeyStopService::class.java)

    private fun enabledServices(context: Context): String =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()

    private fun runSuCommand(command: String): Boolean {
        val process = runCatching {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return false

        val finished = runCatching { process.waitFor(3, TimeUnit.SECONDS) }
            .getOrDefault(false)
        if (!finished) {
            runCatching { process.destroy() }
            return false
        }
        return runCatching { process.exitValue() == 0 }.getOrDefault(false)
    }

    @Volatile
    private var rootDenied = false
}
