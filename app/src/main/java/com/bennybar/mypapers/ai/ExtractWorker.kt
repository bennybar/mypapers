package com.bennybar.mypapers.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.app
import java.util.concurrent.TimeUnit

/**
 * Reads one document with the AI in the background, so it finishes even if the
 * app is closed; waits for a network and retries rate limits and server errors.
 */
class ExtractWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        return try {
            app.repository.extract(id)
            Result.success()
        } catch (e: AiException) {
            if (e.retryable && runAttemptCount < MAX_ATTEMPTS) return Result.retry()
            app.repository.markExtractionFailed(id, e.message ?: "AI reading failed")
            Result.failure()
        } catch (e: java.io.IOException) {
            // Network trouble: try again later.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry()
            else {
                app.repository.markExtractionFailed(id, e.message ?: e.javaClass.simpleName)
                Result.failure()
            }
        } catch (e: Exception) {
            // Anything else (an odd answer, a locked PDF): failed, not stuck on "Reading…".
            if (e is kotlinx.coroutines.CancellationException) throw e
            app.repository.markExtractionFailed(id, e.message ?: e.javaClass.simpleName)
            Result.failure()
        }
    }

    companion object {
        const val TAG = "extract"
        private const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 4

        fun enqueue(context: Context, id: String) {
            val request = OneTimeWorkRequestBuilder<ExtractWorker>()
                .setInputData(workDataOf(KEY_ID to id))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("extract-$id", ExistingWorkPolicy.REPLACE, request)
        }
    }
}
