package com.bennybar.mypapers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ExpiryTest {
    private fun doc(vararg details: Pair<String, String>) =
        Document(id = "d", createdAt = 0, updatedAt = 0, details = details.map { KeyDetail(it.first, it.second) })

    @Test fun formats() {
        assertEquals(LocalDate.of(2027, 8, 15), Expiry.parse("2027-08-15", dayFirst = true))
        assertEquals(LocalDate.of(2027, 8, 15), Expiry.parse("15/08/2027", dayFirst = false)) // 15 can only be the day
        assertEquals(LocalDate.of(2027, 4, 3), Expiry.parse("03.04.2027", dayFirst = true))
        assertEquals(LocalDate.of(2027, 3, 4), Expiry.parse("03/04/2027", dayFirst = false))
        assertEquals(LocalDate.of(2027, 8, 15), Expiry.parse("Aug 15, 2027", dayFirst = true))
        assertEquals(LocalDate.of(2027, 8, 15), Expiry.parse("15 August 2027", dayFirst = true))
        assertEquals(LocalDate.of(2027, 8, 31), Expiry.parse("08/2027", dayFirst = true))
        assertNull(Expiry.parse("2 years", dayFirst = true))
    }

    @Test fun fromDetails() {
        val rx = doc("PD" to "62 mm", "Valid for" to "2 years", "Renewal date" to "15/08/2027")
        assertEquals(LocalDate.of(2027, 8, 15), Expiry.date(rx, dayFirst = true))
        assertEquals(LocalDate.of(2027, 1, 31), Expiry.date(doc("בתוקף עד" to "31.01.2027"), dayFirst = true))
        assertNull(Expiry.date(doc("Prescription date" to "2026-08-15"), dayFirst = true)) // not an expiry
    }

    @Test fun soon() {
        val today = LocalDate.of(2026, 10, 12)
        assertTrue(Expiry.isSoon(doc("Expires" to "2026-11-01"), today, true))
        assertFalse(Expiry.isSoon(doc("Expires" to "2027-11-01"), today, true))
        assertFalse(Expiry.isSoon(doc("Expires" to "2026-10-01"), today, true)) // already past
    }

    @Test fun nearestUpcomingWins() {
        val today = LocalDate.of(2026, 10, 12)
        val bill = doc("Due date" to "2026-10-19", "Valid until" to "2027-10-01")
        assertEquals(LocalDate.of(2026, 10, 19), Expiry.date(bill, true, today))
        assertTrue(Expiry.isSoon(bill, today, true))
        // Once it's paid and past, the next one counts.
        assertEquals(LocalDate.of(2027, 10, 1), Expiry.date(bill, true, LocalDate.of(2026, 10, 25)))
        // All past: the latest.
        assertEquals(LocalDate.of(2027, 10, 1), Expiry.date(bill, true, LocalDate.of(2028, 1, 1)))
    }
}
