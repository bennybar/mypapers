package com.bennybar.mypapers.search

import com.bennybar.mypapers.data.Document
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DateWindow { THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, CUSTOM }

enum class SortBy { NEWEST, OLDEST, AMOUNT_HIGH, AMOUNT_LOW, VENDOR }

/**
 * The filter sheet's choices (types and tags are the chips above the list).
 * A paper without a date counts by the day it was added.
 */
data class Filters(
    val date: DateWindow? = null,
    /** For [DateWindow.CUSTOM], inclusive. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val minCents: Long? = null,
    val maxCents: Long? = null,
    val vendors: Set<String> = emptySet(),
    /** No amount, date or vendor yet. */
    val missingInfo: Boolean = false,
    val duplicates: Boolean = false,
    /** Not read by the AI and not filled in. */
    val unread: Boolean = false,
    /** null: best match first when searching, newest first otherwise. */
    val sort: SortBy? = null,
) {
    val activeCount: Int
        get() = listOf(date != null, minCents != null || maxCents != null, vendors.isNotEmpty(), missingInfo, duplicates, unread, sort != null).count { it }

    /** The date window's first and last day. */
    fun range(today: LocalDate): Pair<LocalDate, LocalDate>? = when (date) {
        DateWindow.THIS_MONTH -> today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        DateWindow.LAST_MONTH -> today.minusMonths(1).let { it.withDayOfMonth(1) to it.withDayOfMonth(it.lengthOfMonth()) }
        DateWindow.THIS_YEAR -> today.withDayOfYear(1) to today.withDayOfYear(today.lengthOfYear())
        DateWindow.LAST_YEAR -> today.minusYears(1).let { it.withDayOfYear(1) to it.withDayOfYear(it.lengthOfYear()) }
        DateWindow.CUSTOM -> (from ?: LocalDate.MIN) to (to ?: LocalDate.MAX)
        null -> null
    }

    fun matches(d: Document, today: LocalDate): Boolean {
        range(today)?.let { (first, last) ->
            val day = paperDay(d)
            if (day.isBefore(first) || day.isAfter(last)) return false
        }
        if (minCents != null || maxCents != null) {
            val total = d.totalCents ?: return false
            if (minCents != null && total < minCents) return false
            if (maxCents != null && total > maxCents) return false
        }
        if (vendors.isNotEmpty() && d.vendor !in vendors) return false
        if (missingInfo && d.totalCents != null && d.date != null && d.vendor.isNotBlank()) return false
        if (duplicates && d.duplicateOf == null) return false
        if (unread && !((d.aiState.isEmpty() || d.aiState == "failed") && d.title.isBlank() && d.vendor.isBlank() && d.totalCents == null)) return false
        return true
    }

    fun sorted(docs: List<Document>): List<Document> = when (sort) {
        SortBy.NEWEST -> docs.sortedByDescending(::paperDay)
        SortBy.OLDEST -> docs.sortedBy(::paperDay)
        // Papers without an amount last either way.
        SortBy.AMOUNT_HIGH -> docs.sortedWith(compareBy<Document> { it.totalCents == null }.thenByDescending { it.totalCents })
        SortBy.AMOUNT_LOW -> docs.sortedWith(compareBy<Document> { it.totalCents == null }.thenBy { it.totalCents })
        SortBy.VENDOR -> docs.sortedWith(compareBy<Document> { it.vendor.isBlank() }.thenBy { it.vendor.lowercase() })
        null -> docs
    }

    companion object {
        fun paperDay(d: Document): LocalDate =
            d.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: Instant.ofEpochMilli(d.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}
