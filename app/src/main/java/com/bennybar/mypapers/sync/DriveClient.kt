package com.bennybar.mypapers.sync

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A file in the app's Drive folder; [updatedAt]: the paper's edit time, kept on the file (null for a paper's file). */
data class DriveFile(val id: String, val name: String, val updatedAt: Long?)

/** One entry of Drive's change feed; [file] null = removed. */
data class DriveChange(val fileId: String, val file: DriveFile?)

/** [next]: the token to read on from; [done]: nothing more for now. */
class ChangePage(val changes: List<DriveChange>, val next: String, val done: Boolean)

class DriveException(message: String, val code: Int = 0) : IOException(message)

/** Drive access is gone (revoked, or never allowed on this phone): the user has to allow it again. */
class DriveAuthException : IOException("Allow Google Drive access again in Settings")

/** The app's own hidden folder in the user's Google Drive (appDataFolder), as the sync needs it. */
interface DriveStore {
    suspend fun startPageToken(): String
    suspend fun listAll(): List<DriveFile>
    suspend fun changes(token: String): ChangePage
    /** The file named [name], or null. */
    suspend fun find(name: String): String?
    suspend fun read(id: String): ByteArray
    suspend fun download(id: String, to: File)
    /** Writes [content] to file [id] (a new file named [name] if null); returns its id. Throws [DriveException] 404 if [id] is gone. */
    suspend fun put(id: String?, name: String, updatedAt: Long?, content: RequestBody): String
    /** Deleting one already gone is fine. */
    suspend fun delete(id: String)
}

/** The Drive REST API (v3), with tokens from [token]; [clearToken] drops one Google turned down. */
class DriveClient(
    private val token: suspend () -> String,
    private val clearToken: suspend (String) -> Unit,
    private val base: String = "https://www.googleapis.com",
) : DriveStore {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .build()

    override suspend fun startPageToken(): String =
        json(get("$base/drive/v3/changes/startPageToken"))["startPageToken"]!!.jsonPrimitive.content

    override suspend fun listAll(): List<DriveFile> {
        val all = mutableListOf<DriveFile>()
        var page: String? = null
        do {
            val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter("spaces", "appDataFolder")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("fields", "nextPageToken,files(id,name,appProperties)")
                .apply { page?.let { addQueryParameter("pageToken", it) } }
                .build().toString()
            val o = json(get(url))
            (o["files"] as? JsonArray).orEmpty().forEach { all += driveFile(it.jsonObject) }
            page = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        } while (page != null)
        return all
    }

    override suspend fun changes(token: String): ChangePage {
        val url = "$base/drive/v3/changes".toHttpUrl().newBuilder()
            .addQueryParameter("pageToken", token)
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("fields", "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,trashed,appProperties))")
            .build().toString()
        val o = json(get(url))
        val changes = (o["changes"] as? JsonArray).orEmpty().map { e ->
            val c = e.jsonObject
            val file = c["file"] as? JsonObject
            val gone = c["removed"]?.jsonPrimitive?.booleanOrNull == true || file?.get("trashed")?.jsonPrimitive?.booleanOrNull == true
            DriveChange(c["fileId"]!!.jsonPrimitive.content, if (gone || file == null) null else driveFile(file))
        }
        val next = o["nextPageToken"]?.jsonPrimitive?.contentOrNull
        return if (next != null) ChangePage(changes, next, done = false)
        else ChangePage(changes, o["newStartPageToken"]!!.jsonPrimitive.content, done = true)
    }

    override suspend fun find(name: String): String? {
        val url = "$base/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("q", "name = '${name.replace("'", "\\'")}' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .build().toString()
        return (json(get(url))["files"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
    }

    override suspend fun read(id: String): ByteArray = call { Request.Builder().url("$base/drive/v3/files/$id?alt=media") }.use { it.body.bytes() }

    override suspend fun download(id: String, to: File) {
        call { Request.Builder().url("$base/drive/v3/files/$id?alt=media") }.use { r ->
            withContext(Dispatchers.IO) { to.outputStream().use { out -> r.body.byteStream().copyTo(out) } }
        }
    }

    // A resumable upload (fine for big files too): the file's details first, then its content.
    override suspend fun put(id: String?, name: String, updatedAt: Long?, content: RequestBody): String {
        val metadata = buildJsonObject {
            if (id == null) {
                put("name", name)
                putJsonArray("parents") { add(kotlinx.serialization.json.JsonPrimitive("appDataFolder")) }
            }
            putJsonObject("appProperties") { put("updatedAt", updatedAt?.toString()) }
        }.toString().toRequestBody(JSON)
        val session = call {
            val url = if (id == null) "$base/upload/drive/v3/files?uploadType=resumable" else "$base/upload/drive/v3/files/$id?uploadType=resumable"
            Request.Builder().url(url).method(if (id == null) "POST" else "PATCH", metadata)
        }.use { it.header("Location") ?: throw DriveException("Drive gave no upload address") }
        return call { Request.Builder().url(session).put(content) }.use { r ->
            Json.parseToJsonElement(r.body.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
    }

    override suspend fun delete(id: String) {
        try {
            call { Request.Builder().url("$base/drive/v3/files/$id").delete() }.close()
        } catch (e: DriveException) {
            if (e.code != 404) throw e
        }
    }

    private suspend fun get(url: String) = call { Request.Builder().url(url) }

    private suspend fun json(r: Response): JsonObject = r.use { Json.parseToJsonElement(it.body.string()).jsonObject }

    /** One request; a turned-down token is dropped and the request tried once more with a fresh one. */
    private suspend fun call(build: () -> Request.Builder): Response = withContext(Dispatchers.IO) {
        var t = token()
        repeat(2) { attempt ->
            val r = http.newCall(build().header("Authorization", "Bearer $t").build()).execute()
            if (r.isSuccessful) return@withContext r
            val text = r.body.string()
            r.close()
            if (r.code == 401 && attempt == 0) {
                clearToken(t)
                t = token()
                return@repeat
            }
            if (r.code == 401) throw DriveAuthException()
            val message = runCatching {
                Json.parseToJsonElement(text).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
            }.getOrNull() ?: text.take(200)
            throw DriveException("Google Drive (${r.code}): $message", r.code)
        }
        throw DriveAuthException()
    }

    private fun driveFile(o: JsonObject) = DriveFile(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        updatedAt = (o["appProperties"] as? JsonObject)?.get("updatedAt")?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
    )

    companion object {
        val JSON = "application/json; charset=UTF-8".toMediaType()

        /** Drops a token Google no longer accepts, so the next one asked for is fresh. */
        suspend fun clearCachedToken(context: Context, token: String) = withContext(Dispatchers.IO) {
            runCatching { GoogleAuthUtil.clearToken(context, token) }
            Unit
        }
    }
}
