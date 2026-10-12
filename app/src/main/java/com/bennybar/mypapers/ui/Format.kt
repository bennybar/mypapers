package com.bennybar.mypapers.ui

import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency
import java.util.Locale

/** Amounts and dates in the phone's format. */
object Format {
    fun amount(cents: Long, currency: String?, locale: Locale = Locale.getDefault()): String {
        val value = BigDecimal.valueOf(cents, 2)
        val code = currency?.let { runCatching { Currency.getInstance(it) }.getOrNull() }
            ?: return NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2 }.format(value)
        return NumberFormat.getCurrencyInstance(locale).apply { this.currency = code }.format(value)
    }

    /** For an edit field: the number without the currency, e.g. "1,249.00". */
    fun amountInput(cents: Long?, locale: Locale = Locale.getDefault()): String =
        cents?.let { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(BigDecimal.valueOf(it, 2)) }.orEmpty()

    /** "1,249.5" (or "1.249,5" where that's the format) → 124950; null if it isn't a number. */
    fun parseAmount(text: String, locale: Locale = Locale.getDefault()): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val format = NumberFormat.getNumberInstance(locale) as DecimalFormat
        format.isParseBigDecimal = true
        val pos = java.text.ParsePosition(0)
        val n = format.parse(t, pos) as BigDecimal? ?: return null
        if (pos.index != t.length) return null
        return n.movePointRight(2).toLong()
    }

    fun date(iso: String?, locale: Locale = Locale.getDefault()): String? =
        iso?.let { runCatching { LocalDate.parse(it).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }.getOrNull() }
}
