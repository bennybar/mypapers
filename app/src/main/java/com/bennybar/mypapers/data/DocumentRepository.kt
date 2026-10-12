package com.bennybar.mypapers.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Each document's file, stored as `files/docs/<id>`. */
class DocumentFiles(filesDir: File) {
    private val dir = File(filesDir, "docs").apply { mkdirs() }

    fun file(id: String) = File(dir, id)

    /** A temp file in the same directory, so the final rename is atomic. */
    fun temp() = File(dir, ".tmp-${UUID.randomUUID()}")

    fun delete(id: String) = file(id).delete()

    fun deleteAll() = dir.listFiles()?.forEach { it.delete() }

    companion object {
        /** The file's type from its first bytes, for when the sender doesn't say (e.g. the scanner's file:// result). */
        fun sniffMimeType(file: File): String? {
            val head = ByteArray(8)
            val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
            if (n < 4) return null
            return when {
                head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> "application/pdf"
                head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "image/jpeg"
                head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "image/png"
                n >= 8 && String(head, 4, 4, Charsets.US_ASCII) == "ftyp" -> "image/heic"
                head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() && head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte() -> "image/webp"
                else -> null
            }
        }
    }
}

sealed interface ImportResult {
    data class Added(val id: String) : ImportResult
    data class Duplicate(val existing: Document) : ImportResult
}

class DocumentRepository(
    private val dao: DocumentDao,
    val files: DocumentFiles,
    private val resolver: ContentResolver,
    /** Called after every local change, to schedule a sync. */
    private val onChanged: () -> Unit,
) {
    fun observeAll(): Flow<List<Document>> = dao.observeAll()

    fun observe(id: String): Flow<Document?> = dao.observe(id)

    /**
     * Copies a picked, scanned or shared file in; the same file twice is a duplicate.
     * [name]: the file name to keep, if not the one the file comes with.
     */
    suspend fun import(uri: Uri, name: String? = null): ImportResult = withContext(Dispatchers.IO) {
        val temp = files.temp()
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)!!.use { input ->
            temp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        dao.findByHash(hash)?.let {
            temp.delete()
            return@withContext ImportResult.Duplicate(it)
        }
        val id = UUID.randomUUID().toString()
        temp.renameTo(files.file(id))
        val now = System.currentTimeMillis()
        dao.upsert(
            Document(
                id = id,
                fileHash = hash,
                fileName = name ?: displayName(uri),
                mimeType = resolver.getType(uri) ?: DocumentFiles.sniffMimeType(files.file(id)),
                createdAt = now,
                updatedAt = now,
                fileLocal = true,
            ),
        )
        onChanged()
        ImportResult.Added(id)
    }

    suspend fun update(doc: Document) {
        dao.upsert(doc.copy(updatedAt = System.currentTimeMillis(), dirty = true))
        onChanged()
    }

    suspend fun delete(id: String) {
        val doc = dao.get(id) ?: return
        // Never pushed: nothing to tell the server.
        if (doc.rev == null) dao.remove(id)
        else dao.upsert(doc.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
        files.delete(id)
        onChanged()
    }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment
}
