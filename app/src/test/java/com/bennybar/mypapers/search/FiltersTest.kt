package com.bennybar.mypapers.search

import com.bennybar.mypapers.data.Document
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class FiltersTest {
    private val today = LocalDate.of(2026, 10, 12)
    private fun doc(id: String, date: String?, total: Long?, vendor: String = "V", dup: String? = null) =
        Document(id = id, date = date, totalCents = total, vendor = vendor, duplicateOf = dup, createdAt = 0, updatedAt = 0)

    private val docs = listOf(
        doc("oct", "2026-10-05", 5000, "Shufersal"),
        doc("sep", "2026-09-20", 20000, "Wolt"),
        doc("jan", "2026-01-10", 100, "Paz"),
        doc("old", "2025-06-01", 99900, "IEC"),
        doc("nodate", null, null, ""),
        doc("dup", "2026-10-06", 5000, "Shufersal", dup = "oct"),
    )

    private fun ids(f: Filters) = docs.filter { f.matches(it, today) }.map { it.id }

    @Test fun dateWindows() {
        assertEquals(listOf("oct", "dup"), ids(Filters(date = DateWindow.THIS_MONTH)))
        assertEquals(listOf("sep"), ids(Filters(date = DateWindow.LAST_MONTH)))
        assertEquals(listOf("oct", "sep", "jan", "dup"), ids(Filters(date = DateWindow.THIS_YEAR)))
        assertEquals(listOf("old"), ids(Filters(date = DateWindow.LAST_YEAR)))
        assertEquals(listOf("sep", "jan"), ids(Filters(date = DateWindow.CUSTOM, from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 9, 30))))
    }

    @Test fun amountsVendorsAndFlags() {
        assertEquals(listOf("oct", "sep", "dup"), ids(Filters(minCents = 1000, maxCents = 50000)))
        assertEquals(listOf("oct", "dup"), ids(Filters(vendors = setOf("Shufersal"))))
        assertEquals(listOf("nodate"), ids(Filters(missingInfo = true)))
        assertEquals(listOf("dup"), ids(Filters(duplicates = true)))
        assertEquals(listOf("nodate"), ids(Filters(unread = true)))
        assertEquals(0, Filters().activeCount)
        assertEquals(2, Filters(date = DateWindow.THIS_MONTH, sort = SortBy.OLDEST).activeCount)
    }

    @Test fun sorting() {
        val dated = docs.filter { it.date != null }
        assertEquals("old", Filters(sort = SortBy.AMOUNT_HIGH).sorted(dated).first().id)
        assertEquals("jan", Filters(sort = SortBy.AMOUNT_LOW).sorted(dated).first().id)
        assertEquals("old", Filters(sort = SortBy.OLDEST).sorted(dated).first().id)
        assertEquals("dup", Filters(sort = SortBy.NEWEST).sorted(dated).first().id)
        assertEquals("IEC", Filters(sort = SortBy.VENDOR).sorted(dated).first().vendor)
    }
}
