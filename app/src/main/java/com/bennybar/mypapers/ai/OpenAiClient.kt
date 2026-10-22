package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.KeyDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** [retryable]: worth trying again later (rate limit, server trouble), unlike a bad key. */
/** [code]: the HTTP status it came with, 0 if none. */
class AiException(message: String, val retryable: Boolean, val code: Int = 0) : IOException(message)

/** The OpenAI Responses API, with the user's own key: OpenAI itself, or an OpenAI-compatible proxy (LiteLLM…). */
class OpenAiClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Checks the key and that the model exists for it. */
    suspend fun check(config: AiConfig) = withContext(Dispatchers.IO) {
        fun get(path: String) = http.newCall(Request.Builder().url("${config.endpoint}$path").header("Authorization", "Bearer ${config.apiKey}").build()).execute()
        get("/models/${config.model}").use { r ->
            if (r.isSuccessful) return@withContext
            if (r.code != 404) throw error(r.code, r.body.string(), config)
        }
        // Some proxies only list their models: look for it there.
        get("/models").use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw error(r.code, text, config)
            val ids = runCatching { Json.parseToJsonElement(text).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull } }.getOrDefault(emptyList())
            if (config.model !in ids) throw AiException("Model not found at ${config.provider}: ${config.model}", retryable = false)
        }
    }

    /**
     * Reads a document. [parts]: the file as Responses API content parts
     * (an input_file for a PDF, input_image for photos), see [AiInput].
     */
    suspend fun extract(config: AiConfig, prompt: String, parts: List<JsonObject>): Extraction =
        parseExtraction(complete(config, prompt, parts, "document", ExtractionPrompt.SCHEMA))

    /** Models (by server and name) that turned down a reasoning effort: asked without one from then on. */
    private val noReasoning = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun AiConfig.key() = "$endpoint|$model"

    /** [effort], unless this model is known to turn it down. */
    private fun effortFor(config: AiConfig, effort: String) = effort.takeUnless { config.key() in noReasoning }

    /**
     * A model that doesn't reason (GPT-4o, many behind a proxy) turns down a reasoning effort
     * with a 400 that names it: remembered, so the request can go again without one.
     */
    private fun turnedDownReasoning(config: AiConfig, e: AiException, sent: String?): Boolean {
        if (sent == null || e.code != 400 || e.message?.contains("reasoning", ignoreCase = true) != true) return false
        noReasoning += config.key()
        return true
    }

    /** Any structured answer: [schema] is the JSON schema it must follow; returns the answer's JSON text. */
    suspend fun complete(config: AiConfig, prompt: String, parts: List<JsonObject>, schemaName: String, schema: String, effort: String = "low"): String = withContext(Dispatchers.IO) {
        val reasoning = effortFor(config, effort)
        val body = requestBody(config.model, prompt, parts, schemaName, schema, reasoning).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("${config.endpoint}/responses")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()
        try {
            http.newCall(request).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) throw error(r.code, text, config)
                outputText(text)
            }
        } catch (e: AiException) {
            if (!turnedDownReasoning(config, e, reasoning)) throw e
            complete(config, prompt, parts, schemaName, schema, effort)
        }
    }

    /**
     * Like [complete], for answers that take minutes (writing a whole page out): OpenAI works on it
     * in the background and it's fetched when done, instead of one connection held open all along.
     */
    suspend fun completeLong(config: AiConfig, prompt: String, parts: List<JsonObject>, schemaName: String, schema: String, effort: String): String = withContext(Dispatchers.IO) {
        val reasoning = effortFor(config, effort)
        val body = JsonObject(requestBody(config.model, prompt, parts, schemaName, schema, reasoning) + ("background" to JsonPrimitive(true)))
        fun call(request: Request): JsonObject = http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw error(r.code, text, config)
            Json.parseToJsonElement(text).jsonObject
        }
        val auth = "Bearer ${config.apiKey}"
        var response = try {
            call(
                Request.Builder().url("${config.endpoint}/responses").header("Authorization", auth)
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build(),
            )
        } catch (e: AiException) {
            if (turnedDownReasoning(config, e, reasoning)) return@withContext completeLong(config, prompt, parts, schemaName, schema, effort)
            // A proxy without background mode: one ordinary (long) request instead.
            if (config.baseUrl.isBlank() || e.retryable) throw e
            return@withContext complete(config, prompt, parts, schemaName, schema, effort)
        }
        // A proxy may answer at once, without background mode.
        val id = response["id"]?.jsonPrimitive?.contentOrNull ?: return@withContext outputText(response.toString())
        val started = System.currentTimeMillis()
        while (response["status"]?.jsonPrimitive?.contentOrNull in setOf("queued", "in_progress")) {
            if (System.currentTimeMillis() - started > LONG_LIMIT_MS) throw AiException("${config.provider} took too long", retryable = false)
            kotlinx.coroutines.delay(POLL_MS)
            response = call(Request.Builder().url("${config.endpoint}/responses/$id").header("Authorization", auth).build())
        }
        outputText(response.toString())
    }

    private fun error(code: Int, body: String, config: AiConfig): AiException {
        val message = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull() ?: body.take(200)
        val who = config.provider
        return when (code) {
            401 -> AiException("$who refused the key: $message", retryable = false, code = code)
            404 -> AiException("Not found at $who: $message", retryable = false, code = code)
            429, in 500..599 -> AiException("$who: $message", retryable = true, code = code)
            else -> AiException("$who ($code): $message", retryable = false, code = code)
        }
    }

    companion object {
        private const val POLL_MS = 4_000L
        private const val LONG_LIMIT_MS = 30 * 60_000L

        /** [effort] null: none asked for (a model that doesn't reason). */
        fun requestBody(model: String, prompt: String, parts: List<JsonObject>, schemaName: String, schema: String, effort: String? = "low"): JsonObject = buildJsonObject {
            put("model", model)
            if (effort != null) putJsonObject("reasoning") { put("effort", effort) }
            putJsonArray("input") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject { put("type", "input_text"); put("text", prompt) })
                            parts.forEach { add(it) }
                        }
                    },
                )
            }
            putJsonObject("text") {
                putJsonObject("format") {
                    put("type", "json_schema")
                    put("name", schemaName)
                    put("strict", true)
                    put("schema", Json.parseToJsonElement(schema))
                }
            }
        }

        /** A document reading from a full Responses API answer. */
        fun parseResponse(body: String): Extraction = parseExtraction(outputText(body))

        /** The answer is the output_text of the "message" output item (after a "reasoning" one). */
        fun outputText(body: String): String {
            val root = Json.parseToJsonElement(body).jsonObject
            return root["output"]?.jsonArray.orEmpty()
                .map { it.jsonObject }
                .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
                .flatMap { (it["content"] as? JsonArray).orEmpty() }
                .map { it.jsonObject }
                .firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "output_text" }
                ?.get("text")?.jsonPrimitive?.content
                ?: throw AiException("The AI returned no answer (${root["status"]?.jsonPrimitive?.contentOrNull})", retryable = true)
        }

        fun parseExtraction(text: String): Extraction {
            val o = Json.parseToJsonElement(text).jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun num(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
            return Extraction(
                title = str("title").orEmpty(),
                vendor = str("vendor").orEmpty(),
                docType = str("docType").orEmpty(),
                date = str("date"),
                total = num("total"),
                currency = str("currency"),
                vat = num("vat"),
                tags = (o["tags"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                keywords = (o["keywords"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                text = str("text").orEmpty(),
                details = (o["details"] as? JsonArray)?.mapNotNull { e ->
                    val d = e as? JsonObject ?: return@mapNotNull null
                    val label = (d["label"] as? JsonPrimitive)?.contentOrNull?.trim()
                    val value = (d["value"] as? JsonPrimitive)?.contentOrNull?.trim()
                    if (label.isNullOrEmpty() || value.isNullOrEmpty()) null else KeyDetail(label, value)
                }.orEmpty(),
            )
        }
    }
}
