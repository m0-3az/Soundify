package com.soundify.app.cleanvideo

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.soundify.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CleanVideoService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Clean Video processing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Progress while music is removed from a video"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                @Suppress("DEPRECATION")
                val uri = intent.getParcelableExtra(EXTRA_URI) as? Uri
                val name = intent.getStringExtra(EXTRA_NAME)
                if (uri == null) {
                    // A startForegroundService() call must reach startForeground()
                    // before stopSelf(), even when the intent is malformed.
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(CleanVideoController.state.value, "Cancelled"),
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                val state = CleanVideoController.state.value
                startForeground(NOTIFICATION_ID, buildNotification(state, "Preparing…"))
                startJob(uri, name)
            }

            ACTION_CANCEL -> {
                CleanVideoController.markCancelling()
                job?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun startJob(uri: Uri, name: String?) {
        if (job?.isActive == true) return
        job = scope.launch {
            var lastNotification = 0L
            try {
                val result = StreamingCleaner(applicationContext).clean(uri, name) { progress ->
                    CleanVideoController.update(progress)
                    val now = SystemClock.elapsedRealtime()
                    if (progress.stage != CleanStage.SEPARATING || now - lastNotification >= 750L) {
                        lastNotification = now
                        notifications.notify(
                            NOTIFICATION_ID,
                            buildNotification(
                                CleanVideoController.state.value,
                                progress.detail,
                            ),
                        )
                    }
                }
                CleanVideoController.finish(result)
                notifyFinished("Clean video is ready", "Open Soundify to review the four stems.")
            } catch (c: CancellationException) {
                CleanVideoController.cancelled()
                notifyFinished(
                    "Clean video cancelled",
                    "Partial stems were removed. Your saved files were kept.",
                )
                throw c
            } catch (t: Throwable) {
                CleanVideoController.fail(t.message ?: "Clean Video failed")
                notifyFinished("Clean video failed", t.message ?: "Open Soundify for details.")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                job = null
            }
        }
    }

    private fun buildNotification(
        state: CleanVideoController.State,
        text: String,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("Cleaning video")
        .setContentText(text)
        .setProgress(100, (state.progress.coerceIn(0f, 1f) * 100).toInt(), state.progress <= 0f)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setContentIntent(openApp())
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Cancel",
            cancelAction(),
        )
        .build()

    private fun notifyFinished(title: String, text: String) {
        notifications.notify(
            FINISHED_NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(openApp())
                .build(),
        )
    }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_CLEAN_VIDEO, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelAction(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, CleanVideoService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    override fun onTimeout(startId: Int) {
        // Android 15 limits dataSync foreground services. Stop cleanly so the
        // worker can remove partial stems instead of being killed mid-write.
        CleanVideoController.markCancelling()
        job?.cancel()
        stopSelf(startId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.soundify.app.cleanvideo.START"
        const val ACTION_CANCEL = "com.soundify.app.cleanvideo.CANCEL"
        const val EXTRA_URI = "uri"
        const val EXTRA_NAME = "name"

        private const val CHANNEL_ID = "clean_video"
        private const val NOTIFICATION_ID = 41
        private const val FINISHED_NOTIFICATION_ID = 42
    }
}
