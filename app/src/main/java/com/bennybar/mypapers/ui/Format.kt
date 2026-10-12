package com.bennybar.mypapers.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/**
 * Amounts and dates in the chosen region's format (Settings → Dates and amounts;
 * the phone's by default). [locale] is Compose state: screens showing a date or
 * an amount redraw when it changes.
 */
object Format {
    var locale: Locale by mutableStateOf(Locale.getDefault())

    /** The default currency for new papers, from the region (₪ for Israel). */
    fun defaultCurrency(): String = runCatching { Currency.getInstance(locale).currencyCode }.getOrDefault("")

    /** A date and time, e.g. for naming scans. */
    fun dateTime(t: LocalDateTime): String =
        t.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))

    /** A day, from epoch milliseconds (e.g. when an email was sent). */
    fun day(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate()
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))

    fun amount(cents: Long, currency: String?, locale: Locale = this.locale): String {
        val value = BigDecimal.valueOf(cents, 2)
        val code = currency?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
            ?: return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2 }.format(value)
        return NumberFormat.getCurrencyInstance(locale).apply { this.currency = code }.format(value)
    }

    /** For an edit field: the number without the currency, e.g. "1,249.00". */
    fun amountInput(cents: Long?, locale: Locale = this.locale): String =
        cents?.let { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(it, 2)) }.orEmpty()

    /** "1,249.5" (or "1.249,5" where that's the format) → 124950; null if it isn't a number. */
    fun parseAmount(text: String, locale: Locale = this.locale): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
        format.isParseBigDecimal = true
        val pos = java.text.ParsePosition(0)
        val n = format.parse(t, pos) as BigDecimal? ?: return null
        if (pos.index != t.length) return null
        return n.movePointRight(2).toLong()
    }

    fun date(iso: String?, locale: Locale = this.locale): String? =
        iso?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrNull() }
}
