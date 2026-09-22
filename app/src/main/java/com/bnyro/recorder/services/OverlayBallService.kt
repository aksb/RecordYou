package com.bnyro.recorder.services

import android.app.AlertDialog
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.MainActivity
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderStatusHolder
import kotlin.math.abs

/**
 * A small draggable floating ball, shown on top of every other app, that lets
 * the user start audio/screen recording and pause/resume/stop an ongoing one
 * without switching back into RecordYou.
 *
 * - Tap while idle -> opens a small 2-button menu (record audio / record screen).
 * - Tap while recording -> pause/resume.
 * - Long-press while recording -> stop.
 * - Drag -> moves the ball, snaps to the nearest screen edge on release.
 *
 * Requires the "display over other apps" permission (SYSTEM_ALERT_WINDOW),
 * which must already be granted before this service is started - see
 * the floating-ball toggle in SettingsScreen.
 */
@RequiresApi(Build.VERSION_CODES.O)
class OverlayBallService : Service() {

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var audioButton: ImageView? = null
    private var videoButton: ImageView? = null
    private var closeButton: ImageView? = null
    private var menuShown = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var dragging = false
    private var longPressHandled = false

    private val stateListener: (RecorderState) -> Unit = { state ->
        mainHandler.post { updateBallAppearance(state) }
    }

