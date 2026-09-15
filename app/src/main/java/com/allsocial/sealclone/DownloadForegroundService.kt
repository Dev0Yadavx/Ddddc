package com.allsocial.sealclone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground Service that handles active media downloads in the background,
 * posting real-time notifications with progress bars and status updates.
 */
class DownloadForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private var lastNotificationUpdateTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val format = intent.getStringExtra(EXTRA_FORMAT) ?: "best"
                val isAudio = intent.getBooleanExtra(EXTRA_IS_AUDIO, false)
                val qualityLabel = intent.getStringExtra(EXTRA_QUALITY_LABEL) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Downloading media"
                val thumb = intent.getStringExtra(EXTRA_THUMB) ?: ""

                startDownloadTask(taskId, url, format, isAudio, qualityLabel, title, thumb)
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: return START_NOT_STICKY
                cancelDownloadTask(taskId)
            }
        }

        return START_NOT_STICKY
    }

    private fun startDownloadTask(
        taskId: String,
        url: String,
        format: String,
        isAudio: Boolean,
        qualityLabel: String,
        title: String,
        thumb: String
    ) {
        // Promote service to foreground immediately with initial notification
        startInForeground(taskId, title, 0f, "Starting download...")

        val job = serviceScope.launch {
            _activeTasksFlow.value = _activeTasksFlow.value + (taskId to ActiveDownloadTask(taskId, title, 0f, "Starting..."))

            try {
                var lastProgress = 0f
                val savedFile = DownloaderBridge.executeDownload(
                    context = applicationContext,
                    targetUrl = url,
                    formatSpec = format,
                    isAudioOnly = isAudio,
                    audioBitrate = if (isAudio) qualityLabel else null,
                    processId = taskId
                ) { progress, speedLine ->
                    val now = System.currentTimeMillis()
                    // Throttle notification updates to avoid flooding Android system
                    val progressInt = progress.toInt()
                    val lastInt = lastProgress.toInt()
                    val shouldUpdateNotification = (now - lastNotificationUpdateTime > 400) || (progressInt != lastInt)

                    lastProgress = progress
                    val cleanSpeed = extractSpeed(speedLine)

                    // Update UI state flow
                    val currentTasks = _activeTasksFlow.value.toMutableMap()
                    currentTasks[taskId] = ActiveDownloadTask(taskId, title, progress, cleanSpeed)
                    _activeTasksFlow.value = currentTasks

                    if (shouldUpdateNotification) {
                        lastNotificationUpdateTime = now
                        val status = if (cleanSpeed.isNotBlank()) "$progressInt% • $cleanSpeed" else "$progressInt%"
                        updateForegroundNotification(taskId, title, progress, status)
                    }
                }

                // Download Finished
                val mb = "${(savedFile.length() / (1024 * 1024))} MB"
                val record = DownloadedRecord(
                    title = title,
                    filePath = savedFile.absolutePath,
                    thumbnail = thumb,
                    quality = if (isAudio) "$qualityLabel MP3" else qualityLabel,
                    ext = if (isAudio) "mp3" else "mp4",
                    fileSize = mb
                )

                // Rescan MediaStore so file is ready in Downloads tab
                MediaStoreHelper.scanDownloadDirectory(applicationContext)

                // Emit completion event for ViewModel/UI
                _downloadEvents.emit(
                    DownloadEvent(
                        taskId = taskId,
                        title = title,
                        isSuccess = true,
                        record = record,
                        savedFile = savedFile
                    )
                )

                showCompletionNotification(taskId, title, savedFile)

            } catch (e: CancellationException) {
                Log.d(TAG, "Download cancelled: $taskId")
                _downloadEvents.emit(
                    DownloadEvent(
                        taskId = taskId,
                        title = title,
                        isSuccess = false,
                        error = "Cancelled"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download error: ${e.message}", e)
                showFailureNotification(taskId, title, e.message ?: "Download failed")
                _downloadEvents.emit(
                    DownloadEvent(
                        taskId = taskId,
                        title = title,
                        isSuccess = false,
                        error = e.message ?: "Download failed"
                    )
                )
            } finally {
                activeJobs.remove(taskId)
                val currentTasks = _activeTasksFlow.value.toMutableMap()
                currentTasks.remove(taskId)
                _activeTasksFlow.value = currentTasks

                // If no more downloads running, stop foreground service
                if (activeJobs.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    // Update notification with next active download
                    val next = _activeTasksFlow.value.values.firstOrNull()
                    if (next != null) {
                        updateForegroundNotification(next.id, next.title, next.progress, next.speed)
                    }
                }
            }
        }

        activeJobs[taskId] = job
    }

    private fun cancelDownloadTask(taskId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(taskId)
        } catch (_: Exception) {}

        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)

        val currentTasks = _activeTasksFlow.value.toMutableMap()
        currentTasks.remove(taskId)
        _activeTasksFlow.value = currentTasks

        if (activeJobs.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startInForeground(taskId: String, title: String, progress: Float, status: String) {
        val notification = buildDownloadNotification(taskId, title, progress, status)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
        }
    }

    private fun updateForegroundNotification(taskId: String, title: String, progress: Float, status: String) {
        val notification = buildDownloadNotification(taskId, title, progress, status)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildDownloadNotification(
        taskId: String,
        title: String,
        progress: Float,
        statusText: String
    ): Notification {
        // Tap notification to return to the app's Tasks tab
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "TASKS")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Cancel action
        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL_DOWNLOAD
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            taskId.hashCode(),
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isIndeterminate = progress <= 0f || progress > 100f

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(statusText.ifBlank { "Downloading..." })
            .setContentIntent(contentPendingIntent)
            .setProgress(100, progress.toInt().coerceIn(0, 100), isIndeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel",
                cancelPendingIntent
            )

        return builder.build()
    }

    private fun showCompletionNotification(taskId: String, title: String, file: File) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("OPEN_TAB", "DOWNLOADS")
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            taskId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("$title (${formatFileSize(file.length())})")
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationId = (System.currentTimeMillis() % 10000).toInt() + 2000
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    private fun showFailureNotification(taskId: String, title: String, error: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("$title • $error")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationId = (System.currentTimeMillis() % 10000).toInt() + 3000
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows real-time status and progress for media downloads"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun extractSpeed(line: String): String {
        if (line.isBlank()) return ""
        val speedMatch = Regex("""(\d+(\.\d+)?\s*[kKMmGg]i?B/s)""").find(line)
        val etaMatch = Regex("""(ETA\s+\d{2}:\d{2})""").find(line)

        val speed = speedMatch?.value ?: ""
        val eta = etaMatch?.value ?: ""

        return when {
            speed.isNotEmpty() && eta.isNotEmpty() -> "$speed • $eta"
            speed.isNotEmpty() -> speed
            eta.isNotEmpty() -> eta
            else -> line.take(25)
        }
    }

    private fun formatFileSize(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1.0) "%.1f MB".format(mb) else "%.0f KB".format(bytes / 1024.0)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "DownloadFGService"
        const val CHANNEL_ID = "seal_download_channel"
        const val CHANNEL_NAME = "Ongoing Downloads"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_DOWNLOAD = "com.allsocial.sealclone.action.START_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.allsocial.sealclone.action.CANCEL_DOWNLOAD"

        const val EXTRA_TASK_ID = "extra_task_id"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FORMAT = "extra_format"
        const val EXTRA_IS_AUDIO = "extra_is_audio"
        const val EXTRA_QUALITY_LABEL = "extra_quality_label"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_THUMB = "extra_thumb"

        // Global state flow of active download tasks for seamless Compose observation
        private val _activeTasksFlow = MutableStateFlow<Map<String, ActiveDownloadTask>>(emptyMap())
        val activeTasksFlow = _activeTasksFlow.asStateFlow()

        // Global shared flow for download events (completion, error)
        private val _downloadEvents = MutableSharedFlow<DownloadEvent>(extraBufferCapacity = 64)
        val downloadEvents = _downloadEvents.asSharedFlow()

        data class DownloadEvent(
            val taskId: String,
            val title: String,
            val isSuccess: Boolean,
            val record: DownloadedRecord? = null,
            val savedFile: File? = null,
            val error: String? = null
        )

        /**
         * Helper function to start a download via Foreground Service.
         */
        fun enqueueDownload(
            context: Context,
            taskId: String,
            url: String,
            format: String,
            isAudio: Boolean,
            qualityLabel: String,
            title: String,
            thumb: String
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_TASK_ID, taskId)
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FORMAT, format)
                putExtra(EXTRA_IS_AUDIO, isAudio)
                putExtra(EXTRA_QUALITY_LABEL, qualityLabel)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_THUMB, thumb)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /**
         * Helper function to cancel an active download.
         */
        fun cancelDownload(context: Context, taskId: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}
