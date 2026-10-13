package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.Document
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A question about all the papers ("where did I buy the washing machine?"), in two steps:
 * the model picks the papers that may help from a one-line-each list, then answers from
 * their whole text. Papers are numbered in the prompts (shorter than their ids).
 */
object AskAll {
    /** Papers read in full for the answer; the others it picked still count for a total. */
    const val MAX_READ = 10
    private const val MAX_TEXT_CHARS = 10_000

    /** One line per paper: number, date, type, title, vendor, amount, tags, keywords, details. */
    fun line(n: Int, d: Document): String = listOf(
        "#$n",
        d.date.orEmpty(),
        d.docType,
        d.title,
        d.vendor,
        d.totalCents?.let { String.format(java.util.Locale.ROOT, "%.2f %s", it / 100.0, d.currency.orEmpty()) }.orEmpty(),
        d.tags.joinToString(", "),
        d.keywords.joinToString(", "),
        d.details.joinToString("; ") { "${it.label}: ${it.value.replace('\n', ' ').take(80)}" },
    ).joinToString(" | ")

    fun pick(question: String, docs: List<Document>): String = """
        You help someone find things in their personal archive of receipts and documents. They ask: "${question.trim()}"
        Below is one line per paper: number | date | type | title | vendor | amount | tags | keywords | details.
        - papers: the numbers of every paper that may help answer it, the most useful first (at most 100). None if nothing fits.
        - total: true if the answer is an amount added up over papers (how much spent or paid), else false.

        ${docs.mapIndexed { i, d -> line(i + 1, d) }.joinToString("\n")}
    """.trimIndent()

    const val PICK_SCHEMA = """{"type":"object","additionalProperties":false,"required":["papers","total"],
"properties":{"papers":{"type":"array","items":{"type":"integer"}},"total":{"type":"boolean"}}}"""

    /** [read]: the papers given in full; [listed]: more picked ones, by their line only. */
    fun answer(question: String, total: Boolean, read: List<Pair<Int, Document>>, listed: List<Pair<Int, Document>>): String = buildString {
        appendLine("Answer a question about someone's personal archive of receipts and documents, from the papers below. They ask: \"${question.trim()}\"")
        appendLine("- answer: short and direct, in the language of the question. Name the papers it comes from by their title. Copy names, numbers and dates exactly as written. If the papers don't answer it, say so plainly.")
        if (total) appendLine("  Don't add amounts up yourself: the app shows the total of the papers you list.")
        appendLine("- papers: the numbers of the papers the answer is based on${if (total) " (for a total: every paper that counts towards it)" else ""}.")
        for ((n, d) in read) {
            appendLine()
            appendLine(line(n, d))
            if (d.notes.isNotBlank()) appendLine("Notes: ${d.notes}")
            val text = d.fullText.ifBlank { d.extractedText }
            if (text.isNotBlank()) appendLine("Text:\n${text.take(MAX_TEXT_CHARS)}")
        }
        if (listed.isNotEmpty()) {
            appendLine()
            appendLine("More papers (their text isn't included):")
            listed.forEach { (n, d) -> appendLine(line(n, d)) }
        }
    }

    const val ANSWER_SCHEMA = """{"type":"object","additionalProperties":false,"required":["answer","papers"],
"properties":{"answer":{"type":"string"},"papers":{"type":"array","items":{"type":"integer"}}}}"""

    fun numbers(json: String): List<Int> =
        (Json.parseToJsonElement(json).jsonObject["papers"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.intOrNull }.distinct()

    fun isTotal(json: String) = Json.parseToJsonElement(json).jsonObject["total"]?.jsonPrimitive?.booleanOrNull == true

    fun answerText(json: String) = Json.parseToJsonElement(json).jsonObject["answer"]?.jsonPrimitive?.contentOrNull.orEmpty()

    /** Amounts added up per currency (cents), papers without an amount left out. */
    fun totals(docs: List<Document>): Map<String, Long> =
        docs.filter { it.totalCents != null }.groupBy { it.currency.orEmpty() }.mapValues { (_, l) -> l.sumOf { it.totalCents!! } }
}

/** What [AskAll] came back with; [totals] (per currency, in cents) only for a question about amounts. */
data class AskAllAnswer(val answer: String, val papers: List<Document>, val totals: Map<String, Long>?)