    private val autoHideMenuRunnable = Runnable { hideMenu() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBall()
        // Reports the current state immediately, so the ball looks right even
        // if a recording was already running before the ball was turned on.
        RecorderStatusHolder.addListener(stateListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        RecorderStatusHolder.removeListener(stateListener)
        mainHandler.removeCallbacksAndMessages(null)
        hideMenu()
        ballView?.let { runCatching { windowManager.removeView(it) } }
        ballView = null
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /**
     * Loads the last saved ball position for the current orientation, clamped
     * to the current screen bounds. Falls back to the default starting
     * position (left edge, upper third of the screen) if nothing was saved
     * yet, or if the saved position no longer fits the screen.
     */
    private fun defaultOrSavedPosition(): Pair<Int, Int> {
        val size = dpToPx(BALL_SIZE_DP)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxX = (screenWidth - size).coerceAtLeast(0)
        val maxY = (screenHeight - size).coerceAtLeast(0)

        val xKey = if (isLandscape()) Preferences.ballPosXLandscapeKey else Preferences.ballPosXPortraitKey
        val yKey = if (isLandscape()) Preferences.ballPosYLandscapeKey else Preferences.ballPosYPortraitKey
        val savedX = Preferences.prefs.getInt(xKey, Int.MIN_VALUE)
        val savedY = Preferences.prefs.getInt(yKey, Int.MIN_VALUE)

        return if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
            savedX.coerceIn(0, maxX) to savedY.coerceIn(0, maxY)
        } else {
            0 to (screenHeight / 3).coerceIn(0, maxY)
        }
    }

    private fun savePosition(x: Int, y: Int) {
        val xKey = if (isLandscape()) Preferences.ballPosXLandscapeKey else Preferences.ballPosXPortraitKey
        val yKey = if (isLandscape()) Preferences.ballPosYLandscapeKey else Preferences.ballPosYPortraitKey
        Preferences.edit {
            putInt(xKey, x)
            putInt(yKey, y)
        }
    }

    private fun addBall() {
        val size = dpToPx(BALL_SIZE_DP)
        val (initialX, initialY) = defaultOrSavedPosition()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        val ball = ImageView(this).apply {
            val pad = dpToPx(14)
            setPadding(pad, pad, pad, pad)
            setOnTouchListener { _, event -> onBallTouch(event) }
        }
        runCatching { windowManager.addView(ball, params) }
        ballView = ball
        ballParams = params
        updateBallAppearance(RecorderStatusHolder.state)
    }

    /**
     * Re-anchors the ball to the saved (or default) position for the new
     * orientation whenever the device rotates, so it doesn't end up
     * off-screen or in an awkward spot carried over from the other
     * orientation.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val ball = ballView ?: return
        val params = ballParams ?: return
        hideMenu()
        val (newX, newY) = defaultOrSavedPosition()
        params.x = newX
        params.y = newY
        runCatching { windowManager.updateViewLayout(ball, params) }
    }

    private fun onBallTouch(event: MotionEvent): Boolean {
        val params = ballParams ?: return false
        val ball = ballView ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = params.x
                downParamY = params.y
                dragging = false
                longPressHandled = false
                // Fire as soon as the hold threshold is reached, instead of
                // waiting for the finger to lift - waiting until release felt
                // broken ("I'm holding it down and nothing is happening").
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                    dragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    hideMenu()
                }
                if (dragging) {
                    params.x = downParamX + dx.toInt()
                    params.y = downParamY + dy.toInt()
                    runCatching { windowManager.updateViewLayout(ball, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (dragging) {
                    snapToEdge(ball, params)
                } else if (!longPressHandled) {
                    // The long-press-to-stop case already fired (and asked
                    // for confirmation) from longPressRunnable while the
                    // finger was still down - only a plain short tap is left
                    // to handle here.
                    onBallTapped()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private val longPressRunnable = Runnable {
        if (dragging) return@Runnable
        if (RecorderStatusHolder.state == RecorderState.ACTIVE ||
            RecorderStatusHolder.state == RecorderState.PAUSED
        ) {
            longPressHandled = true
            ballView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            showStopConfirmationDialog()
        }
    }

    private fun snapToEdge(ball: ImageView, params: WindowManager.LayoutParams) {
        val screenWidth = resources.displayMetrics.widthPixels
        val ballSize = dpToPx(BALL_SIZE_DP)
        val targetX = if (params.x + ballSize / 2 < screenWidth / 2) {
            0
        } else {
            screenWidth - ballSize
        }
        savePosition(targetX, params.y)
        animateBallTo(ball, params, targetX, params.y)
    }

    private fun animateBallTo(
        ball: ImageView,
        params: WindowManager.LayoutParams,
        targetX: Int,
        targetY: Int
    ) {
        val startX = params.x
        val startY = params.y
        val steps = 8
        var step = 0
        val runnable = object : Runnable {
            override fun run() {
                step++
                val fraction = step.toFloat() / steps
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                runCatching { windowManager.updateViewLayout(ball, params) }
                if (step < steps) mainHandler.postDelayed(this, 12L)
            }
        }
        mainHandler.post(runnable)
    }

    private fun onBallTapped() {
        ballView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        when (RecorderStatusHolder.state) {
            RecorderState.IDLE -> toggleMenu()
            RecorderState.ACTIVE, RecorderState.PAUSED -> {
                sendRecorderAction(RecorderService.PAUSE_RESUME_ACTION)
            }
        }
    }

    /**
     * Shown as soon as the long-press threshold is reached while recording -
     * asks for confirmation before actually stopping, since a bare long
     * press with no feedback used to be too easy to trigger by accident (and
     * too easy to miss on purpose, since nothing ever indicated it existed).
     */
    private fun showStopConfirmationDialog() {
        val dialog = AlertDialog.Builder(
            ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        )
            .setMessage(getString(R.string.stop_recording_confirmation))
            .setPositiveButton(getString(R.string.stop)) { _, _ ->
                sendRecorderAction(RecorderService.STOP_ACTION)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        runCatching { dialog.show() }
    }

    private fun sendRecorderAction(action: String) {
        sendBroadcast(
            Intent(RecorderService.RECORDER_INTENT_ACTION)
                .putExtra(RecorderService.ACTION_EXTRA_KEY, action)
        )
    }

    private fun toggleMenu() {
        if (menuShown) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (menuShown) return
        val ballParams = ballParams ?: return
        val screenHeight = resources.displayMetrics.heightPixels
        val ballSize = dpToPx(BALL_SIZE_DP)
        val buttonSize = dpToPx(BUTTON_SIZE_DP)
        val gap = dpToPx(GAP_DP)
        // if the ball sits in the lower half of the screen, open the menu
        // upwards so it doesn't get pushed off-screen
        val expandUpwards = ballParams.y > screenHeight / 2

        audioButton = addMenuButton(
            iconRes = R.drawable.ic_audio,
            bgColor = AUDIO_BUTTON_COLOR,
            offsetIndex = 1,
            buttonSize = buttonSize,
            gap = gap,
            ballSize = ballSize,
            ballParams = ballParams,
            expandUpwards = expandUpwards
        ) { startRecording(RecorderType.AUDIO) }

        videoButton = addMenuButton(
            iconRes = R.drawable.ic_screen,
            bgColor = VIDEO_BUTTON_COLOR,
            offsetIndex = 2,
            buttonSize = buttonSize,
            gap = gap,
            ballSize = ballSize,
            ballParams = ballParams,
            expandUpwards = expandUpwards
        ) { startRecording(RecorderType.VIDEO) }

        closeButton = addMenuButton(
            iconRes = android.R.drawable.ic_menu_close_clear_cancel,
            bgColor = ContextCompat.getColor(this, android.R.color.darker_gray),
            offsetIndex = 3,
            buttonSize = buttonSize,
            gap = gap,
            ballSize = ballSize,
            ballParams = ballParams,
            expandUpwards = expandUpwards
        ) { closeBall() }

        menuShown = true
        mainHandler.postDelayed(autoHideMenuRunnable, MENU_AUTO_HIDE_MS)
    }

    private fun addMenuButton(
        iconRes: Int,
        bgColor: Int,
        offsetIndex: Int,
        buttonSize: Int,
        gap: Int,
        ballSize: Int,
        ballParams: WindowManager.LayoutParams,
        expandUpwards: Boolean,
        onClick: () -> Unit
    ): ImageView {
        val button = ImageView(this).apply {
            setImageResource(iconRes)
            setColorFilter(ContextCompat.getColor(context, android.R.color.white))
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(bgColor)
            }
            val pad = dpToPx(10)
            setPadding(pad, pad, pad, pad)
            setOnClickListener {
                onClick()
                hideMenu()
            }
        }
        val yOffset = (buttonSize + gap) * offsetIndex
        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ballParams.x + (ballSize - buttonSize) / 2
            y = if (expandUpwards) {
                ballParams.y - yOffset
            } else {
                ballParams.y + ballSize + yOffset - buttonSize
            }
        }
        runCatching { windowManager.addView(button, params) }
        return button
    }

