package com.bennybar.mypapers.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class CouchException(val code: Int, message: String) : IOException(message)

/** One page of the `_changes` feed. */
data class Changes(val results: List<JsonObject>, val lastSeq: String)

/**
 * The few CouchDB HTTP calls the sync needs, against one database.
 * [authorization]: the Authorization header for every request (e.g. "Bearer <token>").
 */
class CouchClient(serverUrl: String, private val db: String, private val authorization: String) {
    private val base: HttpUrl = serverUrl.trimEnd('/').toHttpUrl()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().addPathSegment(db).apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private suspend fun <T> call(request: Request.Builder, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        http.newCall(request.header("Authorization", authorization).build()).execute().use { r ->
            if (!r.isSuccessful) throw CouchException(r.code, "${r.code} ${r.body.string().take(200)}")
            read(r)
        }
    }

    private suspend fun callJson(request: Request.Builder): JsonObject =
        call(request) { Json.parseToJsonElement(it.body.string()).jsonObject }

    /** Checks the login and that the database is reachable. */
    suspend fun ping(): JsonObject = callJson(Request.Builder().url(url()))

    /** The doc, or null if it doesn't exist (or was deleted). */
    suspend fun get(id: String): JsonObject? = try {
        callJson(Request.Builder().url(url(id)))
    } catch (e: CouchException) {
        if (e.code == 404) null else throw e
    }

    /** Creates or updates a doc (its `_rev` in [body] when updating); returns the new rev. */
    suspend fun put(id: String, body: JsonObject): String =
        callJson(Request.Builder().url(url(id)).put(body.toString().toRequestBody(json)))["rev"]!!.jsonPrimitive.content

    /** Adds or replaces the doc's attachment; returns the new rev. */
    suspend fun putAttachment(id: String, rev: String, name: String, file: File, mimeType: String): String =
        callJson(
            Request.Builder().url(url(id, name, query = mapOf("rev" to rev)))
                .put(file.asRequestBody(mimeType.toMediaType())),
        )["rev"]!!.jsonPrimitive.content

    /** Streams an attachment into [to]. */
    suspend fun getAttachment(id: String, name: String, to: File) =
        call(Request.Builder().url(url(id, name))) { r ->
            to.outputStream().use { out -> r.body.byteStream().copyTo(out) }
        }

    suspend fun changes(since: String, limit: Int): Changes {
        val r = callJson(
            Request.Builder().url(
                url("_changes", query = mapOf("since" to since, "include_docs" to "true", "limit" to limit.toString())),
            ),
        )
        val results = (r["results"] as JsonArray).map { it.jsonObject }
        // last_seq is an opaque string in CouchDB 2+ (a number in 1.x).
        return Changes(results, r["last_seq"]!!.jsonPrimitive.content)
    }
}
