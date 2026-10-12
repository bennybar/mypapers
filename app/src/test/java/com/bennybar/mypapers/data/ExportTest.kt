package com.bennybar.mypapers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTest {
    @Test fun csv() {
        val d = Document(
            id = "a", title = "Shufersal, weekly", vendor = "שופרסל", date = "2026-10-08", totalCents = 24990, vatCents = 3631,
            currency = "ILS", tags = listOf("Food", "Home"), notes = "said \"thanks\"", createdAt = 0, updatedAt = 0,
        )
        val lines = Export.csv(listOf(d), mapOf("a" to "files/Shufersal, weekly.pdf")).split("\r\n")
        assertTrue(lines[0].startsWith("﻿Date,Title,"))
        val expected = "2026-10-08,\"Shufersal, weekly\",שופרסל,,249.90,36.31,ILS,\"Food, Home\",,\"said \"\"thanks\"\"\",\"files/Shufersal, weekly.pdf\""
        assertEquals(expected, lines[1])
    }
}
