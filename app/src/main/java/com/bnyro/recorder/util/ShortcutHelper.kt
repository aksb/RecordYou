package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.MainActivity
import com.bnyro.recorder.ui.QuickActionActivity

object ShortcutHelper {
    sealed class AppShortcut(
        val id: String,
        @DrawableRes val iconRes: Int,
        @StringRes val label: Int
    ) {
        abstract fun buildIntent(context: Context): Intent

        object RecordAudio : AppShortcut(RecorderType.AUDIO.name, R.drawable.ic_audio, R.string.record_sound) {
            override fun buildIntent(context: Context) = Intent(context, QuickActionActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.AUDIO.name)
            }
        }

        object RecordScreen : AppShortcut(RecorderType.VIDEO.name, R.drawable.ic_screen, R.string.record_screen) {
            override fun buildIntent(context: Context) = Intent(context, QuickActionActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.VIDEO.name)
            }
        }

        object EnableAudioBall : AppShortcut(
            "EnableAudioBall",
            R.drawable.ic_audio,
            R.string.shortcut_enable_audio_ball
        ) {
            override fun buildIntent(context: Context) = enableBallIntent(context, RecorderType.AUDIO)
        }

        object EnableVideoBall : AppShortcut(
            "EnableVideoBall",
            R.drawable.ic_screen,
            R.string.shortcut_enable_video_ball
        ) {
            override fun buildIntent(context: Context) = enableBallIntent(context, RecorderType.VIDEO)
        }
    }

    private fun enableBallIntent(context: Context, type: RecorderType) =
        Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_ENABLE_FLOATING_BALL
            putExtra(MainActivity.EXTRA_BALL_TYPE_KEY, type.name)
        }

    private val shortcuts = listOf(
        AppShortcut.RecordAudio,
        AppShortcut.RecordScreen,
        AppShortcut.EnableAudioBall,
        AppShortcut.EnableVideoBall
    )

    private fun createShortcut(context: Context, shortcut: AppShortcut) {
        val info = ShortcutInfoCompat.Builder(context, shortcut.id)
            .setShortLabel(context.getString(shortcut.label))
            .setLongLabel(context.getString(shortcut.label))
            .setIcon(IconCompat.createWithResource(context, shortcut.iconRes))
            .setIntent(shortcut.buildIntent(context))
            .build()

        ShortcutManagerCompat.pushDynamicShortcut(context, info)
    }

    fun createShortcuts(context: Context) {
        // pushDynamicShortcut() is idempotent (it just replaces the shortcut
        // with the same id), so it's safe - and necessary - to run this on
        // every app start rather than only once: it's what makes sure users
        // upgrading from an older version that had fewer shortcuts actually
        // get the new ones too.
        // 旧版本只有一个“开启悬浮球”快捷方式，它不会因为 push 新的而自动消失。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                context.getSystemService(ShortcutManager::class.java)
                    ?.removeDynamicShortcuts(listOf(LEGACY_ENABLE_FLOATING_BALL_ID))
            }
        }
        shortcuts.forEach { createShortcut(context, it) }
    }

    private const val LEGACY_ENABLE_FLOATING_BALL_ID = "EnableFloatingBall"
}
