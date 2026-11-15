package com.bnyro.recorder.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.bnyro.recorder.R
import com.bnyro.recorder.enums.RecorderState
import com.bnyro.recorder.enums.RecorderType
import com.bnyro.recorder.receivers.FinishedNotificationReceiver
import com.bnyro.recorder.ui.MainActivity
import com.bnyro.recorder.util.NotificationHelper
import com.bnyro.recorder.util.PermissionHelper
import com.bnyro.recorder.util.RecorderStatusHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

abstract class RecorderService : LifecycleService() {
    abstract val notificationTitle: String

    /** 本服务负责的录制类型，用于区分状态并按类型响应停止广播 */
    abstract val recorderType: RecorderType

    private val binder = LocalBinder()
    var recorder: MediaRecorder? = null
    var fileDescriptor: ParcelFileDescriptor? = null
    var outputFile: DocumentFile? = null

    var onRecorderStateChanged: (RecorderState) -> Unit = {}
    open val fgServiceType: Int? = null

    var recorderState: RecorderState = RecorderState.IDLE

    private val recorderReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(ACTION_EXTRA_KEY) != STOP_ACTION) return
            // 悬浮球只停自己那一种录制；不带类型的（通知栏停止按钮）停所有录制
            val targetType = intent.getStringExtra(TYPE_EXTRA_KEY)
            if (targetType != null && targetType != recorderType.name) return
            onDestroy()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return binder
    }

    inner class LocalBinder : Binder() {
        // Return this instance of [BackgroundMode] so clients can call public methods
        fun getService(): RecorderService = this@RecorderService
    }

    override fun onCreate() {
        val notification = buildNotification()
        if (fgServiceType != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.RECORDING_NOTIFICATION_ID,
                notification.build(),
                fgServiceType!!
            )
        } else {
            startForeground(NotificationHelper.RECORDING_NOTIFICATION_ID, notification.build())
        }

        runCatching {
            unregisterReceiver(recorderReceiver)
        }
        ContextCompat.registerReceiver(this, recorderReceiver, IntentFilter(RECORDER_INTENT_ACTION), RECEIVER_EXPORTED)

        super.onCreate()
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val stopIntent = Intent(RECORDER_INTENT_ACTION).putExtra(ACTION_EXTRA_KEY, STOP_ACTION)
        val stopAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.stop),
            getPendingIntent(stopIntent, 2)
        )

        return NotificationCompat.Builder(
            this,
            NotificationHelper.RECORDING_NOTIFICATION_CHANNEL
        )
            .setContentTitle(notificationTitle)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(recorderState == RecorderState.ACTIVE)
            .addAction(stopAction.build())
            .setUsesChronometer(true)
            .setContentIntent(getActivityIntent())
    }

    @SuppressLint("MissingPermission")
    fun updateNotification() {
        if (!PermissionHelper.hasPermission(this, Manifest.permission.POST_NOTIFICATIONS)) return
        val notification = buildNotification().build()
        NotificationManagerCompat.from(this).notify(
            NotificationHelper.RECORDING_NOTIFICATION_ID,
            notification
        )
    }

    private fun getPendingIntent(intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    open fun start() {
        runCatching {
            recorderState = RecorderState.ACTIVE
            onRecorderStateChanged(recorderState)
            RecorderStatusHolder.updateState(recorderType, recorderState)
        }
        updateNotification()
    }

    /** 录制停止、文件描述符关闭后调用，用于内部音频合流等收尾工作 */
    protected open suspend fun onRecordingStopped() {}

    override fun onDestroy() {
        runCatching {
            recorderState = RecorderState.IDLE
            onRecorderStateChanged(recorderState)
            RecorderStatusHolder.updateState(recorderType, recorderState)
        }

        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.RECORDING_NOTIFICATION_ID)

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                recorder?.runCatching {
                    stop()
                    release()
                }
                recorder = null
                fileDescriptor?.close()
                runCatching { onRecordingStopped() }
            }

            createRecordingFinishedNotification()
            outputFile = null

            runCatching {
                unregisterReceiver(recorderReceiver)
            }

            ServiceCompat.stopForeground(this@RecorderService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()

            super.onDestroy()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecordingFinishedNotification() {
        if (outputFile == null) return

        val deleteAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.delete),
            getPendingIntent(
                Intent(this, FinishedNotificationReceiver::class.java).putExtra(
                    FILE_NAME_EXTRA_KEY,
                    outputFile?.name.toString()
                ).putExtra(ACTION_EXTRA_KEY, DELETE_ACTION),
                4
            )
        )
        val shareAction = NotificationCompat.Action.Builder(
            null,
            getString(R.string.share),
            getPendingIntent(
                Intent(this, FinishedNotificationReceiver::class.java).putExtra(
                    FILE_NAME_EXTRA_KEY,
                    outputFile?.name.toString()
                ).putExtra(ACTION_EXTRA_KEY, SHARE_ACTION),
                5
            )
        )

        val notification = NotificationCompat.Builder(
            this,
            NotificationHelper.RECORDING_FINISHED_N_CHANNEL
        )
            .setContentTitle(getString(R.string.recording_finished))
            .setContentText(outputFile?.name)
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(deleteAction.build())
            // / .addAction(shareAction.build())
            .setContentIntent(getActivityIntent())
            .setAutoCancel(true)

        NotificationManagerCompat.from(this)
            .notify(NotificationHelper.RECORDING_FINISHED_N_ID, notification.build())
    }

    private fun getActivityIntent(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            6,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    abstract fun getCurrentAmplitude(): Int?

    companion object {
        const val RECORDER_INTENT_ACTION = "com.bnyro.recorder.RECORDER_ACTION"
        const val FILE_NAME_EXTRA_KEY = "fileName"
        const val ACTION_EXTRA_KEY = "action"
        const val TYPE_EXTRA_KEY = "recorderType"
        const val START_DELAY_EXTRA_KEY = "startDelayMs"
        const val STOP_ACTION = "STOP"
        const val DELETE_ACTION = "DELETE"
        const val SHARE_ACTION = "SHARE"
    }
}
