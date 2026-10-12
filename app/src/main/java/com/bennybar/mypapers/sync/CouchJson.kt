package com.bennybar.mypapers.sync

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.KeyDetail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** The attachment holding a document's file. */
const val FILE_ATTACHMENT = "file"

private const val TYPE = "document"

/**
 * The CouchDB body for [doc]. [keepFile]: the server already has this
 * document's current file — a stub keeps it (a PUT without `_attachments`
 * would drop it).
 */
fun Document.toCouchJson(keepFile: Boolean): JsonObject = buildJsonObject {
    put("_id", id)
    rev?.let { put("_rev", it) }
    if (deleted) {
        // Kept as a tombstone with its time, so the newer side wins against an edit.
        put("_deleted", true)
        put("updatedAt", updatedAt)
        return@buildJsonObject
    }
    put("type", TYPE)
    put("title", title)
    put("vendor", vendor)
    put("docType", docType)
    put("date", date)
    put("totalCents", totalCents)
    put("currency", currency)
    put("vatCents", vatCents)
    putJsonArray("tags") { tags.forEach { add(JsonPrimitive(it)) } }
    putJsonArray("keywords") { keywords.forEach { add(JsonPrimitive(it)) } }
    put("notes", notes)
    put("extractedText", extractedText)
    putJsonArray("details") { details.forEach { d -> add(buildJsonObject { put("label", d.label); put("value", d.value) }) } }
    put("fileHash", fileHash)
    put("fileName", fileName)
    put("mimeType", mimeType)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    put("aiState", aiState)
    put("aiError", aiError)
    put("duplicateOf", duplicateOf)
    put("trashedAt", trashedAt)
    putJsonArray("edited") { edited.forEach { add(JsonPrimitive(it)) } }
    if (keepFile) putJsonObject("_attachments") { putJsonObject(FILE_ATTACHMENT) { put("stub", true) } }
}

/** True for a CouchDB doc that is (or was, if deleted) one of ours. */
fun JsonObject.isDocumentOrTombstone(): Boolean =
    isDeleted() || this["type"]?.jsonPrimitive?.contentOrNull == TYPE

fun JsonObject.isDeleted(): Boolean = this["_deleted"]?.jsonPrimitive?.booleanOrNull == true

fun JsonObject.docId(): String = this["_id"]!!.jsonPrimitive.content

fun JsonObject.fileHash(): String? = str("fileHash")

fun JsonObject.rev(): String = this["_rev"]!!.jsonPrimitive.content

fun JsonObject.updatedAt(): Long = long("updatedAt") ?: 0L

fun JsonObject.hasFile(): Boolean = (this["_attachments"] as? JsonObject)?.containsKey(FILE_ATTACHMENT) == true

private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

/** A synced (clean) row from a server doc; local file state is decided by the caller. */
fun JsonObject.toDocument(fileLocal: Boolean): Document = Document(
    id = str("_id")!!,
    title = str("title").orEmpty(),
    vendor = str("vendor").orEmpty(),
    docType = str("docType").orEmpty(),
    date = str("date"),
    totalCents = long("totalCents"),
    currency = str("currency"),
    vatCents = long("vatCents"),
    tags = (this["tags"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
    keywords = (this["keywords"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
    edited = (this["edited"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
    notes = str("notes").orEmpty(),
    extractedText = str("extractedText").orEmpty(),
    details = (this["details"] as? JsonArray)?.mapNotNull { e ->
        (e as? JsonObject)?.let { o ->
            val label = (o["label"] as? JsonPrimitive)?.contentOrNull
            val value = (o["value"] as? JsonPrimitive)?.contentOrNull
            if (label.isNullOrBlank() || value.isNullOrBlank()) null else KeyDetail(label, value)
        }
    }.orEmpty(),
    fileHash = str("fileHash"),
    fileName = str("fileName"),
    mimeType = str("mimeType"),
    createdAt = long("createdAt") ?: 0L,
    updatedAt = updatedAt(),
    aiState = str("aiState").orEmpty(),
    aiError = str("aiError"),
    duplicateOf = str("duplicateOf"),
    trashedAt = long("trashedAt"),
    rev = str("_rev"),
    dirty = false,
    deleted = false,
    fileUploaded = hasFile(),
    fileLocal = fileLocal,
)

/** The `_changes` row's doc (with `include_docs=true`). */
fun JsonObject.changeDoc(): JsonObject? = this["doc"] as? JsonObject
