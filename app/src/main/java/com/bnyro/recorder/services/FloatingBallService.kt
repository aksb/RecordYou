package com.bnyro.recorder.services

import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.ui.MainActivity
import com.bnyro.recorder.ui.QuickActionActivity
import com.bnyro.recorder.util.Preferences
import com.bnyro.recorder.util.RecorderStatusHolder
import kotlin.math.abs

/**
 * A small draggable floating ball, shown on top of every other app, that starts
 * and stops its own [recorderType] without switching back into RecordYou.
 *
 * - Tap while idle -> starts a recording of [recorderType].
 * - Tap while that recording is running -> stops it.
 * - Drag -> moves the ball anywhere on screen, kept within the screen bounds.
 * - Long press -> intentionally does nothing, so it can never be triggered by
 *   accident while trying to drag the ball.
 *
 * Requires the "display over other apps" permission (SYSTEM_ALERT_WINDOW),
 * which must already be granted before this service is started - see
 * [com.bnyro.recorder.util.FloatingBallHelper].
 */
@RequiresApi(Build.VERSION_CODES.O)
abstract class FloatingBallService : Service() {
    protected abstract val recorderType: RecorderType

    @get:DrawableRes
    protected abstract val idleIconRes: Int

    /** 空闲状态下的球底色：录音球绿色，录屏球蓝色 */
    @get:ColorInt
    protected abstract val idleColor: Int

    /** 录制结束（无论从球、通知栏还是应用内停止）时弹出的提示 */
    @get:StringRes
    protected abstract val stoppedMessageRes: Int

    protected abstract val positionKeys: PositionKeys

    /**
     * When both balls have never been moved, their default position would be
     * identical - so the video ball is placed one ball further down.
     */
    protected abstract val defaultSlot: Int

    data class PositionKeys(
        val portraitX: String,
        val portraitY: String,
        val landscapeX: String,
        val landscapeY: String
    )

    private lateinit var windowManager: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var ballView: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var dragging = false
    private var wasRecording = false

    private val stateListener: (RecorderType, RecorderState) -> Unit = { type, state ->
        if (type == recorderType) {
            // 录制刚结束时报一声，否则球只是悄悄变回空闲色，很难察觉
            if (state == RecorderState.IDLE && wasRecording) {
                mainHandler.post {
                    Toast.makeText(this, stoppedMessageRes, Toast.LENGTH_SHORT).show()
                }
            }
            wasRecording = state == RecorderState.ACTIVE
            mainHandler.post { updateBallAppearance() }
        }
    }

