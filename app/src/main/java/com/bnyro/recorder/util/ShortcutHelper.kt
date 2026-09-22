package com.bnyro.recorder.util

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.MainActivity

object ShortcutHelper {
    sealed class AppShortcut(
        val id: String,
        @DrawableRes val iconRes: Int,
        @StringRes val label: Int
    ) {
        abstract fun buildIntent(context: Context): Intent

        object RecordAudio : AppShortcut(RecorderType.AUDIO.name, R.drawable.ic_audio, R.string.record_sound) {
            override fun buildIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.AUDIO.name)
            }
        }

        object RecordScreen : AppShortcut(RecorderType.VIDEO.name, R.drawable.ic_screen, R.string.record_screen) {
            override fun buildIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra(MainActivity.EXTRA_ACTION_KEY, RecorderType.VIDEO.name)
            }
        }

        object EnableFloatingBall : AppShortcut(
            "EnableFloatingBall",
            R.drawable.ic_screen_record,
            R.string.shortcut_enable_floating_ball
        ) {
            override fun buildIntent(context: Context) = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_ENABLE_FLOATING_BALL
            }
        }
    }

    private val shortcuts = listOf(
        AppShortcut.RecordAudio,
        AppShortcut.RecordScreen,
        AppShortcut.EnableFloatingBall
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
        shortcuts.forEach { createShortcut(context, it) }
    }
}
