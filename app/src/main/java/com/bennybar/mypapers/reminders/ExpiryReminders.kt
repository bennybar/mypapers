package com.bennybar.mypapers.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bennybar.mypapers.MainActivity
import com.bennybar.mypapers.R
import com.bennybar.mypapers.ai.ExtractionPrompt
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Expiry
import com.bennybar.mypapers.feature.documents.title
import com.bennybar.mypapers.gmail.GmailNotifications
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * A notification 30 days and 7 days before a paper expires (see [Expiry]), once each.
 * Checked once a day; tapping it opens the paper.
 */
object ExpiryReminders {
    private const val CHANNEL = "expiry"
    const val OPEN_DOCUMENT = "document"
    const val EXTRA_ID = "id"

    /** Days before the expiry to remind at; each is sent once per paper and date. */
    private val WHEN = listOf(30L, 7L)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Expiring papers", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ExpiryWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("expiry-reminders", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    suspend fun check(context: Context) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val sent = prefs.getStringSet("sent", emptySet())!!.toMutableSet()
        val today = LocalDate.now()
        val dayFirst = ExtractionPrompt.dayFirst(Format.locale)
        app.repository.observeAll().first().forEach { doc ->
            val expires = Expiry.date(doc, dayFirst) ?: return@forEach
            val left = Expiry.daysLeft(expires, today)
            if (left < 0) return@forEach
            // The nearest reminder that's due (7 days left: the 7-day one, not the 30-day one too).
            val due = WHEN.filter { left <= it }.minOrNull() ?: return@forEach
            val key = "${doc.id}|$expires|$due"
            if (!sent.add(key)) return@forEach
            notify(context, doc.id, doc.title(), left, expires)
        }
        prefs.edit { putStringSet("sent", sent) }
    }

    private fun notify(context: Context, id: String, title: String, left: Long, expires: LocalDate) {
        val text = when (left) {
            0L -> "Expires today"
            1L -> "Expires tomorrow"
            else -> "Expires in $left days, on ${Format.date(expires.toString())}"
        }
        val open = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(GmailNotifications.EXTRA_OPEN, OPEN_DOCUMENT)
                .putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        @Suppress("MissingPermission") // checked in check()
        NotificationManagerCompat.from(context).notify(id.hashCode(), n)
    }
}

class ExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ExpiryReminders.check(applicationContext)
        return Result.success()
    }
}