    // 设置里的“录屏时隐藏悬浮球”是随时可改的，运行中的球要跟着生效
    private val secureFlagListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == Preferences.hideBallInRecordingsKey) mainHandler.post { updateSecureFlag() }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        addBall()
        // Reports the current state immediately, so the ball looks right even
        // if a recording was already running before the ball was turned on.
        RecorderStatusHolder.addListener(stateListener)
        Preferences.prefs.registerOnSharedPreferenceChangeListener(secureFlagListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        RecorderStatusHolder.removeListener(stateListener)
        Preferences.prefs.unregisterOnSharedPreferenceChangeListener(secureFlagListener)
        mainHandler.removeCallbacksAndMessages(null)
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
     * position if nothing was saved yet, or if the saved position no longer
     * fits the screen.
     */
    private fun defaultOrSavedPosition(): Pair<Int, Int> {
        val size = dpToPx(BALL_SIZE_DP)
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val maxX = (screenWidth - size).coerceAtLeast(0)
        val maxY = (screenHeight - size).coerceAtLeast(0)

        val xKey = if (isLandscape()) positionKeys.landscapeX else positionKeys.portraitX
        val yKey = if (isLandscape()) positionKeys.landscapeY else positionKeys.portraitY
        val savedX = Preferences.prefs.getInt(xKey, Int.MIN_VALUE)
        val savedY = Preferences.prefs.getInt(yKey, Int.MIN_VALUE)

        val defaultY = (screenHeight / 3 + defaultSlot * (size + dpToPx(GAP_DP)))
            .coerceIn(0, maxY)
        return if (savedX != Int.MIN_VALUE && savedY != Int.MIN_VALUE) {
            savedX.coerceIn(0, maxX) to savedY.coerceIn(0, maxY)
        } else {
            0 to defaultY
        }
    }

    private fun savePosition(x: Int, y: Int) {
        val xKey = if (isLandscape()) positionKeys.landscapeX else positionKeys.portraitX
        val yKey = if (isLandscape()) positionKeys.landscapeY else positionKeys.portraitY
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
            windowFlags(),
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
        updateBallAppearance()
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
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > TOUCH_SLOP_PX || abs(dy) > TOUCH_SLOP_PX)) {
                    dragging = true
                }
                if (dragging) {
                    // 允许拖动到任意位置，但不能超出屏幕
                    val (x, y) = clampToScreen(downParamX + dx.toInt(), downParamY + dy.toInt())
                    params.x = x
                    params.y = y
                    runCatching { windowManager.updateViewLayout(ball, params) }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val (x, y) = clampToScreen(params.x, params.y)
                    params.x = x
                    params.y = y
                    runCatching { windowManager.updateViewLayout(ball, params) }
                    savePosition(x, y)
                } else {
                    onBallTapped()
                }
                dragging = false
                return true
            }
        }
        return false
    }

    private fun clampToScreen(x: Int, y: Int): Pair<Int, Int> {
        val ballSize = dpToPx(BALL_SIZE_DP)
        val maxX = (resources.displayMetrics.widthPixels - ballSize).coerceAtLeast(0)
        val maxY = (resources.displayMetrics.heightPixels - ballSize).coerceAtLeast(0)
        return x.coerceIn(0, maxX) to y.coerceIn(0, maxY)
    }

    private fun onBallTapped() {
        ballView?.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val otherType = if (recorderType == RecorderType.AUDIO) RecorderType.VIDEO else RecorderType.AUDIO
        when {
            RecorderStatusHolder.stateOf(recorderType) == RecorderState.ACTIVE -> stopRecording()
            RecorderStatusHolder.stateOf(otherType) == RecorderState.ACTIVE ->
                Toast.makeText(this, R.string.floating_ball_other_recording, Toast.LENGTH_SHORT)
                    .show()

            else -> startRecording()
        }
    }

    private fun stopRecording() {
        // 带上类型，只停本球对应的那种录制
        sendBroadcast(
            Intent(RecorderService.RECORDER_INTENT_ACTION)
                .putExtra(RecorderService.ACTION_EXTRA_KEY, RecorderService.STOP_ACTION)
                .putExtra(RecorderService.TYPE_EXTRA_KEY, recorderType.name)
        )
    }

    private fun startRecording() {
        // 走 QuickActionActivity 这个透明入口：它完成权限/授权流程后直接开始
        // 录制并退回后台，不会像 MainActivity 那样先画一帧启动画面。
        val intent = Intent(this, QuickActionActivity::class.java)
            .putExtra(MainActivity.EXTRA_ACTION_KEY, recorderType.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    /**
     * 由设置项“录屏时隐藏悬浮球”控制，默认关闭：
     * Android 14+/MIUI 会把 secure 图层涂成黑块，默认开启反而更奇怪。
     */
    private fun hideInRecordings(): Boolean =
        Preferences.prefs.getBoolean(Preferences.hideBallInRecordingsKey, false)

    /**
     * FLAG_SECURE 会让系统在合成屏幕录制/截图时跳过这个悬浮窗：
     * 屏幕上照常可见、可拖动、可点击，但 MediaProjection 录不到它。
     */
    private fun windowFlags(): Int =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (hideInRecordings()) WindowManager.LayoutParams.FLAG_SECURE else 0)

    private fun updateSecureFlag() {
        val ball = ballView ?: return
        val params = ballParams ?: return
        val newFlags = if (hideInRecordings()) {
            params.flags or WindowManager.LayoutParams.FLAG_SECURE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
        }
        if (newFlags == params.flags) return
        params.flags = newFlags
        runCatching { windowManager.updateViewLayout(ball, params) }
    }

    private fun updateBallAppearance() {
        val ball = ballView ?: return
        val recording = RecorderStatusHolder.stateOf(recorderType) == RecorderState.ACTIVE
        ball.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (recording) {
                    ContextCompat.getColor(this@FloatingBallService, android.R.color.holo_red_dark)
                } else {
                    idleColor
                }
            )
        }
        ball.setImageResource(if (recording) R.drawable.ic_stop_square else idleIconRes)
        ball.setColorFilter(ContextCompat.getColor(this@FloatingBallService, android.R.color.white))
    }

    companion object {
        private const val BALL_SIZE_DP = 56
        private const val GAP_DP = 12
        private const val TOUCH_SLOP_PX = 16
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class AudioBallService : FloatingBallService() {
    override val recorderType = RecorderType.AUDIO
    override val idleIconRes = R.drawable.ic_audio
    override val idleColor = Color.parseColor("#2E7D32")
    override val stoppedMessageRes = R.string.toast_audio_stopped
    override val defaultSlot = 0
    override val positionKeys = PositionKeys(
        Preferences.audioBallPosXPortraitKey,
        Preferences.audioBallPosYPortraitKey,
        Preferences.audioBallPosXLandscapeKey,
        Preferences.audioBallPosYLandscapeKey
    )
}

@RequiresApi(Build.VERSION_CODES.O)
class VideoBallService : FloatingBallService() {
    override val recorderType = RecorderType.VIDEO
    override val idleIconRes = R.drawable.ic_screen
    override val idleColor = Color.parseColor("#1565C0")
    override val stoppedMessageRes = R.string.toast_screen_stopped
    override val defaultSlot = 1
    override val positionKeys = PositionKeys(
        Preferences.videoBallPosXPortraitKey,
        Preferences.videoBallPosYPortraitKey,
        Preferences.videoBallPosXLandscapeKey,
        Preferences.videoBallPosYLandscapeKey
    )
}
