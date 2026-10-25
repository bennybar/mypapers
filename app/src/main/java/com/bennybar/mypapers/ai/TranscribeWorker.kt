package com.bennybar.mypapers.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.app
import com.bennybar.mypapers.feature.documents.title
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * "Extract all text" for one paper, in the background (it can take minutes): an ongoing
 * notification while the AI writes the paper out, then one saying it's done (tap to open it).
 */
class TranscribeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val paperId get() = inputData.getString(KEY_ID).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        AiNotifications.progress(applicationContext, paperId.hashCode(), id, "Extracting all text", app.repository.get(paperId)?.title() ?: "a paper", paperId)

    override suspend fun doWork(): Result {
        // Not allowed from the background on newer Android: then it just runs without the notification.
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            val text = app.repository.transcribe(paperId)
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

    private suspend fun done(title: String, error: String?) =
        AiNotifications.done(applicationContext, paperId.hashCode() + 1, title, error ?: app.repository.get(paperId)?.title(), paperId)

    companion object {
        private const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 3

        private fun name(id: String) = "transcribe-$id"

        /** Already running for this paper: left to finish (not charged twice). */
        fun start(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<TranscribeWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                // Started now, while the app is open: allowed to run as a foreground job with its
                // notification from the start, so Android doesn't stop it partway.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name(id), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, id: String) = WorkManager.getInstance(context).cancelUniqueWork(name(id))

        /** True while it's queued or running for this paper. */
        fun observeRunning(context: Context, id: String): Flow<Boolean> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(id))
                .map { infos -> infos.any { !it.state.isFinished } }
    }
}
