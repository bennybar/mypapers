package com.bennybar.mypapers.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * When a paper runs out (a warranty, a policy, a subscription's renewal, a bill's due date),
 * from its key details: a detail whose label says so ("Renewal date", "Valid until", "תוקף")
 * and whose value is a date. The AI writes these as 2027-08-15; older readings may say 15/08/2027.
 */
object Expiry {
    /** How far ahead "expiring soon" looks. */
    const val SOON_DAYS = 60

    private val LABEL = Regex("expir|valid|renew|due|until|thru|through|ends?\\b|end date|תוקף|תפוג|חידוש|לתשלום", RegexOption.IGNORE_CASE)
    private val ISO = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val NUMERIC = Regex("""\b(\d{1,2})[./-](\d{1,2})[./-](\d{4}|\d{2})\b""")
    private val MONTH_YEAR = Regex("""\b(\d{1,2})[./-](\d{4})\b""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val NAMED = Regex("""\b(?:(\d{1,2})\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+(?:(\d{1,2}),?\s+)?(\d{4})\b""", RegexOption.IGNORE_CASE)

    /**
     * The paper's next expiry or due date (a bill due next week before a validity ending next year),
     * else the latest one already past; null if none. [dayFirst]: how to read 03/04/2027 (the region setting).
     */
    fun date(doc: Document, dayFirst: Boolean, today: LocalDate = LocalDate.now()): LocalDate? {
        val dates = doc.details.filter { LABEL.containsMatchIn(it.label) }.mapNotNull { parse(it.value, dayFirst) }
        return dates.filter { !it.isBefore(today) }.minOrNull() ?: dates.maxOrNull()
    }

    /** Days from [today] to the expiry: negative once past. */
    fun daysLeft(expires: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, expires)

    fun isSoon(doc: Document, today: LocalDate, dayFirst: Boolean): Boolean =
        date(doc, dayFirst, today)?.let { daysLeft(it, today) in 0..SOON_DAYS.toLong() } == true

    fun parse(value: String, dayFirst: Boolean): LocalDate? {
        ISO.find(value)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        NUMERIC.find(value)?.let { m ->
            val (a, b, y) = m.destructured.toList().map { it.toInt() }
            val year = if (y < 100) 2000 + y else y
            // A number over 12 can only be the day, whatever the region.
            val (day, month) = when {
                a > 12 -> a to b
                b > 12 -> b to a
                dayFirst -> a to b
                else -> b to a
            }
            return runCatching { LocalDate.of(year, month, day) }.getOrNull()
        }
        NAMED.find(value)?.let { m ->
            val month = MONTHS.indexOf(m.groupValues[2].lowercase().take(3)) + 1
            val year = m.groupValues[4].toInt()
            val day = m.groupValues[1].ifEmpty { m.groupValues[3] }.toIntOrNull()
            return runCatching { if (day != null) LocalDate.of(year, month, day) else YearMonth.of(year, month).atEndOfMonth() }.getOrNull()
        }
        // "08/2027": valid to the end of that month.
        MONTH_YEAR.find(value)?.let { m ->
            val (mo, y) = m.destructured
            return runCatching { YearMonth.of(y.toInt(), mo.toInt()).atEndOfMonth() }.getOrNull()
        }
        return null
    }
}
