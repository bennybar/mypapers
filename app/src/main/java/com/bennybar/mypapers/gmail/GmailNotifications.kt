package com.bennybar.mypapers.gmail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.bennybar.mypapers.MainActivity
import com.bennybar.mypapers.R

/** The ongoing "Scanning Gmail…" notification (keeps the job alive) and the "found N papers" one. */
object GmailNotifications {
    private const val CHANNEL_PROGRESS = "gmail_progress"
    private const val CHANNEL_FOUND = "gmail_found"
    const val ID_PROGRESS = 1001
    private const val ID_FOUND = 1002

    /** Opens the Gmail review screen. */
    const val EXTRA_OPEN = "open"
    const val OPEN_GMAIL = "gmail"
    const val OPEN_SETTINGS = "settings"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Gmail scan progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_FOUND, "Papers found in Gmail", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun progress(context: Context, title: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .apply { if (total > 0) setContentText("$done of $total").setProgress(total, done, false) else setProgress(0, 0, true) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openGmail(context))
            .build()
        return ForegroundInfo(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** "3 new papers in Gmail" — tap to choose what to import. */
    fun found(context: Context, count: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (count == 1) "1 new paper in Gmail" else "$count new papers in Gmail")
            .setContentText("Tap to choose what to add")
            .setAutoCancel(true)
            .setContentIntent(openGmail(context))
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(ID_FOUND, n)
    }

    /** Google stopped accepting the app's Gmail access: connect again in Settings. */
    fun reconnect(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, CHANNEL_FOUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Reconnect Gmail")
            .setContentText("My Papers can't read Gmail anymore. Tap to connect again.")
            .setAutoCancel(true)
            .setContentIntent(open(context, OPEN_SETTINGS, 1))
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(ID_FOUND, n)
    }

    private fun openGmail(context: Context) = open(context, OPEN_GMAIL, 0)

    private fun open(context: Context, what: String, requestCode: Int) = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN, what).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
