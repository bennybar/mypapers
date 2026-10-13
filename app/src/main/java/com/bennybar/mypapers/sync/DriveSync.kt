package com.bennybar.mypapers.sync

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.DriveSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Where the paper's change-feed position is kept (see [DriveSettings]). */
interface PageTokenStore {
    var pageToken: String?
    /** When a sync last finished with everything sent up; 0 = not yet. */
    var lastSyncAt: Long
}

/**
 * Two-way sync between Room and the app's hidden folder in the user's own Google Drive.
 * Each paper is two files there: `<id>.json` (its fields, as for CouchDB before) and `<id>.file`
 * (the PDF or photo). Read Drive's change feed, push local changes, then fetch missing files.
 * A conflict goes to the side edited last ([Document.updatedAt], also kept on the Drive file so
 * our own uploads coming back aren't downloaded). A paper deleted for good has both files removed;
 * [Document.rev] holds the id of its `.json` there.
 */
class DriveSync(
    private val dao: DocumentDao,
    private val files: DocumentFiles,
    private val position: PageTokenStore,
    /** Null while signed out. */
    private val store: () -> DriveStore?,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    /** Runs one full sync (a second caller waits for the running one, then syncs again). */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        val drive = store() ?: return Result.success(Unit)
        _state.value = SyncState.Syncing
        runCatching {
            pull(drive)
            push(drive)
            downloadFiles(drive)
        }.onSuccess {
            position.lastSyncAt = System.currentTimeMillis()
            _state.value = SyncState.Idle
        }
            .onFailure { _state.value = SyncState.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /** Runs [block] with no sync in progress (e.g. signing out, so a finishing sync can't write back). */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun pull(drive: DriveStore) {
        var token = position.pageToken
        if (token == null) {
            // First time with this account: everything there, then changes from before the listing on.
            val start = drive.startPageToken()
            for (f in drive.listAll()) if (f.name.endsWith(JSON_SUFFIX)) applyRemote(drive, f)
            position.pageToken = start
            token = start
        }
        while (true) {
            val page = drive.changes(token!!)
            for (c in page.changes) {
                val f = c.file
                if (f == null) removedRemotely(c.fileId) else if (f.name.endsWith(JSON_SUFFIX)) applyRemote(drive, f)
            }
            token = page.next
            position.pageToken = token
            if (page.done) break
        }
    }

    private suspend fun applyRemote(drive: DriveStore, f: DriveFile) {
        val id = f.name.removeSuffix(JSON_SUFFIX)
        val local = dao.get(id)
        // Already have this edit (our own upload coming back), or a newer one of ours that the push sends.
        if (local != null && f.updatedAt != null && (local.updatedAt == f.updatedAt || local.dirty && local.updatedAt > f.updatedAt)) {
            if (local.rev != f.id) dao.setRev(id, f.id)
            return
        }
        val remote = Json.parseToJsonElement(drive.read(f.id).decodeToString()).jsonObject
        if (local != null && local.dirty && local.updatedAt >= remote.updatedAt()) {
            dao.setRev(id, f.id)
            return
        }
        // Our file never got there (another edit won the race): keep it, and upload it on the next push.
        if (local != null && local.fileLocal && !local.fileUploaded && !remote.hasFile()) {
            dao.upsert(remote.toPaper(f.id, fileLocal = true).copy(fileHash = local.fileHash, mimeType = local.mimeType, dirty = true))
            return
        }
        // Keep the file we have if it's still the same one.
        val sameFile = local != null && local.fileLocal && local.fileHash == remote.fileHash()
        if (local != null && !sameFile) files.delete(id)
        dao.upsert(remote.toPaper(f.id, fileLocal = sameFile))
    }

    /** A paper deleted for good on another device; an edit here since then brings it back. */
    private suspend fun removedRemotely(fileId: String) {
        val local = dao.byRev(fileId) ?: return
        if (local.dirty && !local.deleted) {
            dao.setRev(local.id, null)
            return
        }
        dao.remove(local.id)
        files.delete(local.id)
    }

    private suspend fun push(drive: DriveStore) {
        val dirty = dao.dirty()
        dirty.forEachIndexed { i, doc ->
            _state.value = SyncState.Uploading(i, dirty.size)
            pushOne(drive, doc)
        }
        _state.value = SyncState.Syncing
    }

    private suspend fun pushOne(drive: DriveStore, doc: Document) {
        val metaName = doc.id + JSON_SUFFIX
        val fileName = doc.id + FILE_SUFFIX
        if (doc.deleted) {
            (doc.rev ?: drive.find(metaName))?.let { drive.delete(it) }
            drive.find(fileName)?.let { drive.delete(it) }
            dao.remove(doc.id)
            return
        }
        var uploaded = doc.fileUploaded
        // The file first, so another device that sees the paper can fetch it. Gone if deleted meanwhile.
        val file = files.file(doc.id)
        if (!uploaded && doc.fileLocal && file.exists()) {
            val type = (doc.mimeType ?: "application/octet-stream").toMediaType()
            drive.put(drive.find(fileName), fileName, null, file.asRequestBody(type))
            uploaded = true
        }
        val json = doc.copy(rev = null).toCouchJson(keepFile = uploaded).toString().toRequestBody(DriveClient.JSON)
        val metaId = try {
            drive.put(doc.rev ?: drive.find(metaName), metaName, doc.updatedAt, json)
        } catch (e: DriveException) {
            if (e.code != 404) throw e
            drive.put(null, metaName, doc.updatedAt, json) // removed there meanwhile: write it anew
        }
        dao.markPushed(doc.id, metaId, uploaded, doc.updatedAt)
    }

    private suspend fun downloadFiles(drive: DriveStore) {
        for (doc in dao.missingFiles()) {
            val id = drive.find(doc.id + FILE_SUFFIX) ?: continue
            val temp = files.temp()
            try {
                drive.download(id, temp)
                temp.renameTo(files.file(doc.id))
                dao.markFileLocal(doc.id)
            } finally {
                temp.delete()
            }
        }
    }

    private fun JsonObject.toPaper(driveId: String, fileLocal: Boolean): Document = toDocument(fileLocal).copy(rev = driveId)

    companion object {
        const val JSON_SUFFIX = ".json"
        const val FILE_SUFFIX = ".file"
    }
}
