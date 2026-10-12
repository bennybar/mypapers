package com.bennybar.mypapers.data

import com.bennybar.mypapers.search.FuzzySearch

/**
 * The same paper brought in twice in different files (a scan and the emailed PDF):
 * same amount and date, and a vendor that reads the same. Papers with no amount
 * (a prescription, a warranty): same date, type and vendor, and no detail that
 * differs. The identical file is caught earlier, by its hash. Only the newer copy
 * is flagged, so deleting the flagged one keeps the other.
 */
object Duplicates {
    fun find(doc: Document, others: List<Document>): Document? {
        val date = doc.date ?: return null
        val vendor = FuzzySearch.normalize(doc.vendor)
        val candidates = others.filter { it.id != doc.id && !it.deleted && it.date == date && it.createdAt < doc.createdAt }
        val total = doc.totalCents
        return if (total != null) {
            candidates
                .filter { it.totalCents == total && sameVendor(vendor, FuzzySearch.normalize(it.vendor)) }
                .minByOrNull { it.createdAt }
        } else {
            if (vendor.isEmpty()) return null
            candidates
                .filter { it.totalCents == null && it.docType == doc.docType && FuzzySearch.normalize(it.vendor).let { v -> v.isNotEmpty() && sameVendor(vendor, v) } }
                .filter { sameDetails(doc.details, it.details) }
                .minByOrNull { it.createdAt }
        }
    }

    /** "shufersal" vs "shufersal deal", or a letter off; an empty vendor on either side counts as the same. */
    private fun sameVendor(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty() || a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        return minOf(a.length, b.length) >= 5 && FuzzySearch.distance(a, b) <= 2
    }

    /** No detail with the same label and another value (one side without details: can't tell, so the same). */
    private fun sameDetails(a: List<KeyDetail>, b: List<KeyDetail>): Boolean {
        val values = b.associate { FuzzySearch.normalize(it.label) to FuzzySearch.normalize(it.value) }
        return a.none { d -> values[FuzzySearch.normalize(d.label)]?.let { it != FuzzySearch.normalize(d.value) } == true }
    }
}
