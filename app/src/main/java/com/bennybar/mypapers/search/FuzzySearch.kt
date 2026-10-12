package com.bennybar.mypapers.search

import com.bennybar.mypapers.data.Document
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One search box over vendor, tags, type, file name, notes and the document's
 * text, forgiving typos ("shufersl" finds Shufersal) and partial words. A number
 * also matches amounts by closeness: "120" ranks ₪118 and ₪125 first.
 * Every word typed must match something; best matches first.
 */
object FuzzySearch {
    /** How far off an amount may be and still match, relative to the number typed. */
    private const val AMOUNT_TOLERANCE = 0.5

    fun search(docs: List<Document>, query: String, tags: Set<String> = emptySet(), locale: Locale = Locale.getDefault()): List<Document> {
        val tagged = if (tags.isEmpty()) docs else docs.filter { it.tags.containsAll(tags) }
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return tagged.sortedWith(newestFirst)

        val scored = tagged.mapNotNull { doc ->
            val fields = fields(doc)
            var total = 0.0
            for (token in tokens) {
                val amount = parseAmount(token, locale)?.let { amountScore(doc, it) } ?: 0.0
                val text = normalize(token).split(' ').filter { it.isNotEmpty() }
                    .map { t -> fields.maxOf { (words, weight) -> weight * words.maxOfOrNull { tokenScore(t, it) }.orZero() } }
                    .minOrNull().orZero()
                val best = max(amount, text)
                if (best <= 0.0) return@mapNotNull null
                total += best
            }
            doc to total
        }
        if (scored.isEmpty()) {
            // A lone number with nothing near it: still show the closest amounts.
            val amount = tokens.singleOrNull()?.let { parseAmount(it, locale) } ?: return emptyList()
            return tagged.filter { it.totalCents != null }
                .sortedBy { abs(it.totalCents!! / 100.0 - amount) }
                .take(5)
        }
        return scored.sortedWith(compareByDescending<Pair<Document, Double>> { it.second }.thenBy(newestFirst) { it.first })
            .map { it.first }
    }

    private val newestFirst: Comparator<Document> =
        compareByDescending<Document> { it.date ?: "" }.thenByDescending { it.createdAt }

    private fun Double?.orZero() = this ?: 0.0

    /** Each field's words with how much a match there counts. */
    private fun fields(doc: Document): List<Pair<List<String>, Double>> = listOf(
        words(doc.vendor) to 3.0,
        doc.tags.flatMap(::words) to 3.0,
        words(doc.docType) to 2.0,
        words(doc.fileName.orEmpty()) to 1.0,
        words(doc.notes) to 1.0,
        words(doc.extractedText) to 1.0,
    )

    private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() }

    /**
     * Lower case, no accents or Hebrew vowel points, Hebrew final letters as
     * their regular form (so "שלום" matches typed "שלומ"), punctuation as spaces.
     */
    fun normalize(s: String): String {
        val stripped = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return buildString(stripped.length) {
            for (c in stripped) append(
                when (c) {
                    'ך' -> 'כ'; 'ם' -> 'מ'; 'ן' -> 'נ'; 'ף' -> 'פ'; 'ץ' -> 'צ'
                    else -> if (c.isLetterOrDigit()) c else ' '
                },
            )
        }.trim()
    }

    /** 0 = no match, up to 1 = the same word. */
    fun tokenScore(token: String, word: String): Double {
        if (word == token) return 1.0
        if (word.startsWith(token)) return 0.9
        if (token.length >= 3 && word.contains(token)) return 0.7
        if (token.length < 4) return 0.0
        val allowed = if (token.length <= 5) 1 else 2
        // Whole word or its beginning, so a typo in a partial word still matches.
        val d = min(distance(token, word), distance(token, word.take(token.length)))
        return if (d <= allowed) 0.6 - 0.1 * d else 0.0
    }

    /** 0 = no match, up to 3 (like a vendor match) for the exact amount. */
    private fun amountScore(doc: Document, amount: Double): Double {
        val total = doc.totalCents?.div(100.0) ?: return 0.0
        val rel = abs(total - amount) / max(abs(amount), 1.0)
        return if (rel > AMOUNT_TOLERANCE) 0.0 else 3.0 * (1 - rel / AMOUNT_TOLERANCE) + 0.01
    }

    /**
     * The token as an amount, or null if it isn't one: "120", "₪120", "1,249",
     * "118.50" (and "118,50" where the comma is the decimal separator).
     */
    fun parseAmount(token: String, locale: Locale): Double? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        // Any letters (e.g. an invoice number like "INV2026"): not an amount.
        if (token.any { it.isLetter() }) return null
        val s = token.filter { it.isDigit() || it == '.' || it == ',' }
        if (s.none { it.isDigit() }) return null
        val decimal = symbols.decimalSeparator
        val plain = s.replace(symbols.groupingSeparator.toString(), "").replace(decimal, '.')
        return plain.toDoubleOrNull()
    }

    /** Optimal-string-alignment distance: edits incl. swapping two neighbours. */
    fun distance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
        }
        return d[a.length][b.length]
    }
}
