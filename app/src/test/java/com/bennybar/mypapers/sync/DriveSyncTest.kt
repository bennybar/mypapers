package com.bennybar.mypapers.sync

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.DocumentFiles
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Two phones syncing through an in-memory stand-in for the app's Drive folder. */
class DriveSyncTest {
    /** Drive's appDataFolder: files, and a change feed whose tokens are positions in it. */
    private class FakeDrive : DriveStore {
        val files = mutableMapOf<String, Triple<String, Long?, ByteArray>>() // id → name, updatedAt, content
        val log = mutableListOf<DriveChange>()
        var reads = 0
        private var next = 0

        override suspend fun startPageToken() = log.size.toString()
        override suspend fun listAll() = files.map { (id, f) -> DriveFile(id, f.first, f.second) }
        override suspend fun changes(token: String): ChangePage {
            val from = token.toInt()
            return ChangePage(log.drop(from), log.size.toString(), done = true)
        }
        override suspend fun find(name: String) = files.entries.firstOrNull { it.value.first == name }?.key
        override suspend fun read(id: String): ByteArray {
            reads++
            return files[id]!!.third
        }
        override suspend fun download(id: String, to: File) = to.writeBytes(files[id]!!.third)
        override suspend fun put(id: String?, name: String, updatedAt: Long?, content: RequestBody): String {
            if (id != null && id !in files) throw DriveException("gone", 404)
            val fileId = id ?: "f${next++}"
            val bytes = Buffer().also { content.writeTo(it) }.readByteArray()
            files[fileId] = Triple(files[fileId]?.first ?: name, updatedAt, bytes)
            log += DriveChange(fileId, DriveFile(fileId, files[fileId]!!.first, updatedAt))
            return fileId
        }
        override suspend fun delete(id: String) {
            if (files.remove(id) != null) log += DriveChange(id, null)
        }
    }

    private class Phone(drive: FakeDrive) {
        val dao = FakeDao()
        val files = DocumentFiles(Files.createTempDirectory("phone").toFile())
        val engine = DriveSync(dao, files, object : PageTokenStore { override var pageToken: String? = null }) { drive }

        fun sync() = runBlocking { engine.sync().getOrThrow() }
        fun get(id: String) = runBlocking { dao.get(id) }
        fun edit(id: String, at: Long, change: (Document) -> Document) =
            runBlocking { dao.upsert(change(dao.get(id)!!).copy(updatedAt = at, dirty = true)) }
    }

    @Test
    fun twoPhonesStayInSync() {
        val drive = FakeDrive()
        val a = Phone(drive)
        val b = Phone(drive)

        // A adds a paper with a file.
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        a.files.file("d1").writeBytes(bytes)
        runBlocking {
            a.dao.upsert(
                Document(id = "d1", vendor = "Wolt", totalCents = 11800, currency = "ILS", tags = listOf("Food"),
                    fileHash = "h1", mimeType = "application/pdf", createdAt = 1, updatedAt = 1000, fileLocal = true),
            )
        }
        a.sync()
        assertFalse(a.get("d1")!!.dirty)
        assertTrue(a.get("d1")!!.fileUploaded)
        assertEquals(setOf("d1.json", "d1.file"), drive.files.values.map { it.first }.toSet())

        // A's own upload coming back isn't downloaded again.
        val reads = drive.reads
        a.sync()
        assertEquals(reads, drive.reads)

        // B gets it, file included.
        b.sync()
        val onB = b.get("d1")!!
        assertEquals("Wolt", onB.vendor)
        assertEquals(listOf("Food"), onB.tags)
        assertTrue(onB.fileLocal)
        assertTrue(bytes.contentEquals(b.files.file("d1").readBytes()))

        // B edits; A sees it and keeps its file.
        b.edit("d1", at = 2000) { it.copy(vendor = "Wolt Market") }
        b.sync()
        a.sync()
        assertEquals("Wolt Market", a.get("d1")!!.vendor)
        assertTrue(a.get("d1")!!.fileLocal)
        assertEquals(2, drive.files.size) // updated in place, not duplicated

        // Both edit offline; the later edit wins on both phones.
        a.edit("d1", at = 3000) { it.copy(notes = "from A") }
        b.edit("d1", at = 4000) { it.copy(notes = "from B") }
        a.sync()
        b.sync()
        a.sync()
        assertEquals("from B", a.get("d1")!!.notes)
        assertEquals("from B", b.get("d1")!!.notes)
        // The older edit arriving late doesn't win either.
        a.edit("d1", at = 3500) { it.copy(notes = "late A") }
        b.edit("d1", at = 4500) { it.copy(notes = "B again") }
        b.sync()
        a.sync()
        b.sync()
        assertEquals("B again", a.get("d1")!!.notes)
        assertEquals("B again", b.get("d1")!!.notes)

        // A new phone gets everything.
        val c = Phone(drive)
        c.sync()
        assertEquals("B again", c.get("d1")!!.notes)
        assertTrue(bytes.contentEquals(c.files.file("d1").readBytes()))

        // A deletes for good; B loses it, file too, and Drive is empty.
        runBlocking { a.dao.upsert(a.get("d1")!!.copy(deleted = true, dirty = true, updatedAt = 5000)) }
        a.sync()
        assertNull(a.get("d1"))
        b.sync()
        assertNull(b.get("d1"))
        assertFalse(b.files.file("d1").exists())
        assertTrue(drive.files.isEmpty())
    }

    /** Signing in with papers already on the phone (moving from the old server): they all go up. */
    @Test
    fun papersOnThePhoneGoUpOnFirstSignIn() {
        val drive = FakeDrive()
        val a = Phone(drive)
        runBlocking {
            for (i in 1..3) {
                a.files.file("p$i").writeBytes(byteArrayOf(i.toByte()))
                // As left by the old server's sync, then reset for the new account.
                a.dao.upsert(Document(id = "p$i", vendor = "V$i", fileHash = "h$i", createdAt = 1, updatedAt = 100L + i,
                    rev = "1-couch", dirty = false, fileUploaded = true, fileLocal = true))
            }
            a.dao.resetSync()
        }
        a.sync()
        assertEquals(6, drive.files.size)
        val b = Phone(drive)
        b.sync()
        for (i in 1..3) {
            assertEquals("V$i", b.get("p$i")!!.vendor)
            assertTrue(byteArrayOf(i.toByte()).contentEquals(b.files.file("p$i").readBytes()))
        }
    }

    /** A paper deleted on one phone while edited later on another comes back. */
    @Test
    fun editAfterDeleteBringsItBack() {
        val drive = FakeDrive()
        val a = Phone(drive)
        val b = Phone(drive)
        runBlocking { a.dao.upsert(Document(id = "d", vendor = "X", createdAt = 1, updatedAt = 1000)) }
        a.sync()
        b.sync()
        runBlocking { a.dao.upsert(a.get("d")!!.copy(deleted = true, dirty = true, updatedAt = 2000)) }
        b.edit("d", at = 3000) { it.copy(notes = "keep me") }
        a.sync()
        b.sync()
        a.sync()
        assertEquals("keep me", a.get("d")!!.notes)
        assertEquals("keep me", b.get("d")!!.notes)
    }
}
