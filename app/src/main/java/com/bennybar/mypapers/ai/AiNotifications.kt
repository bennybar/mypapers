package com.bennybar.mypapers.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID
import com.bennybar.mypapers.MainActivity
import com.bennybar.mypapers.R
import com.bennybar.mypapers.gmail.GmailNotifications
import com.bennybar.mypapers.reminders.ExpiryReminders

/**
 * The AI's background jobs: an ongoing notification while one runs (which also keeps Android
 * from stopping it), and one when it's done. Tapping opens the paper, or the app.
 */
object AiNotifications {
    private const val CHANNEL_PROGRESS = "ai_progress"
    private const val CHANNEL_DONE = "ai_done"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "AI at work", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "AI finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /**
     * The ongoing one, with Cancel (which stops the job [workId]); [paperId]: the paper it's about
     * (null: the app); [total] 0: no count.
     */
    fun progress(context: Context, notificationId: Int, workId: UUID, title: String, text: String?, paperId: String?, done: Int = 0, total: Int = 0): ForegroundInfo {
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply {
                if (total > 0) setContentText("$done of $total").setProgress(total, done, false)
                else setContentText(text).setProgress(0, 0, true)
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, paperId))
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(workId))
            .build()
        return ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    fun done(context: Context, notificationId: Int, title: String, text: String?, paperId: String?) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open(context, paperId))
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(notificationId, n)
    }

    private fun open(context: Context, paperId: String?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (paperId != null) intent.putExtra(GmailNotifications.EXTRA_OPEN, ExpiryReminders.OPEN_DOCUMENT).putExtra(ExpiryReminders.EXTRA_ID, paperId)
        return PendingIntent.getActivity(context, paperId?.hashCode() ?: 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
