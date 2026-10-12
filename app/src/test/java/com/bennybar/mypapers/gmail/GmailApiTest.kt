package com.bennybar.mypapers.gmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmailApiTest {
    /** A message as the Gmail API returns it with format=full (trimmed). */
    private val message = Json.parseToJsonElement(
        """
        {"id":"18f1","internalDate":"1791800000000","sizeEstimate":512000,
         "payload":{"partId":"","mimeType":"multipart/mixed","headers":[
            {"name":"From","value":"\"Shufersal\" <noreply@shufersal.co.il>"},
            {"name":"Subject","value":"חשבונית מס 4411"}],
          "parts":[
           {"partId":"0","mimeType":"multipart/related","filename":"","body":{"size":0},"parts":[
              {"partId":"0.0","mimeType":"text/html","filename":"","body":{"size":2100,"data":"PHA-VGhhbmtzPC9wPg"}},
              {"partId":"0.1","mimeType":"image/png","filename":"logo.png","headers":[{"name":"Content-ID","value":"<logo>"},{"name":"Content-Disposition","value":"inline; filename=logo.png"}],"body":{"size":8000,"attachmentId":"a1"}},
              {"partId":"0.2","mimeType":"image/jpeg","filename":"","headers":[{"name":"Content-ID","value":"<banner>"}],"body":{"size":250000,"attachmentId":"a2"}}]},
           {"partId":"1","mimeType":"application/pdf","filename":"חשבונית 4411.pdf","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":40000,"attachmentId":"a3"}},
           {"partId":"2","mimeType":"image/jpeg","filename":"receipt.jpg","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":300000,"attachmentId":"a4"}},
           {"partId":"3","mimeType":"image/jpeg","filename":"tiny.jpg","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":5000,"attachmentId":"a5"}},
           {"partId":"4","mimeType":"application/octet-stream","filename":"statement.PDF","body":{"size":30000,"attachmentId":"a6"}},
           {"partId":"5","mimeType":"application/zip","filename":"archive.zip","body":{"size":50000,"attachmentId":"a7"}}]}}
        """,
    ).jsonObject

    @Test fun findsPdfsAndPhotosButNotLogos() {
        val found = GmailApi.paperParts(message)
        assertEquals(listOf("חשבונית 4411.pdf", "receipt.jpg", "statement.PDF"), found.map { it.fileName })
        assertEquals(listOf("1", "2", "4"), found.map { it.partId })
        assertEquals(listOf("application/pdf", "image/jpeg", "application/pdf"), found.map { it.mimeType })
        assertEquals(40000L, found.first().size)
    }

    @Test fun headersAndSender() {
        val payload = message["payload"]!!.jsonObject
        assertEquals("חשבונית מס 4411", GmailApi.header(payload, "subject"))
        assertEquals("Shufersal", GmailApi.senderName(GmailApi.header(payload, "From")!!))
        assertEquals("noreply@x.com", GmailApi.senderName("<noreply@x.com>"))
    }

    @Test fun partById() {
        assertEquals("a3", GmailApi.partById(message, "1")!!["body"]!!.jsonObject["attachmentId"].toString().trim('"'))
        assertNull(GmailApi.partById(message, "9"))
    }
}
