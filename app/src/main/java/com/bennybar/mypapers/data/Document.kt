package com.bennybar.mypapers.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A receipt or document. The same fields live in CouchDB (one CouchDB doc per
 * row, the file as its `file` attachment); the rest is local sync bookkeeping.
 */
@Entity(tableName = "documents")
data class Document(
    /** Also the CouchDB `_id`. */
    @PrimaryKey val id: String,
    /** A short, clear name ("019 Mobile bill, September 2026"), from the AI or typed; "" = none. */
    val title: String = "",
    val vendor: String = "",
    /** Receipt, Invoice, Warranty, Contract, Other; blank until set. */
    val docType: String = "",
    /** ISO date, yyyy-MM-dd. */
    val date: String? = null,
    /** Amounts in hundredths of the currency unit (₪12.50 = 1250). */
    val totalCents: Long? = null,
    /** ISO 4217 code, e.g. ILS. */
    val currency: String? = null,
    val vatCents: Long? = null,
    val tags: List<String> = emptyList(),
    val notes: String = "",
    /** The document's text as read by the AI, for search. */
    val extractedText: String = "",
    /** The facts worth keeping for this kind of paper (a prescription's values, a policy number…), from the AI. */
    val details: List<KeyDetail> = emptyList(),
    /** A few search words from the AI, in English and in the paper's language ("eye prescription", "מרשם משקפיים"). */
    val keywords: List<String> = emptyList(),
    /** SHA-256 of the file, for duplicate detection. */
    val fileHash: String? = null,
    val fileName: String? = null,
    val mimeType: String? = null,
    val createdAt: Long,
    /** Last edit (epoch ms) — the newer side wins a sync conflict. */
    val updatedAt: Long,
    /** AI reading: "" (not asked), [AI_PENDING], [AI_DONE] or [AI_FAILED]. */
    val aiState: String = "",
    /** Why the AI reading failed, for [AI_FAILED]. */
    val aiError: String? = null,
    /** Another document this one seems to repeat (same vendor, amount and date), until kept or deleted. */
    val duplicateOf: String? = null,
    /** In the trash since (epoch ms): hidden, restorable, deleted for good after [TRASH_DAYS]; null = not. */
    val trashedAt: Long? = null,

    // Sync bookkeeping (local only).
    /** The CouchDB revision this row is based on; null = never pushed. */
    val rev: String? = null,
    /** Has local changes not yet on the server. */
    val dirty: Boolean = true,
    /** Deleted here, waiting to push the deletion. */
    val deleted: Boolean = false,
    /** The server has this row's current file. */
    val fileUploaded: Boolean = false,
    /** The file is on this phone (in [DocumentFiles]). */
    val fileLocal: Boolean = false,
)

/** One labeled fact on a paper: "Right eye" → "SPH −1.25 · CYL −0.50 · AXIS 90". */
@kotlinx.serialization.Serializable
data class KeyDetail(val label: String, val value: String)

/** How long a paper stays in the trash. */
const val TRASH_DAYS = 30

val DOCUMENT_TYPES = listOf("Receipt", "Invoice", "Warranty", "Contract", "Other")

const val AI_PENDING = "pending"
const val AI_DONE = "done"
const val AI_FAILED = "failed"