    private fun hideMenu() {
        if (!menuShown) return
        mainHandler.removeCallbacks(autoHideMenuRunnable)
        audioButton?.let { runCatching { windowManager.removeView(it) } }
        videoButton?.let { runCatching { windowManager.removeView(it) } }
        closeButton?.let { runCatching { windowManager.removeView(it) } }
        audioButton = null
        videoButton = null
        closeButton = null
        menuShown = false
    }

    /**
     * Called from the menu's "close" button - hides the floating ball right
     * away without having to go back into the app's settings, and keeps the
     * settings screen / quick-settings tile in sync with the new state.
     */
    private fun closeBall() {
        Preferences.edit { putBoolean(Preferences.floatingBallKey, false) }
        FloatingBallTile.requestTileRefresh(this)
        stopSelf()
    }

    private fun startRecording(type: RecorderType) {
        // Reuses the very same "invisible start" path already used by the
        // quick-settings tiles: MainActivity does the permission/consent
        // dance (and, for video, the MediaProjection system dialog) and then
        // hides itself again - the app the user was in stays in front.
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ACTION_KEY, type.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun updateBallAppearance(state: RecorderState) {
        val ball = ballView ?: return
        val (bgColorRes, iconRes) = when (state) {
            RecorderState.IDLE ->
                android.R.color.darker_gray to android.R.drawable.ic_input_add
            RecorderState.ACTIVE ->
                android.R.color.holo_red_dark to android.R.drawable.ic_media_pause
            RecorderState.PAUSED ->
                android.R.color.holo_orange_dark to android.R.drawable.ic_media_play
        }
        ball.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(ContextCompat.getColor(this@OverlayBallService, bgColorRes))
        }
        ball.setImageResource(iconRes)
        ball.setColorFilter(ContextCompat.getColor(this@OverlayBallService, android.R.color.white))
        if (state == RecorderState.IDLE) hideMenu()
    }

    companion object {
        private const val BALL_SIZE_DP = 56
        private const val BUTTON_SIZE_DP = 56
        private const val GAP_DP = 8
        private const val TOUCH_SLOP_PX = 16
        private const val LONG_PRESS_MS = 550L
        private const val MENU_AUTO_HIDE_MS = 4000L

        // 录音按钮：绿色；录像按钮：蓝色 - 图标本身保持白色不变
        private val AUDIO_BUTTON_COLOR = Color.parseColor("#2E7D32")
        private val VIDEO_BUTTON_COLOR = Color.parseColor("#1565C0")
    }
}
