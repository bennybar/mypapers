package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.AI_DONE
import com.bennybar.mypapers.data.DOCUMENT_TYPES
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.KeyDetail
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
    val details: List<KeyDetail> = emptyList(),
    val keywords: List<String> = emptyList(),
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
            - title: a short, clear name for it, the way someone would label it in a folder: what it is and from whom, plus the month for periodic bills (e.g. "019 Mobile bill, September 2026", "Shufersal groceries", "Washing machine warranty"). Never a numeric date (the date has its own field). In the document's own language. Not the file name.
            - vendor: the business or issuer, named the way a person would say it (e.g. "Shufersal", not "SHUFERSAL DEAL BRANCH 123"), in the document's own language and script. Empty if there is none.
            - docType: Receipt (proof of a payment), Invoice (a bill or tax invoice, paid or to pay), Warranty (a warranty or guarantee), Contract (an agreement or policy), or Other.
            - date: the document's date (purchase or issue) as YYYY-MM-DD, or null. Read numeric dates the way the document's country writes them: day before month for Israeli, European and most non-US documents (12/10/2026 is 12 October 2026), month before day for US documents. Only if the document gives no clue, numeric dates are ${dateOrder}. A document's date is almost never in the future.
            - total: the final amount paid or due, tax included, as a number; null if none.
            - currency: ISO 4217 code. If only a symbol or nothing is printed, infer it from the country and language; otherwise $currency.
            - vat: the VAT or sales-tax amount if printed, else null.
            - tags: 1 to 3 short category tags. Existing tags: $tags. Reuse an existing tag, spelled exactly, whenever one fits; add a new one only if none fits, in $tagLanguage, Title Case.
            - details: the specific facts worth keeping for this kind of document, as short label/value pairs, in the document's language. Examples: a glasses or contact-lens prescription — one entry per eye with SPH, CYL, AXIS and ADD (e.g. "Right eye (OD)" → "SPH -1.25 · CYL -0.50 · AXIS 90 · ADD +1.00"), plus PD and the prescription date; insurance — policy number, coverage period, insured; warranty — period, serial or model number; subscription — plan, renewal date, account number; bills — account or customer number, billing period, due date; medical — the test results that matter. Empty for an ordinary shop receipt. When the details are what matters (a prescription), the total can be null. Write an expiry, renewal, valid-until or due date as YYYY-MM-DD, with a label that says which ("Valid until", "Renewal date", "Due date"), so the app can remind before it.
            - keywords: 4 to 8 short search terms someone might type to find this document, in English AND in the document's language when that isn't English (e.g. "eye prescription", "glasses", "optometrist", "מרשם משקפיים", "בדיקת ראייה").
            - text: the document's readable text, in full, up to about 6000 characters.
        """.trimIndent()
    }

    /**
     * A question or instruction about one paper, in a conversation: [record] is the paper as the app
     * has it now, [history] the earlier turns. Finds things (details), refines an earlier answer,
     * or proposes changes to the record.
     */
    fun ask(record: String, history: List<PaperTurn>, instruction: String): String = buildString {
        appendLine("You help with one document from a personal paper archive (attached). The app's record of it now:")
        appendLine(record)
        if (history.isNotEmpty()) {
            appendLine()
            appendLine("Earlier in this conversation:")
            history.forEach { t ->
                appendLine("User: ${t.question}")
                appendLine("You: ${t.reply.orEmpty()}")
                t.details?.forEach { appendLine("  ${it.label}: ${it.value}") }
            }
        }
        appendLine()
        appendLine("Now the user says: \"${instruction.trim()}\"")
        appendLine()
        appendLine("""
            - To find, extract, explain or reshape information ("extract the URL", "as a table", "only section 3", "in English"): put it in details, short label/value pairs in the document's language unless asked otherwise (e.g. "URL" → "https://…"), values copied exactly as printed, dates as YYYY-MM-DD. A follow-up that reshapes an earlier answer returns the whole reshaped answer. For a table (line items, a schedule, results), or when asked for one, one entry whose value is the table: one row per line, cells separated by " | ", the first row the column headers.
            - To change the record (title, vendor, type, date, amount, currency, tags, removing a detail by its label): fill only those fields of changes; leave the rest null or empty. Types: Receipt, Invoice, Warranty, Contract, Other. Dates YYYY-MM-DD.
            - reply: one or two sentences in the user's language: what you found, what will change, or that the document doesn't have it.
            - Never invent what isn't in the document or the user's words.
        """.trimIndent())
    }

    /** All of a paper's text, written out (a handwritten page too). */
    const val TRANSCRIBE = """
        Write out all the text of the attached document as Markdown, every word, in its own language and script, top to bottom.
        Keep its structure: "#"/"##" headings for titles, "1." for numbered sections, "-" for bullets, **bold** for underlined or emphasized words, Markdown tables (with a |---| row) for tables, a blank line between paragraphs.
        The page comes whole and then in overlapping close-ups (rows top to bottom, columns left to right as in the picture): read the words from the close-ups, use the whole page for the order, and write overlapping text once.
        Handwriting too: work out each word from its letters and the sentence around it, and write your best reading even when unsure. Use [?] only for a word that's truly illegible; never leave out a part or summarize. No commentary of your own.
    """

    const val TRANSCRIBE_SCHEMA = """{"type":"object","additionalProperties":false,"required":["text"],"properties":{"text":{"type":"string"}}}"""

    const val ASK_SCHEMA = """{"type":"object","additionalProperties":false,"required":["reply","details","changes"],
"properties":{"reply":{"type":"string"},
"details":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","value"],"properties":{"label":{"type":"string"},"value":{"type":"string"}}}},
"changes":{"type":"object","additionalProperties":false,"required":["title","vendor","docType","date","total","currency","addTags","removeTags","removeDetails"],
"properties":{"title":{"type":["string","null"]},"vendor":{"type":["string","null"]},"docType":{"type":["string","null"]},"date":{"type":["string","null"]},
"total":{"type":["number","null"]},"currency":{"type":["string","null"]},"addTags":{"type":"array","items":{"type":"string"}},
"removeTags":{"type":"array","items":{"type":"string"}},"removeDetails":{"type":"array","items":{"type":"string"}}}}}}"""

    /** The JSON schema the answer must follow (strict structured output). */
    const val SCHEMA = """{"type":"object","additionalProperties":false,
"required":["title","vendor","docType","date","total","currency","vat","tags","details","keywords","text"],
"properties":{"title":{"type":"string"},"vendor":{"type":"string"},"docType":{"type":"string","enum":["Receipt","Invoice","Warranty","Contract","Other"]},
"date":{"type":["string","null"]},"total":{"type":["number","null"]},"currency":{"type":["string","null"]},
"vat":{"type":["number","null"]},"tags":{"type":"array","items":{"type":"string"}},"keywords":{"type":"array","items":{"type":"string"}},
"details":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","value"],"properties":{"label":{"type":"string"},"value":{"type":"string"}}}},"text":{"type":"string"}}}"""
}

/**
 * [details] with the [found] ones added; one with the same label (any case) is replaced.
 */
fun mergeDetails(details: List<KeyDetail>, found: List<KeyDetail>): List<KeyDetail> {
    val labels = found.map { it.label.lowercase() }.toSet()
    return details.filter { it.label.lowercase() !in labels } + found
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

private val finalForms = mapOf('מ' to 'ם', 'נ' to 'ן', 'צ' to 'ץ', 'פ' to 'ף', 'כ' to 'ך')
private val hebrewWord = Regex("[\u05D0-\u05EA]+")

/**
 * Hebrew words end in the final letter forms (ם ן ץ ף ך), which models sometimes
 * miss ("לוקרימ" → "לוקרים"). A word followed by a geresh or gershayim (abbreviations,
 * "צ'יפס") is left alone.
 */
fun fixHebrewFinals(s: String): String = hebrewWord.replace(s) { m ->
    val next = s.getOrNull(m.range.last + 1)
    val last = m.value.last()
    val final = finalForms[last]
    if (final == null || m.value.length < 2 || next == '\'' || next == '"' || next == '׳' || next == '״') m.value
    else m.value.dropLast(1) + final
}

/** How much of a paper's text search looks through (more makes typing in search slow). */
const val SEARCH_TEXT_CHARS = 1500

/**
 * [doc] with what the model read. Tags are added to the ones already there, and
 * a tag that matches an existing one (any case) uses its spelling. Fields in
 * [Document.edited] were corrected by hand and stay as they are.
 */
fun Document.withExtraction(e: Extraction, existingTags: List<String>, today: LocalDate = LocalDate.now()): Document {
    fun cents(v: Double?) = v?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong() }
    val newTags = e.tags.map { t -> t.trim() }.filter { it.isNotEmpty() }
        .map { t -> existingTags.firstOrNull { it.equals(t, ignoreCase = true) } ?: t }
    fun keep(field: String) = field in edited
    return copy(
        title = if (keep("title")) title else fixHebrewFinals(e.title.trim()).ifBlank { title },
        vendor = if (keep("vendor")) vendor else fixHebrewFinals(e.vendor.trim()).ifBlank { vendor },
        docType = if (keep("docType")) docType else e.docType.takeIf { it in DOCUMENT_TYPES } ?: docType,
        date = if (keep("date")) date else e.date?.takeIf { runCatching { LocalDate.parse(it) }.isSuccess }?.let { fixFutureDate(it, today) } ?: date,
        totalCents = if (keep("total")) totalCents else cents(e.total) ?: totalCents,
        currency = if (keep("currency")) currency else e.currency?.trim()?.uppercase()?.takeIf { runCatching { Currency.getInstance(it) }.isSuccess } ?: currency,
        vatCents = if (keep("vat")) vatCents else cents(e.vat) ?: vatCents,
        tags = if (keep("tags")) tags else (tags + newTags).distinctBy { it.lowercase() },
        // Search looks at the beginning; questions across papers at all of it (unless the PDF's own text is kept).
        extractedText = e.text.trim().take(SEARCH_TEXT_CHARS).ifBlank { extractedText },
        fullText = fullText.ifBlank { e.text.trim() },
        // Ones saved from Ask AI stay (replacing a read one with the same label).
        details = if (keep("details")) details else mergeDetails(
            e.details.filter { it.label.isNotBlank() && it.value.isNotBlank() }.ifEmpty { details.filterNot { it.asked } },
            details.filter { it.asked },
        ),
        keywords = e.keywords.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.ifEmpty { keywords },
        aiState = AI_DONE,
        aiError = null,
    )
}
