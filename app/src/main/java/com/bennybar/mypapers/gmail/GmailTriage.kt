package com.bennybar.mypapers.gmail

import com.bennybar.mypapers.ai.AiConfig
import com.bennybar.mypapers.ai.OpenAiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * The AI's first pass over what Gmail found, before anything is downloaded: from
 * the sender, subject, file name and Gmail's preview of the text it decides what
 * each item is (receipt, bill, contract… or not a paper at all), the vendor and
 * the amount when visible — so the review screen can show them sorted and
 * labeled. Cheap: a batch of items per request, no files.
 */
object GmailTriage {
    /** In review-screen order; [NOT_A_PAPER] last. */
    val CATEGORIES = listOf("Receipt", "Invoice", "Bill", "Statement", "Contract", "Warranty", "Ticket", "Document", NOT_A_PAPER)
    const val NOT_A_PAPER = "Not a paper"

    /** How many items go in one request. */
    const val BATCH = 40

    data class Result(val key: String, val category: String, val title: String?, val vendor: String?, val totalCents: Long?, val currency: String?, val paper: Boolean)

    private val SCHEMA = """{"type":"object","additionalProperties":false,"required":["items"],
"properties":{"items":{"type":"array","items":{"type":"object","additionalProperties":false,
"required":["id","category","title","vendor","total","currency","paper"],
"properties":{"id":{"type":"string"},"title":{"type":"string"},
"category":{"type":"string","enum":[${CATEGORIES.joinToString(",") { "\"$it\"" }}]},
"vendor":{"type":["string","null"]},"total":{"type":["number","null"]},"currency":{"type":["string","null"]},
"paper":{"type":"boolean"}}}}}}"""

    private const val PROMPT = """You sort what was found in someone's Gmail for their archive of receipts and documents.
For each item (an attachment, or a receipt written in the email itself) decide from the sender, subject, file name and text preview:
- category: Receipt (proof of a payment), Invoice (a bill or tax invoice), Bill (utilities, phone, internet, rent, school fees), Statement (bank, credit card, insurance, pension), Contract (agreements, policies, forms to keep), Warranty, Ticket (tickets, bookings, reservations), Document (any other document worth keeping), or "Not a paper" (newsletters, marketing, ads, social, notifications, shipping and tracking updates, things nobody files).
- title: a short, clear name for it, the way someone would label it in a folder: what it is, from whom, and for when if known (e.g. "019 Mobile bill, September 2026", "Apple receipt, iCloud+", "School trip payment"). In the item's own language. Not the file name.
- vendor: the business or organisation, named the way a person would say it, in its own language; null if unclear.
- total: the amount paid or due if the subject or preview shows one, as a number; else null. currency: its ISO 4217 code, or null.
- paper: true if it's worth keeping in a paper archive.
Answer for every item, with its id."""

    suspend fun sort(ai: OpenAiClient, config: AiConfig, items: List<GmailCandidate>): List<Result> {
        val list = buildJsonArray {
            items.forEach { c ->
                add(
                    buildJsonObject {
                        put("id", c.key)
                        put("from", c.sender)
                        put("subject", c.subject)
                        put("file", if (c.kind == GmailCandidate.KIND_EMAIL) "(the email itself)" else c.fileName)
                        put("preview", c.snippet.take(300))
                    },
                )
            }
        }
        val answer = ai.complete(config, PROMPT + "\n\nItems:\n" + list, emptyList(), "gmail_triage", SCHEMA)
        return parse(answer, items.map { it.key }.toSet())
    }

    /** The answer's items that match ones asked about, with sane values. */
    fun parse(answer: String, keys: Set<String>): List<Result> =
        (Json.parseToJsonElement(answer).jsonObject["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val key = str("id")?.takeIf { it in keys } ?: return@mapNotNull null
            val category = str("category")?.takeIf { it in CATEGORIES } ?: "Document"
            val total = (o["total"] as? JsonPrimitive)?.doubleOrNull
            val currency = str("currency")?.uppercase()?.takeIf { runCatching { Currency.getInstance(it) }.isSuccess }
            Result(
                key = key,
                category = category,
                title = str("title")?.trim()?.ifBlank { null },
                vendor = str("vendor")?.trim()?.ifBlank { null },
                totalCents = total?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() },
                currency = currency,
                paper = (o["paper"] as? JsonPrimitive)?.booleanOrNull ?: (category != NOT_A_PAPER),
            )
        }
}
