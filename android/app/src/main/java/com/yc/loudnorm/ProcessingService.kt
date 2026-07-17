package com.yc.loudnorm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 持有整批视频任务，让切换 App、返回桌面或锁屏后仍能可靠处理和通知。 */
class ProcessingService : Service() {

    data class TaskResult(
        val success: Boolean,
        val notificationText: String,
        val cancelled: Boolean = false,
    )

    companion object {
        private const val CHANNEL_ID = "video_processing"
        private const val COMPLETE_CHANNEL_ID = "video_processing_complete_v1"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.yc.loudnorm.START"
        private const val ACTION_CANCEL = "com.yc.loudnorm.CANCEL"
        private const val UPDATE_INTERVAL_MS = 1000L

        private data class PendingWork(
            val task: suspend CoroutineScope.() -> TaskResult,
            val cancelAction: () -> Unit,
        )

        private val taskLock = Any()
        private var pendingWork: PendingWork? = null

        @Volatile
        private var instance: ProcessingService? = null

        @Volatile
        var isRunning = false
            private set

        fun start(
            context: Context,
            cancelAction: () -> Unit,
            task: suspend CoroutineScope.() -> TaskResult,
        ) {
            synchronized(taskLock) {
                check(pendingWork == null && !isRunning) { "已有视频处理任务正在运行" }
                pendingWork = PendingWork(task, cancelAction)
            }
            try {
                val intent = Intent(context, ProcessingService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                synchronized(taskLock) { pendingWork = null }
                throw e
            }
        }

        fun cancel() {
            val running = instance
            if (running != null && isRunning) {
                running.requestCancel()
                return
            }
            synchronized(taskLock) { pendingWork?.cancelAction }?.invoke()
        }

        fun update(progress: Int, text: String) {
            instance?.enqueueProgress(progress, text)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotificationAt = 0L
    private var pendingProgress = 0
    private var pendingText = "准备处理…"
    private var updateScheduled = false
    @Volatile private var cancelAction: (() -> Unit)? = null

    private val flushProgress = Runnable {
        updateScheduled = false
        if (!isRunning) return@Runnable
        lastNotificationAt = SystemClock.elapsedRealtime()
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildProgressNotification(pendingProgress, pendingText),
        )
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val progressChannel = NotificationChannel(
            CHANNEL_ID, "视频处理进度", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "响度均衡的后台处理进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(progressChannel)

        val completeChannel = NotificationChannel(
            COMPLETE_CHANNEL_ID, "处理完成提醒", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "视频处理完成时弹出提醒"
            enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(completeChannel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestCancel()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || isRunning) return START_NOT_STICKY

        isRunning = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Loudnorm:Processing")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
        startForeground(NOTIFICATION_ID, buildProgressNotification(0, "准备处理…"))

        val work = synchronized(taskLock) {
            pendingWork.also { pendingWork = null }
        }
        if (work == null) {
            complete(TaskResult(false, "任务启动失败"))
            return START_NOT_STICKY
        }
        cancelAction = work.cancelAction

        serviceScope.launch {
            val result = try {
                work.task(this)
            } catch (e: Exception) {
                TaskResult(false, "处理异常：${e.message ?: "未知错误"}")
            }
            complete(result)
        }
        return START_NOT_STICKY
    }

    private fun requestCancel() {
        if (!isRunning) return
        pendingText = "正在取消并清理…"
        cancelAction?.invoke()
        enqueueProgress(pendingProgress, pendingText)
    }

    /** 合并高频进度，只向系统通知栏每秒提交一次最新值，避免系统丢弃更新。 */
    private fun enqueueProgress(progress: Int, text: String) {
        mainHandler.post {
            if (!isRunning) return@post
            pendingProgress = maxOf(pendingProgress, progress.coerceIn(0, 100))
            pendingText = text
            val elapsed = SystemClock.elapsedRealtime() - lastNotificationAt
            if (elapsed >= UPDATE_INTERVAL_MS) {
                mainHandler.removeCallbacks(flushProgress)
                flushProgress.run()
            } else if (!updateScheduled) {
                updateScheduled = true
                mainHandler.postDelayed(flushProgress, UPDATE_INTERVAL_MS - elapsed)
            }
        }
    }

    private fun complete(result: TaskResult) {
        if (!isRunning) return
        isRunning = false
        cancelAction = null
        mainHandler.removeCallbacks(flushProgress)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)

        if (!result.cancelled) {
            // 每批使用不同 ID，确保系统把它当作新通知而非更新上一次完成通知。
            val completionId = (System.currentTimeMillis() and 0x7fffffff).toInt()
            getSystemService(NotificationManager::class.java).notify(
                completionId,
                buildCompletionNotification(result.success, result.notificationText),
            )
        }
        stopSelf()
    }

    private fun buildProgressNotification(progress: Int, text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("响度均衡处理中")
            .setContentText(text)
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "取消",
                cancelIntent(),
            )
            .build()

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

    private fun contentIntent() = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent() = PendingIntent.getService(
        this,
        1,
        Intent(this, ProcessingService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    override fun onDestroy() {
        mainHandler.removeCallbacks(flushProgress)
        if (isRunning) cancelAction?.invoke()
        cancelAction = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        serviceScope.cancel()
        if (instance === this) instance = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
