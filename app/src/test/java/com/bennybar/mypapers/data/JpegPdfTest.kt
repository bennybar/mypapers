package com.bennybar.mypapers.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class JpegPdfTest {
    @Test fun writesAValidTwoPagePdf() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val out = ByteArrayOutputStream()
        JpegPdf.write(listOf(JpegPdf.Page(jpeg, 1500, 2100), JpegPdf.Page(jpeg, 2100, 1500)), out)
        val bytes = out.toByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        assertTrue(text.contains("/Count 2"))
        assertTrue(text.contains("/MediaBox [0 0 720.00 1008.00]")) // 1500x2100 px at 150 dpi
        assertTrue(text.contains("/Filter /DCTDecode"))
        // Every xref offset points at its object.
        val xrefAt = text.substringAfterLast("startxref\n").substringBefore("\n").toInt()
        val entries = text.substring(xrefAt).lines().drop(3).takeWhile { it.endsWith(" n ") }
        assertEquals(8, entries.size) // catalog, pages, 3 per page
        entries.forEachIndexed { i, e -> assertTrue(text.startsWith("${i + 1} 0 obj", e.take(10).toInt())) }
    }
}
