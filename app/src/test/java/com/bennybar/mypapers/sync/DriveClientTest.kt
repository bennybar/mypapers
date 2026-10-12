package com.bennybar.mypapers.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Drive REST calls, against a stand-in server. */
class DriveClientTest {
    private val server = MockWebServer()
    private val tokens = ArrayDeque(listOf("t1", "t2"))
    private val cleared = mutableListOf<String>()
    private lateinit var client: DriveClient

    @Before fun start() {
        server.start()
        client = DriveClient(token = { tokens.first() }, clearToken = { cleared += it; tokens.removeFirst() }, base = server.url("/").toString().trimEnd('/'))
    }

    @After fun stop() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    @Test fun createUploadsDetailsThenContent() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Location", server.url("/upload-session").toString()).build())
        server.enqueue(json("""{"id":"F1"}"""))
        val id = client.put(null, "d1.json", 1234L, "{}".toRequestBody(DriveClient.JSON))
        assertEquals("F1", id)
        val start = server.takeRequest()
        assertEquals("POST", start.method)
        assertTrue(start.url.toString().contains("/upload/drive/v3/files?uploadType=resumable"))
        val meta = start.body!!.utf8()
        assertTrue(meta, meta.contains("\"name\":\"d1.json\"") && meta.contains("appDataFolder") && meta.contains("\"updatedAt\":\"1234\""))
        assertEquals("Bearer t1", start.headers["Authorization"])
        val upload = server.takeRequest()
        assertEquals("PUT", upload.method)
        assertEquals("{}", upload.body!!.utf8())
    }

    @Test fun updateKeepsTheNameAndGone404() = runBlocking {
        server.enqueue(json("""{"error":{"message":"File not found"}}""", 404))
        val e = runCatching { client.put("F9", "d1.json", 1L, "{}".toRequestBody(DriveClient.JSON)) }.exceptionOrNull()
        assertEquals(404, (e as DriveException).code)
        val r = server.takeRequest()
        assertEquals("PATCH", r.method)
        assertTrue(!r.body!!.utf8().contains("\"name\""))
    }

    @Test fun changesPagesAndRemovals() = runBlocking {
        server.enqueue(json("""{"nextPageToken":"p2","changes":[{"fileId":"A","removed":false,"file":{"id":"A","name":"d1.json","appProperties":{"updatedAt":"5"}}},{"fileId":"B","removed":true}]}"""))
        val page = client.changes("p1")
        assertEquals("p2", page.next)
        assertTrue(!page.done)
        assertEquals(DriveFile("A", "d1.json", 5), page.changes[0].file)
        assertNull(page.changes[1].file)
        server.enqueue(json("""{"newStartPageToken":"p3","changes":[{"fileId":"C","file":{"id":"C","name":"d2.json","trashed":true}}]}"""))
        val last = client.changes("p2")
        assertTrue(last.done)
        assertEquals("p3", last.next)
        assertNull(last.changes[0].file) // trashed counts as removed
    }

    @Test fun aTurnedDownTokenIsReplacedOnce() = runBlocking {
        server.enqueue(json("""{"error":{"message":"Invalid Credentials"}}""", 401))
        server.enqueue(json("""{"startPageToken":"42"}"""))
        assertEquals("42", client.startPageToken())
        assertEquals(listOf("t1"), cleared)
        server.takeRequest()
        assertEquals("Bearer t2", server.takeRequest().headers["Authorization"])
    }

    @Test fun findQueriesByName() = runBlocking {
        server.enqueue(json("""{"files":[{"id":"X"}]}"""))
        assertEquals("X", client.find("d1.file"))
        val q = server.takeRequest().url.queryParameter("q")
        assertEquals("name = 'd1.file' and trashed = false", q)
    }
}
