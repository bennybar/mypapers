package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.AI_DONE
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.Duplicates
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.Locale

class ExtractionTest {
    private fun doc(id: String = "d", vendor: String = "", total: Long? = null, date: String? = null, tags: List<String> = emptyList(), createdAt: Long = 0) =
        Document(id = id, vendor = vendor, totalCents = total, date = date, tags = tags, createdAt = createdAt, updatedAt = 0)

    @Test fun parsesARealResponsesApiAnswer() {
        val body = javaClass.getResource("/openai-response.json")!!.readText()
        val e = OpenAiClient.parseResponse(body)
        assertEquals("SHUFERSAL DEAL", e.vendor)
        assertEquals("Receipt", e.docType)
        assertEquals(59.3, e.total!!, 0.0)
        assertEquals("ILS", e.currency)
        assertTrue(e.text.contains("TOTAL ILS"))
    }

    @Test fun mergeRules() {
        val e = Extraction(
            vendor = "Shufersal", docType = "Receipt", date = "2026-10-08", total = 59.299999, currency = "ils",
            vat = 8.62, tags = listOf("groceries", "Household"), text = "SHUFERSAL…",
        )
        val d = doc(tags = listOf("Paper")).withExtraction(e, existingTags = listOf("Groceries"))
        assertEquals("Shufersal", d.vendor)
        assertEquals(5930L, d.totalCents) // rounded to cents
        assertEquals(862L, d.vatCents)
        assertEquals("ILS", d.currency)
        assertEquals(listOf("Paper", "Groceries", "Household"), d.tags) // kept + existing spelling + new
        assertEquals(AI_DONE, d.aiState)
    }

    @Test fun badValuesKeepWhatWasThere() {
        val e = Extraction(vendor = " ", docType = "Banana", date = "8/10/26", total = null, currency = "XYZ1", vat = null, tags = emptyList(), text = "")
        val before = doc(vendor = "Wolt", total = 100, date = "2026-01-01").copy(docType = "Invoice", currency = "USD")
        val d = before.withExtraction(e, emptyList())
        assertEquals("Wolt", d.vendor)
        assertEquals("Invoice", d.docType)
        assertEquals("2026-01-01", d.date)
        assertEquals(100L, d.totalCents)
        assertEquals("USD", d.currency)
    }

    @Test fun dateOrderFollowsTheLocale() {
        assertTrue(ExtractionPrompt.dayFirst(Locale.forLanguageTag("he-IL")))
        assertTrue(ExtractionPrompt.dayFirst(Locale.UK))
        assertFalse(ExtractionPrompt.dayFirst(Locale.US))
        assertTrue(ExtractionPrompt.build(emptyList(), Locale.forLanguageTag("he-IL")).contains("3 April"))
    }

    @Test fun duplicates() {
        val shuf = doc("a", "Shufersal", 5930, "2026-10-08", createdAt = 1)
        val others = listOf(
            shuf,
            doc("b", "Wolt", 5930, "2026-10-08"),           // other vendor
            doc("c", "Shufersal", 5931, "2026-10-08"),      // other amount
            doc("e", "Shufersal", 5930, "2026-10-09"),      // other day
        )
        assertEquals("a", Duplicates.find(doc("new", "SHUFERSAL DEAL", 5930, "2026-10-08"), others)?.id)
        assertEquals("a", Duplicates.find(doc("new", "Shufersl", 5930, "2026-10-08"), others)?.id) // typo
        assertNull(Duplicates.find(doc("new", "Paz", 5930, "2026-10-08"), others))
        assertNull(Duplicates.find(doc("new", "Shufersal", null, "2026-10-08"), others)) // no amount: can't tell
    }

    /** A real call. Runs only with OPENAI_API_KEY and MYPAPERS_TEST_PDF set. */
    @Test fun liveReadsAReceipt() {
        val key = System.getenv("OPENAI_API_KEY")
        val pdf = System.getenv("MYPAPERS_TEST_PDF")?.let(::File)
        assumeTrue("no OpenAI key", key != null && pdf != null)
        val part = buildJsonObject {
            put("type", "input_file")
            put("filename", "receipt.pdf")
            put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf!!.readBytes()))
        }
        val e = runBlocking {
            OpenAiClient().extract(AiConfig(key!!, AiSettings.DEFAULT_MODEL), ExtractionPrompt.build(listOf("Groceries", "Car"), Locale.forLanguageTag("he-IL")), listOf(part))
        }
        println(e)
        assertEquals(59.3, e.total!!, 0.001)
        assertEquals("2026-04-03", e.date) // printed 03/04/2026: day first in Israel
        assertTrue(e.tags.contains("Groceries"))
    }
}
