package com.bennybar.mypapers.data

import com.bennybar.mypapers.search.FuzzySearch

/**
 * The same paper brought in twice in different files (a scan and the emailed PDF):
 * same amount and date, and a vendor that reads the same. The identical file is
 * caught earlier, by its hash.
 */
object Duplicates {
    fun find(doc: Document, others: List<Document>): Document? {
        val total = doc.totalCents ?: return null
        val date = doc.date ?: return null
        val vendor = FuzzySearch.normalize(doc.vendor)
        return others
            .filter { it.id != doc.id && !it.deleted && it.totalCents == total && it.date == date }
            .filter { sameVendor(vendor, FuzzySearch.normalize(it.vendor)) }
            .minByOrNull { it.createdAt }
    }

    /** "shufersal" vs "shufersal deal", or a letter off; an empty vendor on either side counts as the same. */
    private fun sameVendor(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        return minOf(a.length, b.length) >= 5 && FuzzySearch.distance(a, b) <= 2
    }
}
