package com.yc.loudnorm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/** 让视频处理在切换 App、返回桌面或锁屏后继续运行，并在通知栏显示进度。 */
class ProcessingService : Service() {

    companion object {
        private const val CHANNEL_ID = "video_processing"
        private const val COMPLETE_CHANNEL_ID = "video_processing_complete_v1"
        private const val NOTIFICATION_ID = 1001
        private const val COMPLETE_NOTIFICATION_ID = 1002
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
        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID, "处理完成提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "视频处理完成时弹出提醒"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(completeChannel)
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                isRunning = true
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loudnorm:Processing")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java).notify(
            COMPLETE_NOTIFICATION_ID,
            buildCompletionNotification(success, text),
        )
        stopSelf()
    }

    private fun buildCompletionNotification(success: Boolean, text: String) =
        NotificationCompat.Builder(this, COMPLETE_CHANNEL_ID)
            .setSmallIcon(
                if (success) android.R.drawable.stat_sys_upload_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle(if (success) "视频处理完成" else "视频处理失败")
            .setContentText(text.ifBlank { if (success) "成品已保存到相册" else "请打开 App 查看日志" })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .build()

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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
            .setContentIntent(contentIntent())
            .build()

    private fun contentIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onBind(intent: Intent?): IBinder? = null
}
