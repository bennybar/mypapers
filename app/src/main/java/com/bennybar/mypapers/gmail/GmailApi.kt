package com.bennybar.mypapers.gmail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** [needsReconnect]: Google no longer accepts the app's access (the user must connect Gmail again). */
class GmailException(message: String, val needsReconnect: Boolean = false) : IOException(message)

/** Any other error answer from Gmail. */
class GmailHttpException(val code: Int, message: String) : IOException(message)

/**
 * The Gmail API, read-only (gmail.readonly). [token] gives a fresh access token;
 * it's asked again once if Google says the current one expired.
 */
class GmailApi(private val token: suspend (refresh: Boolean) -> String, private val baseUrl: String = "https://gmail.googleapis.com/gmail/v1/users/me") {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Ids of the emails matching a Gmail search (same syntax as the Gmail search box). */
    suspend fun search(query: String): List<String> {
        val ids = mutableListOf<String>()
        var pageToken: String? = null
        do {
            val url = "$baseUrl/messages".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("maxResults", "500")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = get(url)
            (page["messages"] as? JsonArray)?.forEach { m -> m.jsonObject.str("id")?.let(ids::add) }
            pageToken = page.str("nextPageToken")
        } while (pageToken != null)
        return ids
    }

    /** The full message (headers and part tree; small bodies inline, attachments by id). */
    suspend fun message(id: String): JsonObject = get("$baseUrl/messages/$id".toHttpUrl().newBuilder().addQueryParameter("format", "full").build())

