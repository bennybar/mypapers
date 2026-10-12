package com.bennybar.mypapers.search

import com.bennybar.mypapers.data.Document
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FuzzySearchTest {
    private fun doc(id: String, vendor: String, total: Long?, tags: List<String> = emptyList(), date: String = "2026-10-01") =
        Document(id = id, vendor = vendor, totalCents = total, tags = tags, date = date, createdAt = 0, updatedAt = 0)

    private val docs = listOf(
        doc("shuf", "Shufersal", 34290, listOf("Groceries"), "2026-10-08"),
        doc("wolt", "Wolt", 11800, listOf("Food", "Delivery"), "2026-10-06"),
        doc("iec", "Israel Electric Corp.", 61240, listOf("Utilities"), "2026-10-01"),
        doc("paz", "Paz", 12500, listOf("Car", "Fuel"), "2026-09-22"),
        doc("heb", "שופרסל", 9900, listOf("מזון"), "2026-09-01"),
    )

    private fun ids(q: String, tags: Set<String> = emptySet()) =
        FuzzySearch.search(docs, q, tags, Locale.US).map { it.id }

    @Test fun blankQueryIsRecentlyAddedFirst() {
        // Added later, but dated January: still first by default.
        val rx = Document(id = "rx", date = "2026-01-12", createdAt = 99, updatedAt = 0)
        assertEquals("rx", FuzzySearch.search(docs + rx, "", emptySet(), Locale.US).first().id)
    }

    @Test fun englishKeywordFindsHebrewPaper() {
        val rx = Document(id = "rx", title = "מרשם משקפיים", keywords = listOf("eye prescription", "glasses", "מרשם משקפיים"), createdAt = 0, updatedAt = 0)
        assertEquals(listOf("rx"), FuzzySearch.search(docs + rx, "eye prescription", emptySet(), Locale.US).map { it.id })
        assertEquals(listOf("rx"), FuzzySearch.search(docs + rx, "משקפיים", emptySet(), Locale.US).map { it.id })
    }

    @Test fun undatedNewScanIsOnTop() {
        // No date yet (not read by the AI): sorted by when it was added — today, above older papers.
        val scan = Document(id = "scan", createdAt = System.currentTimeMillis(), updatedAt = 0)
        assertEquals("scan", FuzzySearch.search(docs + scan, "", emptySet(), Locale.US).first().id)
    }

    @Test fun typoInVendor() = assertEquals("shuf", ids("shufersl").first())

    @Test fun swappedLetters() = assertEquals("shuf", ids("shufesral").first())

    @Test fun partialWord() = assertEquals(listOf("iec"), ids("elec"))

    @Test fun tagMatch() = assertEquals(listOf("paz"), ids("fuel"))

    @Test fun tagTypo() = assertEquals("wolt", ids("delivry").first())

    @Test fun amountClosestFirst() {
        val r = ids("120")
        assertEquals(listOf("wolt", "paz"), r.take(2)) // 118 is 2 away, 125 is 5 away
        assertTrue("612.40 is too far" , "iec" !in r)
    }

    @Test fun amountWithCurrencyAndGrouping() {
        assertEquals("iec", ids("₪612").first())
        assertEquals("shuf", ids("342.90").first())
    }

    @Test fun loneNumberWithNothingNearStillShowsClosest() =
        assertEquals("iec", ids("2000").first())

    @Test fun everyWordMustMatch() {
        assertEquals(listOf("wolt"), ids("wolt food"))
        assertEquals(emptyList<String>(), ids("wolt fuel"))
    }

    @Test fun vendorAndAmountTogether() = assertEquals(listOf("paz"), ids("paz 125"))

    @Test fun hebrewFinalLetters() = assertEquals("heb", ids("שופרס").first())

    @Test fun tagChipsFilter() = assertEquals(listOf("wolt"), ids("", setOf("Food")))

    @Test fun parseAmountByLocale() {
        assertEquals(1249.0, FuzzySearch.parseAmount("1,249", Locale.US)!!, 0.0)
        assertEquals(118.5, FuzzySearch.parseAmount("118,50", Locale.GERMANY)!!, 0.0)
        assertNull(FuzzySearch.parseAmount("INV2026", Locale.US))
        assertNull(FuzzySearch.parseAmount("wolt", Locale.US))
    }
}
