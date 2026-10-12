package com.bennybar.mypapers.gmail

import com.bennybar.mypapers.ai.AiConfig
import com.bennybar.mypapers.ai.AiSettings
import com.bennybar.mypapers.ai.OpenAiClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class GmailTriageTest {
    private fun candidate(key: String, sender: String, subject: String, file: String, snippet: String, kind: String = GmailCandidate.KIND_ATTACHMENT) =
        GmailCandidate(
            key = key, gmailId = key, subject = subject, sender = sender, sentAt = 0, kind = kind, partId = "1",
            fileName = file, mimeType = "application/pdf", size = 1000, foundAt = 0, snippet = snippet,
        )

    @Test fun parsesAndCleansTheAnswer() {
        val answer = """{"items":[
            {"id":"a","category":"Bill","title":"019 Mobile bill, September 2026","vendor":"019 Mobile","total":89.9,"currency":"ils","paper":true},
            {"id":"b","category":"Not a paper","title":"Newsletter","vendor":null,"total":null,"currency":null,"paper":false},
            {"id":"c","category":"Banana","title":" ","vendor":" ","total":null,"currency":"XX","paper":true},
            {"id":"zzz","category":"Bill","title":"not asked","vendor":null,"total":null,"currency":null,"paper":true}]}"""
        val r = GmailTriage.parse(answer, setOf("a", "b", "c")).associateBy { it.key }
        assertEquals(3, r.size) // "zzz" wasn't asked about
        assertEquals("Bill", r["a"]!!.category)
        assertEquals(8990L, r["a"]!!.totalCents)
        assertEquals("ILS", r["a"]!!.currency)
        assertFalse(r["b"]!!.paper)
        assertEquals("Document", r["c"]!!.category) // unknown category
        assertNull(r["c"]!!.title)
        assertNull(r["c"]!!.vendor)
        assertNull(r["c"]!!.currency)
    }

    /** A real call. Needs OPENAI_API_KEY. */
    @Test fun liveSortsAMixedInbox() {
        val key = System.getenv("OPENAI_API_KEY")
        assumeTrue("no OpenAI key", key != null)
        val items = listOf(
            candidate("bill", "no-reply@019mobile.co.il", "החשבונית שלך מ 019", "71352348_bennybar@gmail.com.pdf", "שלום, מצורפת החשבונית החודשית שלך לחודש ספטמבר 2026 בסך 89.90 ש\"ח"),
            candidate("school", "do-not-reply@metro-mail.co.il", "אישור תשלום לבית הספר PARENT PAY", "ParentPayInvoice.pdf", "תודה על התשלום. סכום: 3,534.00 ₪ עבור תלמוד תורה"),
            candidate("news", "ערכים", "עלון ערכים לשבת // פרשת בראשית", "גיליון 184.pdf", "עלון שבועי לשבת, דברי תורה ומאמרים"),
            candidate("apple", "Apple", "Your receipt from Apple.", "Email.pdf", "Apple Account bennybar@gmail.com iCloud+ 50GB Monthly ₪3.90 Billed to Visa", GmailCandidate.KIND_EMAIL),
        )
        val r = runBlocking { GmailTriage.sort(OpenAiClient(), AiConfig(key!!, AiSettings.DEFAULT_MODEL), items) }.associateBy { it.key }
        r.values.forEach(::println)
        assertEquals(4, r.size)
        assertTrue(r["bill"]!!.category in setOf("Bill", "Invoice"))
        assertTrue(r["school"]!!.paper)
        assertEquals(353400L, r["school"]!!.totalCents)
        assertFalse(r["news"]!!.paper)
        assertEquals(390L, r["apple"]!!.totalCents)
        assertTrue(r.values.all { !it.title.isNullOrBlank() })
    }
}
