package com.bnyro.recorder.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 摇一摇 / 翻转（屏幕朝下）停止录制的传感器监听。
 *
 * 只在录制进行期间注册（由 [com.bnyro.recorder.services.RecorderService]
 * 在开始时挂上、收尾时释放），非录制状态完全不占用传感器，避免耗电和误触。
 *
 * 摇一摇：先低通滤波分离重力，再用线加速度幅度判断；需要在短时间内出现
 * 多次明显的加速度峰值才算摇动，少一次抖动不会触发。
 *
 * 翻转：只认“从非屏幕朝下变成屏幕朝下并保持一小段时间”，所以开始录制时
 * 手机本来就朝下不会立刻停止；灵敏度只影响判定为“屏幕朝下”的角度阈值。
 */
class MotionStopDetector(
    context: Context,
    private val onTriggered: () -> Unit
) : SensorEventListener {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val sensitivity = Preferences.prefs
        .getInt(Preferences.motionStopSensitivityKey, Preferences.motionStopSensitivityDefault)
        .coerceIn(1, MAX_SENSITIVITY)

    // 线加速度峰值阈值（m/s²），灵敏度越高阈值越低
    private val shakeThreshold = SHAKE_THRESHOLDS[sensitivity - 1]

    // 判定“屏幕朝下”时 -Z 轴上需要达到的重力比例
    private val faceDownThreshold = FACE_DOWN_THRESHOLDS[sensitivity - 1]

    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private var shakeHits = 0
    private var shakeWindowStart = 0L
    private var lastShakeHit = 0L

    private var faceDownSince = 0L

    // 本次录制期间是否出现过“非屏幕朝下”：一开始就朝下时不能直接判定为翻转
    private var seenFaceUp = false

    private var triggered = false
    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 刚点开始录制时手上还在动，先静默一段时间
    private var armAtElapsed = 0L

    fun start() {
        if (registered || triggered || accelerometer == null) return
        gravityInitialized = false
        shakeHits = 0
        faceDownSince = 0L
        seenFaceUp = false
        armAtElapsed = SystemClock.elapsedRealtime() + ARM_DELAY_MS
        registered = sensorManager?.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_GAME
        ) == true
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { sensorManager?.unregisterListener(this) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (!gravityInitialized) {
            // 第一帧直接作为重力初值，避免滤波从 0 起步时产生一个假的巨大加速度
            gravity[0] = x
            gravity[1] = y
            gravity[2] = z
            gravityInitialized = true
        } else {
            for (i in 0..2) {
                gravity[i] = GRAVITY_ALPHA * gravity[i] + (1 - GRAVITY_ALPHA) * event.values[i]
            }
        }

        val now = SystemClock.elapsedRealtime()
        // 静默期内只更新重力估计，不做判定
        if (now < armAtElapsed) return

        detectShake(x, y, z, now)
        detectFlip(z, now)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun detectShake(x: Float, y: Float, z: Float, now: Long) {
        val dx = x - gravity[0]
        val dy = y - gravity[1]
        val dz = z - gravity[2]
        if (sqrt(dx * dx + dy * dy + dz * dz) < shakeThreshold) return

        // 同一个峰值会被连续采样到很多次，按最小间隔去重
        if (now - lastShakeHit < SHAKE_MIN_INTERVAL_MS) return
        lastShakeHit = now

        if (shakeHits == 0 || now - shakeWindowStart > SHAKE_WINDOW_MS) {
            shakeHits = 1
            shakeWindowStart = now
        } else {
            shakeHits++
        }
        if (shakeHits >= SHAKE_HITS) trigger()
    }

    private fun detectFlip(z: Float, now: Long) {
        val faceDown = z < -faceDownThreshold * SensorManager.GRAVITY_EARTH
        if (!faceDown) {
            seenFaceUp = true
            faceDownSince = 0L
            return
        }
        if (!seenFaceUp) return
        if (faceDownSince == 0L) {
            faceDownSince = now
            return
        }
        // 保持朝下一段时间才算真的翻转，避免只是甩了一下手机
        if (now - faceDownSince >= FLIP_STABLE_MS) trigger()
    }

    private fun trigger() {
        if (triggered) return
        triggered = true
        // 停止录制会走广播，统一回到主线程再处理
        mainHandler.post {
            stop()
            onTriggered()
        }
    }

    companion object {
        const val MAX_SENSITIVITY = 5

        // 灵敏度 1..5 对应的线加速度峰值阈值（m/s²）
        private val SHAKE_THRESHOLDS = floatArrayOf(20f, 17f, 14f, 11f, 9f)

        // 灵敏度 1..5 对应的 -Z 重力比例阈值
        private val FACE_DOWN_THRESHOLDS = floatArrayOf(0.85f, 0.8f, 0.75f, 0.7f, 0.6f)

        private const val SHAKE_HITS = 3
        private const val SHAKE_WINDOW_MS = 1200L
        private const val SHAKE_MIN_INTERVAL_MS = 120L
        private const val FLIP_STABLE_MS = 400L
        private const val ARM_DELAY_MS = 1500L
        private const val GRAVITY_ALPHA = 0.8f
    }
}
