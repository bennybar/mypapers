package com.bennybar.mypapers.gmail

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.DocumentRepository
import com.bennybar.mypapers.data.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finding papers in Gmail and bringing in the ones the user picks. Both run as
 * background jobs shown as an ongoing notification, so Android keeps them going.
 */
class GmailImporter(
    private val context: Context,
    private val dao: GmailCandidateDao,
    val settings: GmailSettings,
    private val repository: DocumentRepository,
) {
    private val work get() = WorkManager.getInstance(context)

    fun observePending() = dao.observePending()
    fun observeNewCount() = dao.observeNewCount()

    /** True while a scan or an import is running (or waiting for a network); not the idle daily sweep. */
    fun observeBusy(): Flow<Boolean> = combine(
        work.getWorkInfosForUniqueWorkFlow("gmail-scan"),
        work.getWorkInfosForUniqueWorkFlow("gmail-import"),
    ) { scan, import ->
        (scan + import).any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    /** Why the last scan failed (wrong password…), if it did. */
    fun observeScanError(): Flow<String?> = work.getWorkInfosForUniqueWorkFlow("gmail-scan").map { infos ->
        infos.firstOrNull { it.state == WorkInfo.State.FAILED }?.outputData?.getString("error")
    }

    fun scanNow() = enqueue(OneTimeWorkRequest.Builder(GmailScanWorker::class.java), "gmail-scan")

    suspend fun queue(keys: List<String>) {
        dao.setStates(keys, GmailCandidate.STATE_QUEUED)
        enqueue(OneTimeWorkRequest.Builder(GmailImportWorker::class.java), "gmail-import")
    }

    suspend fun dismiss(keys: List<String>) = dao.setStates(keys, GmailCandidate.STATE_DISMISSED)

    /** The daily look for new papers, when on and after the first scan. */
    fun scheduleSweep() {
        val c = settings.config.value
        if (!c.isSet || !c.dailySweep || settings.lastScanAt == 0L) {
            work.cancelUniqueWork("gmail-sweep")
            return
        }
        val request = PeriodicWorkRequestBuilder<GmailScanWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        work.enqueueUniquePeriodicWork("gmail-sweep", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Another mailbox or signed out: forget what the old one found. */
    suspend fun reset() {
        dao.clear()
        work.cancelUniqueWork("gmail-sweep")
    }

    private fun enqueue(builder: OneTimeWorkRequest.Builder, name: String) {
        val request = builder
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Looks through Gmail: the first time back [GmailConfig.monthsBack] months,
     * later since the last scan. Returns how many new things it found.
     */
    suspend fun scan(onProgress: suspend (done: Int, total: Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val c = settings.config.value
        if (!c.isSet) return@withContext 0
        val startedAt = System.currentTimeMillis()
        val last = settings.lastScanAt
        // Gmail's newer_than: months for the first scan; days (with a day's overlap) after that.
        val window = if (last == 0L) "${c.monthsBack}m" else "${TimeUnit.MILLISECONDS.toDays(startedAt - last) + 2}d"
        GmailClient.connect(c.address, c.appPassword).use { gmail ->
            val withAttachments = gmail.search(GmailClient.attachmentQuery(window)).toSet()
            val receipts = gmail.search(GmailClient.receiptQuery(window)).toSet() - withAttachments
            val all = (withAttachments.map { it to false } + receipts.map { it to true }).sortedByDescending { it.first }
            var added = 0
            // In batches: one round trip per batch for the email structures.
            all.chunked(BATCH).forEachIndexed { i, batch ->
                onProgress(i * BATCH, all.size)
                val found = batch.groupBy({ it.second }, { it.first }).flatMap { (emails, uids) ->
                    gmail.describe(uids, includeEmails = emails, now = startedAt)
                }
                added += dao.insertNew(found).count { it != -1L }
            }
            settings.lastScanAt = startedAt
            added
        }
    }

    /** Brings in what was picked, one by one; each then gets the AI reading like any new paper. */
    suspend fun importQueued(onProgress: suspend (done: Int, total: Int) -> Unit) = withContext(Dispatchers.IO) {
        val queued = dao.queued()
        if (queued.isEmpty()) return@withContext
        val c = settings.config.value
        val dir = File(context.cacheDir, "gmail").apply { mkdirs() }
        GmailClient.connect(c.address, c.appPassword).use { gmail ->
            queued.forEachIndexed { i, candidate ->
                onProgress(i, queued.size)
                val file = File(dir, "import-${System.nanoTime()}")
                try {
                    when (candidate.kind) {
                        GmailCandidate.KIND_EMAIL -> HtmlToPdf.render(context, gmail.emailHtml(candidate), file)
                        else -> gmail.downloadPart(candidate, file)
                    }
                    val result = repository.import(Uri.fromFile(file), candidate.fileName)
                    val docId = when (result) {
                        is ImportResult.Added -> result.id
                        is ImportResult.Duplicate -> result.existing.id
                    }
                    dao.setState(candidate.key, GmailCandidate.STATE_IMPORTED, documentId = docId)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    dao.setState(candidate.key, GmailCandidate.STATE_FAILED, error = e.message ?: e.javaClass.simpleName)
                } finally {
                    file.delete()
                }
            }
            onProgress(queued.size, queued.size)
        }
    }

    companion object {
        const val TAG = "gmail"
        private const val BATCH = 50
    }
}

class GmailScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = GmailNotifications.progress(applicationContext, "Looking for papers in Gmail", 0, 0)

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        return try {
            val found = app.gmail.scan { done, total ->
                setForeground(GmailNotifications.progress(applicationContext, "Looking for papers in Gmail", done, total))
            }
            if (found > 0) GmailNotifications.found(applicationContext, found)
            app.gmail.scheduleSweep()
            Result.success()
        } catch (e: GmailException) {
            Result.failure(workDataOf("error" to e.message))
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        } catch (e: javax.mail.MessagingException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        }
    }
}

class GmailImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = GmailNotifications.progress(applicationContext, "Adding papers from Gmail", 0, 0)

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        return try {
            app.gmail.importQueued { done, total ->
                setForeground(GmailNotifications.progress(applicationContext, "Adding papers from Gmail", done, total))
            }
            Result.success()
        } catch (e: GmailException) {
            Result.failure(workDataOf("error" to e.message))
        } catch (e: javax.mail.MessagingException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        }
    }
}
