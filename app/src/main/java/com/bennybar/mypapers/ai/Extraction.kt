package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.AI_DONE
import com.bennybar.mypapers.data.DOCUMENT_TYPES
import com.bennybar.mypapers.data.Document
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** What the model read off a document (the JSON schema's fields). */
data class Extraction(
    val vendor: String,
    val docType: String,
    val date: String?,
    val total: Double?,
    val currency: String?,
    val vat: Double?,
    val tags: List<String>,
    val text: String,
    val title: String = "",
)

object ExtractionPrompt {
    /** Day before month in this locale's short dates (03/04 = 3 April)? */
    fun dayFirst(locale: Locale): Boolean {
        val pattern = DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.SHORT, null, IsoChronology.INSTANCE, locale)
        return pattern.indexOf('d') < pattern.indexOf('M')
    }

    fun build(existingTags: List<String>, locale: Locale): String {
        val currency = runCatching { Currency.getInstance(locale).currencyCode }.getOrDefault("USD")
        val dateOrder = if (dayFirst(locale)) "day before month (03/04/2026 is 3 April 2026)" else "month before day (03/04/2026 is March 4, 2026)"
        val tags = if (existingTags.isEmpty()) "(none yet)" else existingTags.joinToString(", ")
        val tagLanguage = if (existingTags.isEmpty()) "English" else "the same language as the existing tags"
        return """
            You read receipts and documents for a personal paper archive. Fill every field from the attached document.
            - title: a short, clear name for it, the way someone would label it in a folder: what it is, from whom, and for when if known (e.g. "019 Mobile bill, September 2026", "Shufersal groceries", "Washing machine warranty"). In the document's own language. Not the file name.
            - vendor: the business or issuer, named the way a person would say it (e.g. "Shufersal", not "SHUFERSAL DEAL BRANCH 123"), in the document's own language and script. Empty if there is none.
            - docType: Receipt (proof of a payment), Invoice (a bill or tax invoice, paid or to pay), Warranty (a warranty or guarantee), Contract (an agreement or policy), or Other.
            - date: the document's date (purchase or issue) as YYYY-MM-DD, or null. Read numeric dates the way the document's country writes them: day before month for Israeli, European and most non-US documents (12/10/2026 is 12 October 2026), month before day for US documents. Only if the document gives no clue, numeric dates are ${dateOrder}. A document's date is almost never in the future.
            - total: the final amount paid or due, tax included, as a number; null if none.
            - currency: ISO 4217 code. If only a symbol or nothing is printed, infer it from the country and language; otherwise $currency.
            - vat: the VAT or sales-tax amount if printed, else null.
            - tags: 1 to 3 short category tags. Existing tags: $tags. Reuse an existing tag, spelled exactly, whenever one fits; add a new one only if none fits, in $tagLanguage, Title Case.
            - text: the document's readable text, up to about 1500 characters, for search.
        """.trimIndent()
    }

    /** The JSON schema the answer must follow (strict structured output). */
    const val SCHEMA = """{"type":"object","additionalProperties":false,
"required":["title","vendor","docType","date","total","currency","vat","tags","text"],
"properties":{"title":{"type":"string"},"vendor":{"type":"string"},"docType":{"type":"string","enum":["Receipt","Invoice","Warranty","Contract","Other"]},
"date":{"type":["string","null"]},"total":{"type":["number","null"]},"currency":{"type":["string","null"]},
"vat":{"type":["number","null"]},"tags":{"type":"array","items":{"type":"string"}},"text":{"type":"string"}}}"""
}

/**
 * A date read with day and month the wrong way round lands in the future
 * (12/10 read as December 10 in October): swapped, it's a real past date.
 */
fun fixFutureDate(iso: String, today: LocalDate): String {
    val d = runCatching { LocalDate.parse(iso) }.getOrNull() ?: return iso
    if (!d.isAfter(today) || d.dayOfMonth > 12) return iso
    val swapped = runCatching { LocalDate.of(d.year, d.dayOfMonth, d.monthValue) }.getOrNull() ?: return iso
    return if (swapped.isAfter(today)) iso else swapped.toString()
}

/**
 * [doc] with what the model read. Tags are added to the ones already there, and
 * a tag that matches an existing one (any case) uses its spelling.
 */
fun Document.withExtraction(e: Extraction, existingTags: List<String>, today: LocalDate = LocalDate.now()): Document {
    fun cents(v: Double?) = v?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() }
    val newTags = e.tags.map { t -> t.trim() }.filter { it.isNotEmpty() }
        .map { t -> existingTags.firstOrNull { it.equals(t, ignoreCase = true) } ?: t }
    return copy(
        title = e.title.trim().ifBlank { title },
        vendor = e.vendor.trim().ifBlank { vendor },
        docType = e.docType.takeIf { it in DOCUMENT_TYPES } ?: docType,
        date = e.date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }?.let { fixFutureDate(it, today) } ?: date,
        totalCents = cents(e.total) ?: totalCents,
        currency = e.currency?.trim()?.uppercase()?.takeIf { runCatching { Currency.getInstance(it) }.isSuccess } ?: currency,
        vatCents = cents(e.vat) ?: vatCents,
        tags = (tags + newTags).distinctBy { it.lowercase() },
        extractedText = e.text.trim().ifBlank { extractedText },
        aiState = AI_DONE,
        aiError = null,
    )
}
