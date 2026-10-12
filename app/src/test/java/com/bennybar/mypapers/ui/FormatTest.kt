package com.bennybar.mypapers.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormatTest {
    private val il = Locale.forLanguageTag("en-IL")
    private val de = Locale.GERMANY

    @Test fun datesFollowTheRegion() {
        assertEquals("Oct 12, 2026", Format.date("2026-10-12", Locale.US))
        assertTrue(Format.date("2026-10-12", de)!!.startsWith("12.10.2026"))
        assertNull(Format.date("not a date", Locale.US))
    }

    @Test fun amountsFollowTheRegion() {
        assertEquals("$1,249.50", Format.amount(124950, "USD", Locale.US))
        assertTrue(Format.amount(124950, "EUR", de).startsWith("1.249,50"))
        assertTrue(Format.amount(124950, "ILS", il).contains("1,249.50"))
    }

    @Test fun typedAmountsReadInTheRegionsFormat() {
        assertEquals(124950L, Format.parseAmount("1,249.50", Locale.US))
        assertEquals(124950L, Format.parseAmount("1.249,50", de))
        assertNull(Format.parseAmount("12abc", Locale.US))
    }

    @Test fun defaultCurrencyFromTheRegion() {
        Format.locale = il
        assertEquals("ILS", Format.defaultCurrency())
        Format.locale = de
        assertEquals("EUR", Format.defaultCurrency())
    }
}
