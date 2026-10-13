package com.bennybar.mypapers.sync

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.data.DocumentFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    /** Sending papers up: [done] of [total]. */
    data class Uploading(val done: Int, val total: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

/** Where the last pulled `_changes` position is kept. */
interface SeqStore {
    var lastSeq: String
}

/**
 * Two-way sync between Room and one CouchDB database, single user:
 * pull the server's changes, push local ones, then fetch missing files.
 * A conflict goes to the side edited last ([Document.updatedAt]).
 */
class SyncEngine(
    private val dao: DocumentDao,
    private val files: DocumentFiles,
    private val seq: SeqStore,
    /** Null while no server is set up. */
    private val client: () -> CouchClient?,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state

    /** Runs one full sync (a second caller waits for the running one, then syncs again). */
    suspend fun sync(): Result<Unit> = mutex.withLock {
        val couch = client() ?: return Result.success(Unit)
        _state.value = SyncState.Syncing
        runCatching {
            pull(couch)
            push(couch)
            downloadFiles(couch)
        }.onSuccess { _state.value = SyncState.Idle }
            .onFailure {
                _state.value = SyncState.Failed(
                    if (it is CouchException && it.code == 401) "Signed out by the server; sign in again"
                    else it.message ?: it.javaClass.simpleName,
                )
            }
    }

    /** Runs [block] with no sync in progress (e.g. signing out, so a finishing sync can't write back). */
    suspend fun <T> whileIdle(block: suspend () -> T): T = mutex.withLock { block() }

    private suspend fun pull(couch: CouchClient) {
        while (true) {
            val page = couch.changes(seq.lastSeq, limit = 100)
            for (change in page.results) {
                val remote = change.changeDoc() ?: continue
                if (remote.isDocumentOrTombstone()) applyRemote(remote)
            }
            seq.lastSeq = page.lastSeq
            if (page.results.size < 100) break
        }
    }

    private suspend fun applyRemote(remote: JsonObject) {
        val id = remote.docId()
        val local = dao.get(id)
        if (local != null && local.rev == remote.rev()) return // our own push coming back
        if (local != null && local.dirty && local.updatedAt >= remote.updatedAt()) {
            // The local edit is newer: build on the server's revision so the push wins.
            dao.setRev(id, remote.rev())
            return
        }
        if (remote.isDeleted()) {
            if (local != null) {
                dao.remove(id)
                files.delete(id)
            }
            return
        }
        // Our file never got to the server (another edit won the race): keep it, and upload it on the next push.
        if (local != null && local.fileLocal && !local.fileUploaded && !remote.hasFile()) {
            dao.upsert(remote.toDocument(fileLocal = true).copy(fileHash = local.fileHash, mimeType = local.mimeType, dirty = true))
            return
        }
        // Keep the file we have if it's still the same one.
        val sameFile = local != null && local.fileLocal && local.fileHash == remote.fileHash()
        if (local != null && !sameFile) files.delete(id)
        dao.upsert(remote.toDocument(fileLocal = sameFile))
    }

    private suspend fun push(couch: CouchClient) {
        for (doc in dao.dirty()) pushOne(couch, doc)
    }

    private suspend fun pushOne(couch: CouchClient, start: Document) {
        var doc = start
        repeat(3) {
            if (doc.deleted && doc.rev == null) {
                // Deleted before we knew of a push; one may have got there anyway.
                val remote = couch.get(doc.id)
                if (remote == null) {
                    dao.remove(doc.id)
                    return
                }
                doc = doc.copy(rev = remote.rev())
            }
            try {
                var rev = couch.put(doc.id, doc.toCouchJson(keepFile = doc.fileUploaded))
                if (doc.deleted) {
                    dao.remove(doc.id)
                    return
                }
                var uploaded = doc.fileUploaded
                // The file is gone if the paper was deleted meanwhile: the tombstone's push follows.
                if (!uploaded && doc.fileLocal && files.file(doc.id).exists()) {
                    rev = couch.putAttachment(
                        doc.id, rev, FILE_ATTACHMENT, files.file(doc.id), doc.mimeType ?: "application/octet-stream",
                    )
                    uploaded = true
                }
                dao.markPushed(doc.id, rev, uploaded, doc.updatedAt)
                return
            } catch (e: CouchException) {
                if (e.code != 409) throw e
                // Changed on the server since our revision.
                val remote = couch.get(doc.id)
                if (remote == null) {
                    // Deleted there: recreate (a PUT without _rev revives it), unless our change is a deletion.
                    doc = doc.copy(rev = null, fileUploaded = false)
                } else if (remote.updatedAt() > doc.updatedAt) {
                    applyRemote(remote)
                    return
                } else {
                    val sameFile = remote.hasFile() && remote.fileHash() == doc.fileHash
                    doc = doc.copy(rev = remote.rev(), fileUploaded = sameFile)
                }
            }
        }
    }

    private suspend fun downloadFiles(couch: CouchClient) {
        for (doc in dao.missingFiles()) {
            val temp = files.temp()
            try {
                couch.getAttachment(doc.id, FILE_ATTACHMENT, temp)
                temp.renameTo(files.file(doc.id))
                dao.markFileLocal(doc.id)
            } finally {
                temp.delete()
            }
        }
    }
}
