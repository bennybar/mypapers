package com.bennybar.mypapers.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Part
import javax.mail.Session
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

class GmailClientTest {
    private val session = Session.getInstance(Properties())

    private fun part(type: String, bytes: Int, name: String? = null, disposition: String? = null, contentId: String? = null) =
        MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(ByteArray(bytes) { 1 }, type))
            name?.let { fileName = it }
            disposition?.let { this.disposition = it }
            contentId?.let { setHeader("Content-ID", it) }
        }

    /** Reparsed, as it comes from the server (sizes and structure as JavaMail reads them). */
    private fun message(root: MimeMultipart): MimeMessage {
        val m = MimeMessage(session).apply { setContent(root); saveChanges() }
        val bytes = java.io.ByteArrayOutputStream().also { m.writeTo(it) }.toByteArray()
        return MimeMessage(session, bytes.inputStream())
    }

    @Test fun findsPdfsAndPhotosButNotLogos() {
        val related = MimeMultipart("related").apply {
            addBodyPart(MimeBodyPart().apply { setContent("<p>Thanks for your order</p><img src=cid:logo>", "text/html; charset=utf-8") })
            addBodyPart(part("image/png", 8_000, contentId = "<logo>", disposition = Part.INLINE)) // logo
            addBodyPart(part("image/png", 200_000, contentId = "<banner>")) // big inline banner
        }
        val root = MimeMultipart("mixed").apply {
            addBodyPart(MimeBodyPart().apply { setContent(related) })
            addBodyPart(part("application/pdf", 40_000, name = "Invoice 4411.pdf", disposition = Part.ATTACHMENT))
            addBodyPart(part("image/jpeg", 300_000, name = "receipt-photo.jpg", disposition = Part.ATTACHMENT))
            addBodyPart(part("image/jpeg", 5_000, name = "tiny.jpg", disposition = Part.ATTACHMENT)) // too small
            addBodyPart(part("application/octet-stream", 30_000, name = "statement.PDF", disposition = Part.ATTACHMENT)) // by extension
            addBodyPart(part("application/zip", 50_000, name = "archive.zip", disposition = Part.ATTACHMENT))
        }
        val found = GmailClient.paperParts(message(root))
        assertEquals(listOf("Invoice 4411.pdf", "receipt-photo.jpg", "statement.PDF"), found.map { it.fileName })
        assertEquals(listOf("application/pdf", "image/jpeg", "application/pdf"), found.map { it.mimeType })
        assertEquals("1", found.first().path)
        assertTrue(found.all { it.size > 0 })
    }

    @Test fun hebrewFileNames() {
        val root = MimeMultipart("mixed").apply {
            addBodyPart(MimeBodyPart().apply { setText("שלום", "utf-8") })
            addBodyPart(part("application/pdf", 10_000, name = javax.mail.internet.MimeUtility.encodeText("חשבונית מס.pdf", "utf-8", "B"), disposition = Part.ATTACHMENT))
        }
        assertEquals("חשבונית מס.pdf", GmailClient.paperParts(message(root)).single().fileName)
    }
}
