package com.bnyro.recorder.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.enums.ThemeMode
import com.bnyro.recorder.services.OverlayBallService
import com.bnyro.recorder.ui.models.RecorderModel
import com.bnyro.recorder.ui.models.ThemeModel
import com.bnyro.recorder.ui.theme.RecordYouTheme
import com.bnyro.recorder.util.FloatingBallHelper
import com.bnyro.recorder.util.Preferences

class MainActivity : ComponentActivity() {
    private var initialRecorder = RecorderType.NONE
    private var exitAfterRecordingStart = false
    private lateinit var mProjectionManager: MediaProjectionManager
    private val recorderModel: RecorderModel by viewModels()
    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val themeModel: ThemeModel by viewModels()
        launcher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    recorderModel.startVideoRecorder(this, result)
                }
            }
        mProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        processIntent(intent)
        ensureFloatingBallRunning()
        enableEdgeToEdge()

        setContent {
            RecordYouTheme(
                when (themeModel.themeMode) {
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                    ThemeMode.DARK, ThemeMode.AMOLED -> true
                    else -> false
                },
                amoledDark = themeModel.themeMode == ThemeMode.AMOLED
            ) {
                val navController = rememberNavController()
                Surface(
                    modifier = Modifier
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost(
                        navController = navController,
                        modifier = Modifier,
                        initialRecorder = initialRecorder
                    )
                }
            }
        }
    }

    /**
     * If the user has the floating ball enabled and the overlay permission is
     * still granted, (re-)start [OverlayBallService]. This is a no-op if it's
     * already running - it just makes sure the ball comes back after the
     * process/service got killed by the system while RecordYou was in the
     * background.
     */
    private fun ensureFloatingBallRunning() {
        val enabled = Preferences.prefs.getBoolean(Preferences.floatingBallKey, false)
        if (!enabled) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!Settings.canDrawOverlays(this)) return
        startService(Intent(this, OverlayBallService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        processIntent(intent)
        super.onNewIntent(intent)
    }

    private fun processIntent(intent: Intent) {
        if (intent.action == ACTION_ENABLE_FLOATING_BALL) {
            handleEnableFloatingBallShortcut()
            return
        }

        val initialRecorderType = intent.getStringExtra(EXTRA_ACTION_KEY)?.let {
            RecorderType.valueOf(it)
        } ?: RecorderType.NONE
        initialRecorder = initialRecorderType
        if (initialRecorderType == RecorderType.AUDIO) {
            // audio recording starts synchronously (no system consent dialog needed
            // once the mic permission is granted), so we can move the task back
            // right away instead of waiting for a pause/resume cycle.
            if (recorderModel.startAudioRecorder(this)) {
                exitAfterRecordingStart = true
            }
        } else if (initialRecorderType == RecorderType.VIDEO) {
            if (recorderModel.hasScreenRecordingPermissions(this)) {
                launcher.launch(mProjectionManager.createScreenCaptureIntent())
            }
        }
        intent.removeExtra(EXTRA_ACTION_KEY)
    }

    /**
     * Handles the "开启悬浮球" launcher shortcut (long-press the app icon).
     * If the overlay permission is already granted, turns the ball on and
     * quietly returns to whatever app the user was in - same "invisible
     * start" pattern used for the audio/video quick actions. Otherwise sends
     * the user to the system permission screen; they'll need to trigger the
     * shortcut again afterwards.
     */
    private fun handleEnableFloatingBallShortcut() {
        if (FloatingBallHelper.setEnabled(this, true)) {
            exitAfterRecordingStart = true
        }
    }

    override fun onPause() {
        super.onPause()
        if (initialRecorder == RecorderType.VIDEO) {
            exitAfterRecordingStart = true
            initialRecorder = RecorderType.NONE
        }
    }

    override fun onResume() {
        super.onResume()
        if (exitAfterRecordingStart) {
            exitAfterRecordingStart = false
            moveTaskToBack(true)
        }
    }

    companion object {
        const val EXTRA_ACTION_KEY = "action"
        const val ACTION_ENABLE_FLOATING_BALL = "com.bnyro.recorder.action.ENABLE_FLOATING_BALL"
    }
}
