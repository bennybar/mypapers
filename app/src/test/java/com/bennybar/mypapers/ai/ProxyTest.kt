package com.bennybar.mypapers.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The AI through an OpenAI-compatible proxy (LiteLLM…), a stand-in server here. */
class ProxyTest {
    private val server = MockWebServer()
    private lateinit var config: AiConfig

    @Before fun start() {
        server.start()
        config = AiConfig("proxy-key", "claude-sonnet", baseUrl = server.url("/v1/").toString())
    }

    @After fun stop() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    private val answer = """{"id":"r1","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"text\":\"hello\"}"}]}]}"""

    @Test fun requestsGoToTheProxyWithItsKey() = runBlocking {
        server.enqueue(json(answer))
        assertEquals("""{"text":"hello"}""", OpenAiClient().complete(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA))
        val r = server.takeRequest()
        assertEquals("/v1/responses", r.url.encodedPath)
        assertEquals("Bearer proxy-key", r.headers["Authorization"])
        assertTrue(r.body!!.utf8().contains("\"model\":\"claude-sonnet\""))
    }

    /** Test is a tiny real request to /responses (LiteLLM keeps /models/{id} for its admins). */
    @Test fun checkAsksTheModel() = runBlocking {
        server.enqueue(json(answer))
        OpenAiClient().check(config)
        assertEquals("/v1/responses", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().code(401).body("""{"error":{"message":"Authentication Error, Invalid proxy server token passed."}}""").build())
        try {
            OpenAiClient().check(config)
            fail("a refused key passed")
        } catch (e: AiException) {
            assertTrue(e.message, e.message!!.contains("Invalid proxy server token"))
        }
    }

    /** No background mode at the proxy: asked again as one ordinary request. */
    @Test fun longAnswerWithoutBackgroundMode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"Unsupported parameter: background"}}""").build())
        server.enqueue(json(answer))
        val text = OpenAiClient().completeLong(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA, "medium")
        assertEquals("""{"text":"hello"}""", text)
        assertTrue(server.takeRequest().body!!.utf8().contains("\"background\":true"))
        assertTrue(!server.takeRequest().body!!.utf8().contains("background"))
    }

    /** A model that doesn't reason turns down the effort: asked again without it, and from then on. */
    @Test fun modelWithoutReasoning() = runBlocking {
        val client = OpenAiClient()
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":{"message":"litellm.BadRequestError: Unsupported parameter: 'reasoning.effort' is not supported with this model."}}""").build())
        server.enqueue(json(answer))
        assertEquals("""{"text":"hello"}""", client.complete(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA))
        assertTrue(server.takeRequest().body!!.utf8().contains("\"reasoning\""))
        assertTrue(!server.takeRequest().body!!.utf8().contains("reasoning"))
        server.enqueue(json(answer))
        client.complete(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA)
        assertTrue(!server.takeRequest().body!!.utf8().contains("reasoning"))
    }

    /** Stopped while the AI works in the background: it's told to stop too. */
    @Test fun stoppingCancelsTheBackgroundResponse() = runBlocking {
        server.enqueue(json("""{"id":"r9","status":"in_progress"}"""))
        repeat(5) { server.enqueue(json("""{"id":"r9","status":"in_progress"}""")) }
        server.enqueue(json("""{"id":"r9","status":"cancelled"}"""))
        val job = launch(Dispatchers.IO) {
            OpenAiClient().completeLong(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA, "medium")
        }
        server.takeRequest() // the request itself
        server.takeRequest() // the first look at how it's going: its id is known by now
        job.cancelAndJoin()
        val paths = generateSequence { server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS) }.map { it.method + " " + it.url.encodedPath }.toList()
        assertTrue(paths.toString(), "POST /v1/responses/r9/cancel" in paths)
    }

    /** Stopped while waiting for an ordinary answer: the request is dropped, not waited out. */
    @Test fun stoppingDropsTheRequest() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).headersDelay(30, java.util.concurrent.TimeUnit.SECONDS).body(answer).build())
        val job = launch(Dispatchers.IO) { OpenAiClient().complete(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA) }
        server.takeRequest()
        val took = kotlin.system.measureTimeMillis { job.cancelAndJoin() }
        assertTrue("took $took ms", took < 5_000)
    }

    /** A proxy that answers a background request at once. */
    @Test fun longAnswerGivenAtOnce() = runBlocking {
        server.enqueue(json(answer))
        assertEquals("""{"text":"hello"}""", OpenAiClient().completeLong(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA, "medium"))
    }
}
