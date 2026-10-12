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
import android.util.Log
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.DocumentRepository
import com.bennybar.mypapers.data.ImportResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
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
        // Running already: it picks these up before it ends; queued after it if it's just ending.
        enqueue(OneTimeWorkRequest.Builder(GmailImportWorker::class.java), "gmail-import", ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    suspend fun dismiss(keys: List<String>) = dao.setStates(keys, GmailCandidate.STATE_DISMISSED)

    /** The daily look for new papers, when on and after the first scan. */
    fun scheduleSweep() {
        val c = settings.config.value
        if (!c.isConnected || !c.dailySweep || settings.lastScanAt == 0L) {
            work.cancelUniqueWork("gmail-sweep")
            return
        }
        val request = PeriodicWorkRequestBuilder<GmailScanWorker>(1, TimeUnit.DAYS)
            // The first sweep a day after the scan that just ran, not right away.
            .setInitialDelay(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        work.enqueueUniquePeriodicWork("gmail-sweep", ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Another mailbox or signed out: forget what the old one found. */
    suspend fun reset() {
        dao.clear()
        dao.clearSeen()
        work.cancelUniqueWork("gmail-sweep")
    }

    /** Disconnects Gmail: no more scans, sweeps or imports, and nothing found is kept. */
    suspend fun disconnect() {
        work.cancelAllWorkByTag(TAG)
        reset()
        settings.save(settings.config.value.copy(email = ""))
    }

    private fun enqueue(builder: OneTimeWorkRequest.Builder, name: String, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        val request = builder
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name, policy, request)
    }

    /** The Gmail API with the connected account's token (asked once, renewed if Google says it expired). */
    private fun api(): GmailApi {
        val email = settings.config.value.email
        var cached: String? = null
        return GmailApi({ refresh ->
            cached?.takeUnless { refresh } ?: when (val r = GmailAuth.authorize(context, email)) {
                is GmailAuth.Result.Token -> r.accessToken
                is GmailAuth.Result.NeedsConsent -> throw GmailException("Connect Gmail again in Settings", needsReconnect = true)
            }.also { cached = it }
        })
    }

    /**
     * Looks through Gmail: only mail since the last completed scan (the daily sweep,
     * "Scan again"), or the whole [GmailConfig.monthsBack] window the first time and
     * once more when it's widened. Emails already looked at aren't fetched again, so an
     * interrupted scan resumes where it stopped. Returns how many new things it found.
     */
    suspend fun scan(onProgress: suspend (done: Int, total: Int) -> Unit): Int {
        val c = settings.config.value
        if (!c.isConnected) return 0
        val startedAt = System.currentTimeMillis()
        val window = GmailApi.scanWindow(settings.lastScanAt, settings.coveredMonths, c.monthsBack)
        val gmail = api()
        onProgress(0, 0)
        val seen = dao.seenIds().toSet()
        val withAttachments = gmail.search(GmailApi.attachmentQuery(window))
        val receipts = gmail.search(GmailApi.receiptQuery(window)) - withAttachments.toSet()
        val all = (withAttachments.map { it to false } + receipts.map { it to true }).filter { it.first !in seen }
        var added = 0
        all.chunked(BATCH).forEachIndexed { i, batch ->
            onProgress(i * BATCH, all.size)
            val emailsToo = batch.toMap()
            val messages = gmail.messages(batch.map { it.first })
            val found = messages.flatMap { m -> candidates(m, emailsToo[m.str("id")] == true, startedAt) }
            added += dao.insertNew(found).count { it != -1L }
            // Only now: an interrupted scan picks this batch up again next time.
            dao.markSeen(batch.map { GmailSeen(it.first, startedAt) })
        }
        onProgress(all.size, all.size)
        // Only now, after the whole scan: an interrupted one runs again (skipping what it fetched).
        settings.lastScanAt = startedAt
        settings.coveredMonths = maxOf(settings.coveredMonths, c.monthsBack)
        Log.i(LOG_TAG, "Gmail scan ($window): ${withAttachments.size} with attachments, ${receipts.size} receipt emails, ${all.size} not seen before, $added new")
        return added
    }

    /** The email's attachments worth offering — or, when it has none, the email itself (a receipt written in it). */
    private fun candidates(m: JsonObject, emailIfNoAttachments: Boolean, now: Long): List<GmailCandidate> {
        val id = m.str("id") ?: return emptyList()
        val payload = m["payload"]?.jsonObject ?: return emptyList()
        val subject = GmailApi.header(payload, "Subject").orEmpty().ifBlank { "(no subject)" }
        val sender = GmailApi.senderName(GmailApi.header(payload, "From").orEmpty())
        val sentAt = m.str("internalDate")?.toLongOrNull() ?: 0L
        fun candidate(kind: String, partId: String, name: String, type: String, size: Long) = GmailCandidate(
            key = "$id:$partId", gmailId = id, subject = subject, sender = sender, sentAt = sentAt,
            kind = kind, partId = partId, fileName = name, mimeType = type, size = size, foundAt = now,
        )
        val attachments = GmailApi.paperParts(m).map { candidate(GmailCandidate.KIND_ATTACHMENT, it.partId, it.fileName, it.mimeType, it.size) }
        return when {
            attachments.isNotEmpty() -> attachments
            emailIfNoAttachments -> listOf(
                candidate(GmailCandidate.KIND_EMAIL, "", safeFileName(subject) + ".pdf", "application/pdf", m.str("sizeEstimate")?.toLongOrNull() ?: 0L),
            )
            else -> emptyList()
        }
    }

    /** Brings in what was picked, one by one; each then gets the AI reading like any new paper. */
    suspend fun importQueued(onProgress: suspend (done: Int, total: Int) -> Unit) {
        val gmail by lazy { api() }
        val dir = File(context.cacheDir, "gmail").apply { mkdirs() }
        // Again until none is left: papers queued while this runs are added too.
        while (true) {
            val queued = dao.queued()
            if (queued.isEmpty()) return
            queued.forEachIndexed { i, candidate ->
                onProgress(i, queued.size)
                val file = File(dir, "import-${System.nanoTime()}")
                try {
                    val message = gmail.message(candidate.gmailId)
                    when (candidate.kind) {
                        GmailCandidate.KIND_EMAIL -> HtmlToPdf.render(context, gmail.emailHtml(candidate.gmailId, message), file)
                        else -> gmail.savePart(
                            candidate.gmailId,
                            GmailApi.partById(message, candidate.partId) ?: throw GmailException("The attachment is no longer in this email"),
                            file,
                        )
                    }
                    val docId = when (val result = repository.import(Uri.fromFile(file), candidate.fileName)) {
                        is ImportResult.Added -> result.id
                        is ImportResult.Duplicate -> result.existing.id
                    }
                    dao.setState(candidate.key, GmailCandidate.STATE_IMPORTED, documentId = docId)
                } catch (e: GmailException) {
                    if (e.needsReconnect) throw e
                    dao.setState(candidate.key, GmailCandidate.STATE_FAILED, error = e.message)
                } catch (e: java.io.IOException) {
                    dao.setState(candidate.key, GmailCandidate.STATE_FAILED, error = e.message ?: e.javaClass.simpleName)
                } finally {
                    withContext(Dispatchers.IO) { file.delete() }
                }
            }
            onProgress(queued.size, queued.size)
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Email" }

    companion object {
        const val TAG = "gmail"
        private const val BATCH = 50
    }
}

private const val LOG_TAG = "Gmail"

class GmailScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = GmailNotifications.progress(applicationContext, "Looking for papers in Gmail", 0, 0)

    override suspend fun doWork(): Result {
        // Not allowed from the background on newer Android: then it runs without the notification.
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            val found = app.gmail.scan { done, total ->
                runCatching { setForeground(GmailNotifications.progress(applicationContext, "Looking for papers in Gmail", done, total)) }
            }
            if (found > 0) GmailNotifications.found(applicationContext, found)
            app.gmail.scheduleSweep()
            Result.success()
        } catch (e: GmailException) {
            Log.w(LOG_TAG, "Gmail scan failed", e)
            if (e.needsReconnect) GmailNotifications.reconnect(applicationContext)
            Result.failure(workDataOf("error" to e.message))
        } catch (e: java.io.IOException) {
            Log.w(LOG_TAG, "Gmail scan failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(LOG_TAG, "Gmail scan failed", e)
            Result.failure(workDataOf("error" to "Scan failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}

class GmailImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo() = GmailNotifications.progress(applicationContext, "Adding papers from Gmail", 0, 0)

    override suspend fun doWork(): Result {
        // Not allowed from the background on newer Android: then it runs without the notification.
        runCatching { setForeground(getForegroundInfo()) }
        return try {
            app.gmail.importQueued { done, total ->
                runCatching { setForeground(GmailNotifications.progress(applicationContext, "Adding papers from Gmail", done, total)) }
            }
            Result.success()
        } catch (e: GmailException) {
            Log.w(LOG_TAG, "Gmail import failed", e)
            if (e.needsReconnect) GmailNotifications.reconnect(applicationContext)
            Result.failure(workDataOf("error" to e.message))
        } catch (e: java.io.IOException) {
            Log.w(LOG_TAG, "Gmail import failed (attempt ${runAttemptCount + 1})", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure(workDataOf("error" to e.message))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(LOG_TAG, "Gmail import failed", e)
            Result.failure(workDataOf("error" to "Import failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
