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
class AiException(message: String, val retryable: Boolean) : IOException(message)

/** The OpenAI Responses API, with the user's own key. */
class OpenAiClient(private val baseUrl: String = "https://api.openai.com/v1") {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Checks the key and that the model exists for it. */
    suspend fun check(config: AiConfig) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/models/${config.model}").header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(request).execute().use { r -> if (!r.isSuccessful) throw error(r.code, r.body.string()) }
    }

    /**
     * Reads a document. [parts]: the file as Responses API content parts
     * (an input_file for a PDF, input_image for photos), see [AiInput].
     */
    suspend fun extract(config: AiConfig, prompt: String, parts: List<JsonObject>): Extraction =
        parseExtraction(complete(config, prompt, parts, "document", ExtractionPrompt.SCHEMA))

    /** Any structured answer: [schema] is the JSON schema it must follow; returns the answer's JSON text. */
    suspend fun complete(config: AiConfig, prompt: String, parts: List<JsonObject>, schemaName: String, schema: String): String = withContext(Dispatchers.IO) {
        val body = requestBody(config.model, prompt, parts, schemaName, schema).toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/responses")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body)
            .build()
        http.newCall(request).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw error(r.code, text)
            outputText(text)
        }
    }

    private fun error(code: Int, body: String): AiException {
        val message = runCatching {
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
        }.getOrNull() ?: body.take(200)
        return when (code) {
            401 -> AiException("The OpenAI key was refused", retryable = false)
            404 -> AiException("Model not found: $message", retryable = false)
            429, in 500..599 -> AiException("OpenAI: $message", retryable = true)
            else -> AiException("OpenAI ($code): $message", retryable = false)
        }
    }

    companion object {
        fun requestBody(model: String, prompt: String, parts: List<JsonObject>, schemaName: String, schema: String): JsonObject = buildJsonObject {
            put("model", model)
            putJsonObject("reasoning") { put("effort", "low") }
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
                ?: throw AiException("OpenAI returned no answer (${root["status"]?.jsonPrimitive?.contentOrNull})", retryable = true)
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