    /**
     * Several messages, a few requests at a time (Gmail allows about 50 a second;
     * [get] waits and retries when it says to slow down). An email deleted since the
     * search is skipped; any other failure fails the whole batch rather than losing emails.
     */
    suspend fun messages(ids: List<String>): List<JsonObject> = coroutineScope {
        val parallel = Semaphore(5)
        ids.map { id ->
            async {
                parallel.withPermit {
                    try {
                        message(id)
                    } catch (e: GmailHttpException) {
                        if (e.code == 404) null else throw e
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /** Saves a part's content (an attachment, or a body too big to come inline) into [to]. */
    suspend fun savePart(gmailId: String, part: JsonObject, to: File) {
        val bytes = partBytes(gmailId, part) ?: throw GmailException("The attachment is empty")
        withContext(Dispatchers.IO) { to.writeBytes(bytes) }
    }

    /** The email's HTML (plain-text emails as preformatted text), with its sender and subject on top. */
    suspend fun emailHtml(gmailId: String, message: JsonObject): String {
        val payload = message["payload"]?.jsonObject ?: throw GmailException("The email has no content")
        val html = findBody(payload, "text/html")?.let { partBytes(gmailId, it)?.toString(Charsets.UTF_8) }
        val text = if (html == null) findBody(payload, "text/plain")?.let { partBytes(gmailId, it)?.toString(Charsets.UTF_8) } else null
        val header = "<p style=\"font-family:sans-serif;color:#555;font-size:12px\">" +
            "${escape(header(payload, "From").orEmpty())} · ${escape(header(payload, "Subject").orEmpty())}</p>"
        return when {
            html != null -> header + html
            text != null -> "$header<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${escape(text)}</pre>"
            else -> throw GmailException("The email has no text")
        }
    }

    private suspend fun partBytes(gmailId: String, part: JsonObject): ByteArray? {
        val body = part["body"]?.jsonObject ?: return null
        val data = body.str("data") ?: body.str("attachmentId")?.let { attachmentId ->
            get("$baseUrl/messages/$gmailId/attachments/$attachmentId".toHttpUrl()).str("data")
        } ?: return null
        return Base64.getUrlDecoder().decode(data)
    }

    private suspend fun get(url: HttpUrl, refreshed: Boolean = false, attempt: Int = 0): JsonObject {
        val (code, text) = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("Authorization", "Bearer ${token(refreshed)}").build()
            http.newCall(request).execute().use { r -> r.code to r.body.string() }
        }
        val rateLimited = code == 429 || (code == 403 && (text.contains("rateLimitExceeded") || text.contains("userRateLimitExceeded")))
        return when {
            code in 200..299 -> Json.parseToJsonElement(text).jsonObject
            // Too fast, or Gmail having a moment: wait (1, 2, 4, 8 s) and try again.
            (rateLimited || code >= 500) && attempt < 4 -> {
                delay(1000L shl attempt)
                get(url, refreshed, attempt + 1)
            }
            code == 401 && !refreshed -> get(url, refreshed = true, attempt = attempt) // expired: once more with a fresh token
            code == 401 -> throw GmailException("Gmail access was revoked. Connect Gmail again.", needsReconnect = true)
            code == 403 && text.contains("accessNotConfigured") -> throw GmailException("The Gmail API isn't enabled in the Google Cloud project")
            code == 403 && text.contains("insufficient", ignoreCase = true) -> throw GmailException("Connect Gmail again in Settings", needsReconnect = true)
            else -> throw GmailHttpException(code, "Gmail ($code): ${errorMessage(text)}")
        }
    }

    companion object {
        /** Images smaller than this are logos, signatures and tracking pixels, not photographed receipts. */
        private const val MIN_IMAGE_BYTES = 30_000

        /** Gmail searches: attachments, and emails that read like receipts (most of which have no attachment). */
        fun attachmentQuery(window: String) =
            "newer_than:$window has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png OR filename:heic)"

        /**
         * Receipts written in the email: by subject, or Gmail's Purchases category when the
         * email talks money — minus shipping and tracking updates, which Gmail files there too.
         */
        fun receiptQuery(window: String) =
            "newer_than:$window (subject:(receipt OR invoice OR \"tax invoice\" OR \"order confirmation\" OR \"payment confirmation\" OR \"payment received\" OR \"your order\" OR קבלה OR חשבונית OR \"אישור הזמנה\" OR \"אישור תשלום\") " +
                "OR (category:purchases (receipt OR invoice OR total OR קבלה OR חשבונית))) " +
                "-subject:(shipped OR delivered OR \"out for delivery\" OR \"at customs\" OR tracking OR package OR shipment OR משלוח OR נשלח OR \"נמסר\")"

        /** An attachment worth offering: its part id, and what it is. */
        data class PaperPart(val partId: String, val fileName: String, val mimeType: String, val size: Long)

        /** The PDFs and photos attached to an email, skipping logos and pictures shown inside it. */
        fun paperParts(message: JsonObject): List<PaperPart> {
            val found = mutableListOf<PaperPart>()
            message["payload"]?.jsonObject?.let { walk(it) { part -> paperPart(part)?.let(found::add) } }
            return found
        }

        /** What's in the found email list: who, what about, when. */
        fun header(payload: JsonObject, name: String): String? =
            (payload["headers"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it.str("name").equals(name, ignoreCase = true) }?.str("value")

        /** "Shufersal <noreply@shufersal.co.il>" → "Shufersal". */
        fun senderName(from: String): String =
            from.substringBefore('<').trim().trim('"').ifBlank { from.substringAfter('<').substringBefore('>') }

        fun partById(message: JsonObject, partId: String): JsonObject? {
            var found: JsonObject? = null
            message["payload"]?.jsonObject?.let { walk(it) { p -> if (p.str("partId") == partId) found = p } }
            return found
        }

        private fun walk(part: JsonObject, visit: (JsonObject) -> Unit) {
            val children = part["parts"] as? JsonArray
            if (children.isNullOrEmpty()) visit(part) else children.forEach { walk(it.jsonObject, visit) }
        }

        private fun findBody(payload: JsonObject, type: String): JsonObject? {
            var found: JsonObject? = null
            walk(payload) { p ->
                val attachment = header(p, "Content-Disposition")?.startsWith("attachment", ignoreCase = true) == true
                if (found == null && !attachment && p.str("mimeType").equals(type, ignoreCase = true)) found = p
            }
            return found
        }

        private fun paperPart(part: JsonObject): PaperPart? {
            val name = part.str("filename").orEmpty()
            val type = part.str("mimeType").orEmpty().lowercase()
            val ext = name.substringAfterLast('.', "").lowercase()
            val isPdf = type == "application/pdf" || ext == "pdf"
            val isImage = type.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "heic")
            if (!isPdf && !isImage) return null
            val size = part["body"]?.jsonObject?.get("size")?.let { (it as? JsonPrimitive)?.longOrNull } ?: 0L
            if (isImage) {
                if (size < MIN_IMAGE_BYTES) return null
                // Pictures shown inside the email (logos, banners) rather than attached.
                val disposition = header(part, "Content-Disposition").orEmpty().lowercase()
                val inline = disposition.startsWith("inline") || (header(part, "Content-ID") != null && name.isEmpty())
                if (inline && !disposition.startsWith("attachment")) return null
            }
            val fileName = name.ifEmpty { if (isPdf) "attachment.pdf" else "photo.${ext.ifEmpty { "jpg" }}" }
            val mime = if (isPdf) "application/pdf" else type.takeIf { it.startsWith("image/") } ?: "image/jpeg"
            return PaperPart(part.str("partId").orEmpty(), fileName, mime, size)
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun errorMessage(body: String) = runCatching {
            (Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"] as JsonPrimitive).content
        }.getOrNull() ?: body.take(200)

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
