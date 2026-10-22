package com.bennybar.mypapers.ai

import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.KeyDetail
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AskAllTest {
    private fun doc(id: String, title: String, cents: Long?, currency: String? = "ILS", text: String = "", date: String? = "2026-05-01", tags: List<String> = emptyList()) =
        Document(id = id, title = title, totalCents = cents, currency = currency, fullText = text, date = date, tags = tags, createdAt = 0, updatedAt = 0)

    @Test fun lineHasTheFacts() {
        val d = doc("a", "Washing machine", 249900, tags = listOf("Home")).copy(vendor = "Machsanei Hashmal", details = listOf(KeyDetail("Warranty", "3 years\nparts")))
        assertEquals("#4 | 2026-05-01 |  | Washing machine | Machsanei Hashmal | 2499.00 ILS | Home |  | Warranty: 3 years parts", AskAll.line(4, d))
    }

    @Test fun totalsPerCurrencySkipNoAmount() {
        val t = AskAll.totals(listOf(doc("a", "x", 1000), doc("b", "y", 250), doc("c", "z", 500, "USD"), doc("d", "w", null)))
        assertEquals(mapOf("ILS" to 1250L, "USD" to 500L), t)
    }

    /** A follow-up carries the conversation so far into both steps. */
    @Test fun followUpCarriesTheConversation() {
        val history = listOf(AllTurn("how much on the dentist?", "₪750 at Dr. Levi."))
        val docs = listOf(doc("1", "Dentist", 45000))
        for (prompt in listOf(AskAll.pick("and last year?", docs, history), AskAll.answer("and last year?", history, true, listOf(1 to docs[0]), emptyList()))) {
            assertTrue(prompt, prompt.contains("Q: how much on the dentist?") && prompt.contains("A: ₪750 at Dr. Levi."))
            assertTrue(prompt, prompt.contains("Now they ask: \"and last year?\""))
        }
        assertTrue(AskAll.pick("where?", docs).contains("They ask: \"where?\""))
    }

    @Test fun parsesAnswers() {
        assertEquals(listOf(3, 1), AskAll.numbers("""{"papers":[3,1,3],"total":true}"""))
        assertTrue(AskAll.isTotal("""{"papers":[],"total":true}"""))
        assertEquals("At Ace", AskAll.answerText("""{"answer":"At Ace","papers":[2]}"""))
    }

    @Test fun pdfTextIsKeptOverTheAiText() {
        val e = Extraction("V", "Receipt", null, null, null, null, emptyList(), "a".repeat(3000))
        val fromPdf = doc("a", "", null, text = "the pdf's own text").withExtraction(e, emptyList())
        assertEquals("the pdf's own text", fromPdf.fullText)
        assertEquals(SEARCH_TEXT_CHARS, fromPdf.extractedText.length)
        val photo = doc("b", "", null).withExtraction(e, emptyList())
        assertEquals(3000, photo.fullText.length)
    }

    /** A real call, both steps: picks the right papers, answers from the text, lists the papers for a total. Needs OPENAI_API_KEY. */
    @Test fun liveAskAll() {
        val key = System.getenv("OPENAI_API_KEY")
        assumeTrue("no OpenAI key", key != null)
        val config = AiConfig(key!!, AiSettings.DEFAULT_MODEL)
        val docs = listOf(
            doc("1", "Washing machine", 249900, text = "Machsanei Hashmal, Tel Aviv branch. Bosch WAN28 washing machine, serial WX-55821. Warranty 3 years.", tags = listOf("Home")).copy(vendor = "Machsanei Hashmal"),
            doc("2", "Dentist — filling", 45000, text = "Dr. Levi dental clinic. Composite filling, tooth 36.", tags = listOf("Health")),
            doc("3", "Dentist — cleaning", 30000, text = "Dr. Levi dental clinic. Cleaning and check-up.", tags = listOf("Health")),
            doc("4", "Shufersal groceries", 31250, text = "Milk, bread, eggs.", tags = listOf("Groceries")),
        )
        fun run(q: String): Triple<String, List<Int>, Boolean> = runBlocking {
            val c = OpenAiClient()
            val pick = c.complete(config, AskAll.pick(q, docs), emptyList(), "pick", AskAll.PICK_SCHEMA)
            val picked = AskAll.numbers(pick).map { it to docs[it - 1] }
            val total = AskAll.isTotal(pick)
            val answer = c.complete(config, AskAll.answer(q, emptyList(), total, picked, emptyList()), emptyList(), "answer", AskAll.ANSWER_SCHEMA)
            Triple(AskAll.answerText(answer), AskAll.numbers(answer), total)
        }
        val serial = run("what's the serial number of my washing machine?")
        println("  serial: $serial")
        assertTrue(serial.first, serial.first.contains("WX-55821"))
        assertEquals(listOf(1), serial.second)
        val dental = run("how much did I spend on the dentist?")
        println("  dental: $dental")
        assertTrue(dental.third)
        assertEquals(setOf(2, 3), dental.second.toSet())
    }
}
