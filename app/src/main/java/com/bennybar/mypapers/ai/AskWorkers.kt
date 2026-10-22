package com.bennybar.mypapers.ai

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.app
import com.bennybar.mypapers.feature.documents.title
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val KEY_ID = "id"
private const val MAX_ATTEMPTS = 3

/** Started while the app is open, so it can run as a foreground job with its notification from the start. */
private inline fun <reified W : ListenableWorker> enqueue(context: Context, name: String, data: androidx.work.Data, policy: ExistingWorkPolicy) {
    val request = OneTimeWorkRequestBuilder<W>()
        .setInputData(data)
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
}

private fun observeWork(context: Context, name: String): Flow<Boolean> =
    WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name).map { infos -> infos.any { !it.state.isFinished } }

/** A failure worth another try later (network, a busy OpenAI), within [MAX_ATTEMPTS]. */
private fun ListenableWorker.retryable(e: Exception) =
    runAttemptCount < MAX_ATTEMPTS && (e is AiException && e.retryable || e !is AiException && e is java.io.IOException)

/** Ask AI about one paper: answers the last turn of its conversation in [AiAnswers], which waits there for the paper's page. */
class AskWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val id get() = inputData.getString(KEY_ID).orEmpty()
    private val notificationId get() = ("ask" + id).hashCode()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        AiNotifications.progress(applicationContext, notificationId, "Asking the AI", app.answers.papers.value[id]?.lastOrNull()?.question, id)

    /** The conversation with its last turn replaced by [turn] (if it's still the one asked). */
    private fun answer(question: String, turn: PaperTurn) {
        val turns = app.answers.papers.value[id] ?: return
        if (turns.lastOrNull()?.question == question && !turns.last().done) app.answers.setPaper(id, turns.dropLast(1) + turn)
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val turns = app.answers.papers.value[id] ?: return Result.success()
        val question = turns.lastOrNull()?.takeUnless { it.done }?.question ?: return Result.success()
        return try {
            val turn = app.repository.askPaper(id, turns)
            answer(question, turn)
            AiNotifications.done(applicationContext, notificationId, "Answer ready", turn.reply ?: question, id)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (retryable(e)) return Result.retry()
            answer(question, PaperTurn(question, error = e.message ?: e.javaClass.simpleName))
            AiNotifications.done(applicationContext, notificationId, "Couldn't ask the AI", e.message, id)
            Result.failure()
        }
    }

    companion object {
        /** True while it's queued or running for this paper. */
        fun observeRunning(context: Context, id: String) = observeWork(context, "ask-$id")

        /** A question, or a follow-up to the conversation so far. */
        fun start(context: Context, id: String, question: String, reworks: String? = null) {
            val before = app.answers.papers.value[id].orEmpty().filter { it.done }
            // A follow-up to a rework ("only the first rows") still replaces that detail.
            app.answers.setPaper(id, before + PaperTurn(question, reworks = reworks ?: before.lastOrNull()?.reworks))
            enqueue<AskWorker>(context, "ask-$id", workDataOf(KEY_ID to id), ExistingWorkPolicy.REPLACE)
        }
    }
}

/** A question about all the papers: answers the last turn of that conversation in [AiAnswers], for the home screen. */
class AskAllWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo =
        AiNotifications.progress(applicationContext, NOTIFICATION, "Asking about your papers", app.answers.all.value?.lastOrNull()?.question, null)

    private fun answer(question: String, turn: AllTurn) {
        val turns = app.answers.all.value ?: return
        if (turns.lastOrNull()?.question == question && !turns.last().done) app.answers.setAll(turns.dropLast(1) + turn)
    }

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val turns = app.answers.all.value ?: return Result.success()
        val question = turns.lastOrNull()?.takeUnless { it.done }?.question ?: return Result.success()
        return try {
            val a = app.repository.askAll(question, turns.dropLast(1).filter { it.answer != null })
            answer(question, AllTurn(question, a.answer, a.papers.map { it.id }, a.totals))
            AiNotifications.done(applicationContext, NOTIFICATION, "Answer ready", question, null)
            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (retryable(e)) return Result.retry()
            answer(question, AllTurn(question, error = e.message ?: e.javaClass.simpleName))
            AiNotifications.done(applicationContext, NOTIFICATION, "Couldn't ask the AI", e.message, null)
            Result.failure()
        }
    }

    companion object {
        private const val NOTIFICATION = 2002

        fun observeRunning(context: Context) = observeWork(context, "ask-all")

        /** A new question ([followUp] false: the conversation starts over) or a follow-up. */
        fun start(context: Context, question: String, followUp: Boolean) {
            val before = if (followUp) app.answers.all.value.orEmpty().filter { it.done } else emptyList()
            app.answers.setAll(before + AllTurn(question))
            enqueue<AskAllWorker>(context, "ask-all", workDataOf(), ExistingWorkPolicy.REPLACE)
        }
    }
}
