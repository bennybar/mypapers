package com.bennybar.mypapers.sync

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.DocumentDao
import com.bennybar.mypapers.data.DocumentFiles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Two phones syncing through a real CouchDB database. Runs only when pointed at
 * one: MYPAPERS_TEST_URL, MYPAPERS_TEST_DB (empty, made for the test) and
 * MYPAPERS_TEST_TOKEN (a CouchDB JWT for a member of it).
 */
class SyncEngineIntegrationTest {
    private val url = System.getenv("MYPAPERS_TEST_URL")
    private val db = System.getenv("MYPAPERS_TEST_DB")
    private val token = System.getenv("MYPAPERS_TEST_TOKEN")

    private class Phone(url: String, db: String, token: String) {
        val dao = FakeDao()
        val files = DocumentFiles(Files.createTempDirectory("phone").toFile())
        val engine = SyncEngine(dao, files, object : SeqStore { override var lastSeq = "0" }) {
            CouchClient(url, db, "Bearer $token")
        }

        fun sync() = runBlocking { engine.sync().getOrThrow() }
        fun get(id: String) = runBlocking { dao.get(id) }
        fun edit(id: String, at: Long, change: (Document) -> Document) =
            runBlocking { dao.upsert(change(dao.get(id)!!).copy(updatedAt = at, dirty = true)) }
    }

    @Test
    fun twoPhonesStayInSync() {
        assumeTrue("no test server configured", url != null && db != null && token != null)
        val a = Phone(url, db, token)
        val b = Phone(url, db, token)

        // A adds a document with a file.
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        a.files.file("d1").writeBytes(bytes)
        runBlocking {
            a.dao.upsert(
                Document(id = "d1", vendor = "Wolt", totalCents = 11800, currency = "ILS", tags = listOf("Food"),
                    fileHash = "h1", mimeType = "application/pdf", createdAt = 1, updatedAt = 1000, fileLocal = true),
            )
        }
        a.sync()
        assertEquals(false, a.get("d1")!!.dirty)
        assertTrue(a.get("d1")!!.fileUploaded)

        // B gets it, file included.
        b.sync()
        val onB = b.get("d1")!!
        assertEquals("Wolt", onB.vendor)
        assertEquals(listOf("Food"), onB.tags)
        assertTrue(onB.fileLocal)
        assertTrue(bytes.contentEquals(b.files.file("d1").readBytes()))

        // B edits; A sees it, and keeps its file (same file, no re-download needed).
        b.edit("d1", at = 2000) { it.copy(vendor = "Wolt Market") }
        b.sync()
        a.sync()
        assertEquals("Wolt Market", a.get("d1")!!.vendor)
        assertTrue(a.get("d1")!!.fileLocal)

        // Both edit offline; the later edit wins on both phones.
        a.edit("d1", at = 3000) { it.copy(notes = "from A") }
        b.edit("d1", at = 4000) { it.copy(notes = "from B") }
        a.sync()
        b.sync() // conflicts with A's push, B's is newer → B wins
        a.sync()
        assertEquals("from B", a.get("d1")!!.notes)
        assertEquals("from B", b.get("d1")!!.notes)
        // The file survived the conflict on the server.
        val c = Phone(url, db, token)
        c.sync()
        assertTrue(bytes.contentEquals(c.files.file("d1").readBytes()))

        // A deletes; B loses it, file too.
        runBlocking { a.dao.upsert(a.get("d1")!!.copy(deleted = true, dirty = true, updatedAt = 5000)) }
        a.sync()
        assertNull(a.get("d1"))
        b.sync()
        assertNull(b.get("d1"))
        assertTrue(!b.files.file("d1").exists())
    }
}

/** An in-memory DocumentDao with the same semantics as the Room queries. */
private class FakeDao : DocumentDao {
    private val rows = MutableStateFlow(mapOf<String, Document>())

    override fun observeAll(): Flow<List<Document>> = rows.map { m -> m.values.filter { !it.deleted } }
    override fun observe(id: String): Flow<Document?> = rows.map { it[id]?.takeIf { d -> !d.deleted } }
    override suspend fun get(id: String) = rows.value[id]
    override suspend fun findByHash(hash: String) = rows.value.values.firstOrNull { !it.deleted && it.fileHash == hash }
    override suspend fun all() = rows.value.values.filter { !it.deleted }
    override suspend fun dirty() = rows.value.values.filter { it.dirty }
    override suspend fun missingFiles() = rows.value.values.filter { !it.deleted && it.fileUploaded && !it.fileLocal }
    override suspend fun upsert(doc: Document) { rows.value = rows.value + (doc.id to doc) }
    override suspend fun remove(id: String) { rows.value = rows.value - id }
    override suspend fun markPushed(id: String, rev: String, fileUploaded: Boolean, pushedUpdatedAt: Long) {
        val d = rows.value[id] ?: return
        upsert(d.copy(rev = rev, fileUploaded = fileUploaded, dirty = if (d.updatedAt == pushedUpdatedAt) false else d.dirty))
    }
    override suspend fun setRev(id: String, rev: String?) { rows.value[id]?.let { upsert(it.copy(rev = rev)) } }
    override suspend fun markFileLocal(id: String) { rows.value[id]?.let { upsert(it.copy(fileLocal = true)) } }
    override suspend fun clear() { rows.value = emptyMap() }
    override suspend fun resetSync() { rows.value = rows.value.mapValues { it.value.copy(rev = null, dirty = true, fileUploaded = false) } }
}
