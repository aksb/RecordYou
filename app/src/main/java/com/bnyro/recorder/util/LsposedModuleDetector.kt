package com.bnyro.recorder.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 探测 system_server 里的 LSPosed 模块是否真的注入生效。
 *
 * LSPosed 没有给普通 App 查询模块状态的接口，只能靠握手：App 广播一条
 * ping，模块收到后回一条 pong；超时没回就视为未生效（模块没装、没勾选
 * 系统框架作用域，或更新 APK 后还没重启手机）。
 *
 * 检测结果只用于界面状态展示和「长按停止」的警告判断，不参与运行时的
 * 按键拦截——拦截始终由总开关 + 录制状态决定，和这里完全解耦。
 * pong 接收器要求发送方持有 STATUS_BAR 权限（system_server 持有），
 * 避免第三方应用伪造探测结果影响警告显示。
 */
object LsposedModuleDetector {
    const val ACTION_PING = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_PING"
    const val ACTION_PONG = "com.bnyro.recorder.action.XPOSED_VOLUME_KEY_PONG"

    /** 只有系统（system_server）才能发送 pong */
    private const val PONG_SENDER_PERMISSION = "android.permission.STATUS_BAR"

    enum class Status { UNKNOWN, CHECKING, ACTIVE, INACTIVE }

    /** 最近一次检测结果，Compose 界面读取即可自动刷新 */
    var status by mutableStateOf(Status.UNKNOWN)
        private set

    private const val TIMEOUT_MS = 1_500L

    /** 刚确认过结果就不再重复发 ping，30 秒内的结论视为仍然可信 */
    private const val FRESH_MS = 30_000L

    @Volatile
    private var lastCheckedAt = 0L

    @Volatile
    private var lastActive = false

    private var seq = 0

    /**
     * 异步探测（界面用）：立即把状态置为 CHECKING，结果写回 status。
     * 有 30 秒新鲜度缓存，不会每次刷新界面都发广播。
     */
    fun detect(context: Context) {
        if (status == Status.CHECKING) return
        if (freshResult() != null) return
        val app = context.applicationContext
        val mySeq = nextSeq()
        status = Status.CHECKING
        Thread {
            val active = ping(app)
            if (isLatest(mySeq)) record(active)
        }.start()
    }

    /**
     * 阻塞探测（后台线程用）：最多等待 1.5 秒返回模块是否生效。
     * 结果同样写回 status；旧结果若已被更新的探测取代则丢弃。
     */
    fun detectBlocking(context: Context): Boolean {
        freshResult()?.let { return it }
        val app = context.applicationContext
        val mySeq = nextSeq()
        status = Status.CHECKING
        val active = ping(app)
        if (isLatest(mySeq)) record(active)
        return active
    }

    /** 30 秒内的结果直接复用，返回 null 表示需要重新探测 */
    private fun freshResult(): Boolean? {
        if (SystemClock.elapsedRealtime() - lastCheckedAt >= FRESH_MS) return null
        return lastActive
    }

    private fun record(active: Boolean) {
        lastActive = active
        lastCheckedAt = SystemClock.elapsedRealtime()
        status = if (active) Status.ACTIVE else Status.INACTIVE
    }

    @Synchronized
    private fun nextSeq(): Int = ++seq

    @Synchronized
    private fun isLatest(mySeq: Int): Boolean = mySeq == seq

    /** 阻塞式 ping，必须在后台线程调用；接收器随结果立即注销，不长期持有 */
    private fun ping(context: Context): Boolean {
        val latch = CountDownLatch(1)
        var active = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_PONG) {
                    active = true
                    latch.countDown()
                }
            }
        }
        val registered = runCatching {
            val filter = IntentFilter(ACTION_PONG)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    filter,
                    PONG_SENDER_PERMISSION,
                    null,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(receiver, filter, PONG_SENDER_PERMISSION, null)
            }
            true
        }.getOrDefault(false)
        if (!registered) return false

        return try {
            context.sendBroadcast(Intent(ACTION_PING))
            runCatching { latch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
            active
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
