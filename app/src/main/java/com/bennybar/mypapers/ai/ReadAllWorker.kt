package com.bennybar.mypapers.ai

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.app
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Reads every unread paper with the AI, one after another, as one background job
 * with a progress notification. Each result is saved (and synced) as soon as it's
 * read, so an interrupted run loses nothing: the next run reads what's left.
 */
class ReadAllWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = AiNotifications.progress(applicationContext, NOTIFICATION, id, TITLE, null, null)

    override suspend fun doWork(): Result {
        // Not allowed from the background on newer Android: then it just runs without the notification.
        runCatching { setForeground(getForegroundInfo()) }
        // Everything (after an upgrade, to refresh titles and fields) or only what was never read.
        // A retried re-read skips what this run already read, so the key isn't charged twice.
        val since = inputData.getLong(SINCE, 0)
        val ids = if (inputData.getBoolean(ALL, false)) app.repository.readableIds(notReadSince = since) else app.repository.unreadIds()
        var failures = 0
        ids.forEachIndexed { i, id ->
            runCatching { setForeground(AiNotifications.progress(applicationContext, NOTIFICATION, this@ReadAllWorker.id, TITLE, null, null, i, ids.size)) }
            setProgress(workDataOf(DONE to i, TOTAL to ids.size))
            try {
                app.repository.markReading(id)
                app.repository.extract(id)
            } catch (e: AiException) {
                // A bad key stops the run; a busy OpenAI is retried later, keeping what's done.
                if (!e.retryable) {
                    app.repository.markExtractionFailed(id, e.message ?: "AI reading failed")
                    return Result.failure()
                }
                app.repository.markUnread(id)
                return if (runAttemptCount < 5) Result.retry() else Result.failure()
            } catch (e: java.io.IOException) {
                Log.w("ReadAll", "Network trouble on $id", e)
                app.repository.markUnread(id)
                return if (runAttemptCount < 5) Result.retry() else Result.failure()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    // Stopped (no network, the system's limits): back to unread, so the next run reads it.
                    withContext(NonCancellable) { app.repository.markUnread(id) }
                    throw e
                }
                failures++
                app.repository.markExtractionFailed(id, e.message ?: e.javaClass.simpleName)
            }
        }
        Log.i("ReadAll", "Read ${ids.size - failures} of ${ids.size} papers")
        return Result.success()
    }

    companion object {
        const val NAME = "ai-read-all"
        private const val TITLE = "Reading papers with AI"
        private const val NOTIFICATION = 2001
        private const val DONE = "done"
        private const val TOTAL = "total"
        private const val ALL = "all"
        private const val SINCE = "since"

        /** Stops it; what it was reading goes back to unread. */
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(NAME)

        /** [all]: re-read every paper, not only unread ones. */
        fun start(context: Context, all: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<ReadAllWorker>()
                .setInputData(workDataOf(ALL to all, SINCE to System.currentTimeMillis()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            // Already running: it picks up papers added meanwhile on its next run (KEEP), queued after it.
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        /** (done, total) while it runs; null when it isn't. */
        fun observeProgress(context: Context): Flow<Pair<Int, Int>?> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NAME).map { infos ->
                infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.progress?.let { p -> p.getInt(DONE, 0) to p.getInt(TOTAL, 0) }
            }
    }
}
