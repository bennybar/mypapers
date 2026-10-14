package com.bennybar.mypapers.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.MainActivity
import com.bennybar.mypapers.R
import com.bennybar.mypapers.app
import com.bennybar.mypapers.feature.documents.title
import com.bennybar.mypapers.gmail.GmailNotifications
import com.bennybar.mypapers.reminders.ExpiryReminders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * "Extract all text" for one paper, in the background (it can take minutes): an ongoing
 * notification while the AI writes the paper out, then one saying it's done (tap to open it).
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id get() = inputData.getString(KEY_ID).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = app.repository.get(id)?.title() ?: "a paper"
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Extracting all text")
            .setContentText(title)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPaper(applicationContext, id))
            .build()
        return ForegroundInfo(id.hashCode(), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    override suspend fun doWork(): Result {
        // Not allowed from the background on newer Android: then it just runs without the notification.
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            val text = app.repository.transcribe(id)
            done(if (text.isEmpty()) "No text found" else "Text extracted", null)
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            done("Couldn't extract the text", e.message)
            Result.failure()
        } catch (e: java.io.IOException) {
            if (runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            done("Couldn't extract the text", e.message)
            Result.failure()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            done("Couldn't extract the text", e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    private suspend fun done(title: String, error: String?) {
        val context = applicationContext
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val paper = app.repository.get(id)?.title() ?: ""
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(error ?: paper)
            .setAutoCancel(true)
            .setContentIntent(openPaper(context, id))
            .build()
        @Suppress("MissingPermission") // checked above
        NotificationManagerCompat.from(context).notify(id.hashCode() + 1, n)
    }

    companion object {
        private const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_PROGRESS = "ai_progress"
        private const val CHANNEL_DONE = "ai_done"

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "AI at work", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "AI finished", NotificationManager.IMPORTANCE_DEFAULT))
        }

        private fun name(id: String) = "transcribe-$id"

        /** Already running for this paper: left to finish (not charged twice). */
        fun start(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, request)
        }

        /** True while it's queued or running for this paper. */
        fun observeRunning(context: Context, id: String): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(id))
                .map { infos -> infos.any { !it.state.isFinished } }

        private fun openPaper(context: Context, id: String) = PendingIntent.getActivity(
            context, id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(GmailNotifications.EXTRA_OPEN, ExpiryReminders.OPEN_DOCUMENT)
                .putExtra(ExpiryReminders.EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
