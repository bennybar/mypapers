package com.bennybar.mypapers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TableTest {
    @Test fun plainRows() {
        assertEquals(
            listOf(listOf("Item", "Qty", "Price"), listOf("Milk", "2", "11.80"), listOf("Bread", "1", "")),
            tableRows("Item | Qty | Price\nMilk | 2 | 11.80\nBread | 1"),
        )
    }

    @Test fun markdown() {
        assertEquals(
            listOf(listOf("Eye", "SPH"), listOf("Right", "-1.25")),
            tableRows("| Eye | SPH |\n|---|:---:|\n| Right | -1.25 |"),
        )
    }

    @Test fun notATable() {
        assertNull(tableRows("https://example.com"))
        assertNull(tableRows("SPH -1.25 | CYL -0.50")) // one line
        assertNull(tableRows("line one\nline two"))
    }
}
