package app.retranslate.domain.jobs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import app.retranslate.MainActivity
import app.retranslate.R
import java.util.UUID

/**
 * The job foreground notification (PLAN.md §7.4): book title,
 * "Chapter X of Y", a progress bar, and Pause / Cancel actions —
 * Pause goes through [JobActionReceiver] (cooperative, progress-safe),
 * Cancel through WorkManager's own cancel intent for this work.
 */
class JobNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Translation jobs",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Progress of background book translations"
                }
            )
        }
    }

    fun foregroundInfo(
        workId: UUID,
        jobRunId: Long,
        bookId: Long,
        bookTitle: String,
        done: Int,
        total: Int,
        currentChapter: Int
    ): ForegroundInfo {
        val notification = build(workId, bookId, bookTitle, done, total, currentChapter)
        val notificationId = (jobRunId * 31 + NOTIFICATION_BASE).toInt()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun build(
        workId: UUID,
        bookId: Long,
        bookTitle: String,
        done: Int,
        total: Int,
        currentChapter: Int
    ): Notification {
        val text = when {
            total <= 0 -> "Preparing…"
            currentChapter > 0 -> "Chapter $currentChapter of $total"
            else -> "$done of $total chapters"
        }
        val openApp = PendingIntent.getActivity(
            context,
            bookId.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getBroadcast(
            context,
            (bookId * 31 + 1).toInt(),
            Intent(JobActionReceiver.ACTION_PAUSE).apply {
                setPackage(context.packageName)
                putExtra(JobActionReceiver.EXTRA_BOOK_ID, bookId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_translate)
            .setContentTitle(bookTitle)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total <= 0)
            .setContentIntent(openApp)
            .addAction(0, "Pause", pauseIntent)
            .addAction(0, "Cancel", cancelIntent)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "translation_jobs"
        private const val NOTIFICATION_BASE = 10_000L
    }
}
