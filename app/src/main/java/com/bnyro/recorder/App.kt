package com.bnyro.recorder

import android.app.Application
import com.bnyro.recorder.util.FileRepository
import com.bnyro.recorder.util.FileRepositoryImpl
import com.bnyro.recorder.util.NotificationHelper
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.ShortcutHelper
import com.bnyro.recorder.util.XposedVolumeKeyState

class App : Application() {
    val fileRepository: FileRepository by lazy {
        FileRepositoryImpl(this)
    }
    override fun onCreate() {
        super.onCreate()
        Preferences.init(this)
        NotificationHelper.buildNotificationChannels(this)
        ShortcutHelper.createShortcuts(this)
        // 进程每次启动都把快捷录音开关补发给 LSPosed 模块，
        // 覆盖「模块已注入但 App 恰好重启过」的空窗
        XposedVolumeKeyState.syncQuickConfig(this)
    }
}
