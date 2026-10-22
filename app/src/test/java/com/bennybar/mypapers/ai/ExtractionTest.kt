package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.AI_DONE
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.Duplicates
import com.bennybar.mypapers.data.KeyDetail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
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
            vat = 8.62, tags = listOf("groceries", "Household"), text = "SHUFERSAL…", title = "Shufersal groceries",
        )
        val d = doc(tags = listOf("Paper")).withExtraction(e, existingTags = listOf("Groceries"))
        assertEquals("Shufersal", d.vendor)
        assertEquals("Shufersal groceries", d.title)
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

    @Test fun handEditedFieldsStay() {
        val e = Extraction(
            vendor = "Shufersal", docType = "Receipt", date = "2026-10-08", total = 99.0, currency = "ILS", vat = null,
            tags = listOf("Groceries"), text = "…", title = "Shufersal groceries", details = listOf(KeyDetail("Valid until", "2027-01-01")),
        )
        val before = doc(vendor = "Wolt", total = 1250, tags = listOf("Food")).copy(
            title = "Dinner", details = listOf(KeyDetail("Due date", "2026-11-01")),
            edited = listOf("vendor", "total", "title", "tags", "details"),
        )
        val d = before.withExtraction(e, emptyList())
        assertEquals("Wolt", d.vendor)
        assertEquals(1250L, d.totalCents)
        assertEquals("Dinner", d.title)
        assertEquals(listOf("Food"), d.tags)
        assertEquals(listOf(KeyDetail("Due date", "2026-11-01")), d.details)
        // Not edited: the AI fills them.
        assertEquals("2026-10-08", d.date)
        assertEquals("Receipt", d.docType)
    }

    @Test fun askedDetailsReplaceSameLabel() {
        val before = listOf(KeyDetail("Order number", "A-1"), KeyDetail("URL", "old"))
        val after = mergeDetails(before, listOf(KeyDetail("url", "https://example.com/order/A-1")))
        assertEquals(listOf(KeyDetail("Order number", "A-1"), KeyDetail("url", "https://example.com/order/A-1")), after)
    }

    /** A re-read refreshes the AI's own details and keeps the ones saved from Ask AI (replacing a read one with its label). */
    @Test fun rereadKeepsAskedDetails() {
        val before = doc().copy(details = listOf(KeyDetail("Policy", "old"), KeyDetail("URL", "https://a.example", asked = true), KeyDetail("Plan", "asked plan", asked = true)))
        val e = Extraction("V", "Contract", null, null, null, null, emptyList(), "", details = listOf(KeyDetail("Policy", "new"), KeyDetail("Plan", "read plan")))
        val after = before.withExtraction(e, emptyList()).details
        assertEquals(listOf(KeyDetail("Policy", "new"), KeyDetail("URL", "https://a.example", asked = true), KeyDetail("Plan", "asked plan", asked = true)), after)
    }

    /** A reworked detail takes the old one's place, even under a new label. */
    @Test fun reworkReplacesInPlace() {
        val before = listOf(KeyDetail("Policy", "P-1"), KeyDetail("Items", "a, b"), KeyDetail("URL", "u"))
        assertEquals(
            listOf(KeyDetail("Policy", "P-1"), KeyDetail("Item list", "Item | Qty\na | 1\nb | 2"), KeyDetail("URL", "u")),
            replaceDetail(before, "items", listOf(KeyDetail("Item list", "Item | Qty\na | 1\nb | 2"))),
        )
        // Gone meanwhile: just added.
        assertEquals(before + KeyDetail("X", "1"), replaceDetail(before, "Missing", listOf(KeyDetail("X", "1"))))
    }

    @Test fun dateOrderFollowsTheLocale() {
        assertTrue(ExtractionPrompt.dayFirst(Locale.forLanguageTag("he-IL")))
        assertTrue(ExtractionPrompt.dayFirst(Locale.UK))
        assertFalse(ExtractionPrompt.dayFirst(Locale.US))
        assertTrue(ExtractionPrompt.build(emptyList(), Locale.forLanguageTag("he-IL")).contains("3 April"))
    }

    @Test fun futureDateWithDayAndMonthSwapped() {
        val today = java.time.LocalDate.of(2026, 10, 12)
        assertEquals("2026-10-12", fixFutureDate("2026-12-10", today)) // 12/10 read month-first
        assertEquals("2026-09-30", fixFutureDate("2026-09-30", today)) // past: kept
        assertEquals("2026-12-25", fixFutureDate("2026-12-25", today)) // day > 12: can't be swapped
        assertEquals("2026-11-12", fixFutureDate("2026-11-12", today)) // swapped (Dec 11) is future too: kept
    }

    @Test fun hebrewFinalLetters() {
        assertEquals("לוקרים", fixHebrewFinals("לוקרימ"))
        assertEquals("כלל פנסיה וגמל", fixHebrewFinals("כלל פנסיה וגמל"))
        assertEquals("חשבון 019, ספטמבר", fixHebrewFinals("חשבונ 019, ספטמבר"))
        assertEquals("ארץ ועוף", fixHebrewFinals("ארצ ועופ"))
        assertEquals("צ'יפס", fixHebrewFinals("צ'יפס")) // geresh: not a word end
        assertEquals("Shufersal מ", fixHebrewFinals("Shufersal מ")) // one letter: left alone
    }

    @Test fun duplicates() {
        val shuf = doc("a", "Shufersal", 5930, "2026-10-08", createdAt = 1)
        val others = listOf(
            shuf,
            doc("b", "Wolt", 5930, "2026-10-08"),           // other vendor
            doc("c", "Shufersal", 5931, "2026-10-08"),      // other amount
            doc("e", "Shufersal", 5930, "2026-10-09"),      // other day
        )
        assertEquals("a", Duplicates.find(doc("new", "SHUFERSAL DEAL", 5930, "2026-10-08", createdAt = 9), others)?.id)
        assertEquals("a", Duplicates.find(doc("new", "Shufersl", 5930, "2026-10-08", createdAt = 9), others)?.id) // typo
        assertNull(Duplicates.find(doc("new", "Paz", 5930, "2026-10-08", createdAt = 9), others))
        assertNull(Duplicates.find(doc("new", "Shufersal", null, "2026-10-08", createdAt = 9), others)) // no amount vs an amount
        // Only the newer copy is flagged.
        assertNull(Duplicates.find(shuf, others + doc("new", "Shufersal", 5930, "2026-10-08", createdAt = 9)))
    }

    @Test fun duplicatesWithoutAmount() {
        val rx = Document(id = "rx", vendor = "קשר עין", docType = "Medical", date = "2026-01-12", createdAt = 1, updatedAt = 0,
            details = listOf(KeyDetail("PD", "62 mm")))
        val again = rx.copy(id = "again", createdAt = 2, details = emptyList())
        assertEquals("rx", Duplicates.find(again, listOf(rx))?.id)
        assertNull(Duplicates.find(again.copy(details = listOf(KeyDetail("PD", "64 mm"))), listOf(rx))) // a newer prescription
        assertNull(Duplicates.find(again.copy(date = "2026-08-27"), listOf(rx)))
        assertNull(Duplicates.find(again.copy(vendor = ""), listOf(rx.copy(vendor = ""))))
    }

    /** A real call: an Israeli invoice on a US-locale phone still reads day-first. Needs OPENAI_API_KEY and MYPAPERS_TEST_IL_PDF. */
    @Test fun liveIsraeliInvoiceOnUsPhone() {
        val key = System.getenv("OPENAI_API_KEY")
        val pdf = System.getenv("MYPAPERS_TEST_IL_PDF")?.let(::File)
        assumeTrue("no OpenAI key", key != null && pdf != null)
        val part = buildJsonObject {
            put("type", "input_file")
            put("filename", "invoice.pdf")
            put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf!!.readBytes()))
        }
        val e = runBlocking { OpenAiClient().extract(AiConfig(key!!, AiSettings.DEFAULT_MODEL), ExtractionPrompt.build(listOf("School"), Locale.US), listOf(part)) }
        println(e)
        assertEquals("2026-10-12", e.date)
        assertEquals(3534.0, e.total!!, 0.001)
    }

    /** A real call: a glasses prescription's values come back as key details. Needs OPENAI_API_KEY and MYPAPERS_TEST_RX_PDF. */
    @Test fun livePrescriptionDetails() {
        val key = System.getenv("OPENAI_API_KEY")
        val pdf = System.getenv("MYPAPERS_TEST_RX_PDF")?.let(::File)
        assumeTrue("no OpenAI key", key != null && pdf != null)
        val part = buildJsonObject {
            put("type", "input_file")
            put("filename", "rx.pdf")
            put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf!!.readBytes()))
        }
        val e = runBlocking { OpenAiClient().extract(AiConfig(key!!, AiSettings.DEFAULT_MODEL), ExtractionPrompt.build(emptyList(), Locale.forLanguageTag("he-IL")), listOf(part)) }
        e.details.forEach { println("  ${it.label}: ${it.value}") }
        println("  keywords=${e.keywords}")
        println("  title=${e.title} total=${e.total} date=${e.date}")
        val all = e.details.joinToString(" | ") { it.label + " " + it.value }
        assertTrue(all, all.contains("-1.25") && all.contains("-0.75") && all.contains("62"))
        assertEquals("2026-08-15", e.date)
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
        assertTrue(e.title.isNotBlank())
    }

    /** A real call: "extract the URL" comes back as a detail; asking for something absent comes back empty. Needs OPENAI_API_KEY and MYPAPERS_TEST_ASK_PDF. */
    @Test fun liveAsk() {
        val key = System.getenv("OPENAI_API_KEY")
        val pdf = System.getenv("MYPAPERS_TEST_ASK_PDF")?.let(::File)
        assumeTrue("no OpenAI key", key != null && pdf != null)
        val part = buildJsonObject {
            put("type", "input_file")
            put("filename", "order.pdf")
            put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(pdf!!.readBytes()))
        }
        val config = AiConfig(key!!, AiSettings.DEFAULT_MODEL)
        val record = "Title: Order 77412\nVendor: \nType: Receipt\nDate: \nAmount: \nTags: \nDetails:"
        fun ask(history: List<PaperTurn>, q: String): JsonObject =
            Json.parseToJsonElement(runBlocking { OpenAiClient().complete(config, ExtractionPrompt.ask(record, history, q), listOf(part), "answer", ExtractionPrompt.ASK_SCHEMA) }).jsonObject
        fun details(o: JsonObject) = OpenAiClient.parseExtraction(o.toString()).details
        val first = ask(emptyList(), "extract the URL")
        println("  url: $first")
        assertTrue(first.toString(), details(first).any { it.value.contains("https://track.example-shop.com/orders/77412") })
        assertTrue(ask(emptyList(), "extract the IBAN").let { details(it).isEmpty() })
        // A follow-up that changes the paper comes back as changes, not details.
        val turn = PaperTurn("extract the URL", first["reply"]!!.jsonPrimitive.content, details(first))
        val change = ask(listOf(turn), "set the title to Shop order and add the tag Online")
        println("  change: $change")
        val c = change["changes"]!!.jsonObject
        assertEquals("Shop order", c["title"]!!.jsonPrimitive.content)
        assertTrue(c.toString(), c["addTags"].toString().contains("Online"))
    }
}
