package com.yc.loudnorm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 让视频处理在切换 App、返回桌面或锁屏后继续运行，并在通知栏显示进度。 */
class ProcessingService : Service() {

    companion object {
        private const val CHANNEL_ID = "video_processing"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.yc.loudnorm.START"
        @Volatile
        private var instance: ProcessingService? = null

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, ProcessingService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun update(progress: Int, text: String) {
            instance?.showProgress(progress, text)
        }

        fun finish(success: Boolean, text: String) {
            instance?.complete(success, text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val channel = NotificationChannel(
            CHANNEL_ID, "视频处理进度", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "响度均衡的后台处理进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                startForeground(NOTIFICATION_ID, buildNotification(0, "准备处理…", true))
            }
        }
        return START_NOT_STICKY
    }

    private fun showProgress(progress: Int, text: String) {
        if (!isRunning) return
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(progress.coerceIn(0, 100), text, true),
        )
    }

    private fun complete(success: Boolean, text: String) {
        isRunning = false
        stopForeground(STOP_FOREGROUND_DETACH)
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification(100, text.ifBlank { if (success) "处理完成" else "处理失败" }, false),
        )
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        isRunning = false
        super.onDestroy()
    }

    private fun buildNotification(progress: Int, text: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (ongoing) "响度均衡处理中" else "响度均衡")
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
