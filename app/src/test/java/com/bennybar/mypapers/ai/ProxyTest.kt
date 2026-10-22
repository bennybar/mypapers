package com.bennybar.mypapers.ai

import kotlinx.coroutines.runBlocking
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

    /** A proxy that doesn't know /models/{id} still passes the test when the model is in its list. */
    @Test fun checkFallsBackToTheModelList() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        server.enqueue(json("""{"data":[{"id":"gpt-x"},{"id":"claude-sonnet"}]}"""))
        OpenAiClient().check(config)
        server.takeRequest()
        assertEquals("/v1/models", server.takeRequest().url.encodedPath)

        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        server.enqueue(json("""{"data":[{"id":"gpt-x"}]}"""))
        try {
            OpenAiClient().check(config)
            fail("a model the proxy doesn't have passed")
        } catch (e: AiException) {
            assertTrue(e.message, e.message!!.contains("claude-sonnet"))
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

    /** A proxy that answers a background request at once. */
    @Test fun longAnswerGivenAtOnce() = runBlocking {
        server.enqueue(json(answer))
        assertEquals("""{"text":"hello"}""", OpenAiClient().completeLong(config, "p", emptyList(), "t", ExtractionPrompt.TRANSCRIBE_SCHEMA, "medium"))
    }
}
