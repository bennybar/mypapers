package com.bennybar.mypapers.gmail

import com.sun.mail.iap.Argument
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.IMAPMessage
import com.sun.mail.imap.protocol.IMAPResponse
import java.io.Closeable
import java.io.File
import java.util.Properties
import javax.mail.AuthenticationFailedException
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Multipart
import javax.mail.Part
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeUtility

class GmailException(message: String) : Exception(message)

/**
 * Gmail over IMAP with an app password, searching with Gmail's own syntax
 * (X-GM-RAW) inside All Mail. Blocking calls: use from a background thread.
 */
class GmailClient private constructor(private val store: Store, private val folder: IMAPFolder) : Closeable {
    val uidValidity: Long get() = folder.uidValidity

    /** UIDs of the emails matching a Gmail search (e.g. "has:attachment newer_than:6m"). */
    fun search(query: String): List<Long> {
        @Suppress("UNCHECKED_CAST")
        val uids = folder.doCommand { protocol ->
            val args = Argument().apply { writeString(query, "UTF-8") }
            val responses = protocol.command("UID SEARCH CHARSET UTF-8 X-GM-RAW", args)
            val found = mutableListOf<Long>()
            for (r in responses) {
                if (r is IMAPResponse && r.keyEquals("SEARCH")) {
                    while (true) {
                        val n = r.readLong()
                        if (n < 0) break
                        found += n
                    }
                }
            }
            protocol.notifyResponseHandlers(responses)
            protocol.handleResult(responses.last())
            found
        } as List<Long>
        return uids
    }

    /**
     * What in these emails may be a paper: PDF and photo attachments (not logos
     * or signature images), or — when there are none — the email itself, for
     * receipts written in the email.
     */
    fun describe(uids: List<Long>, includeEmails: Boolean, now: Long): List<GmailCandidate> {
        if (uids.isEmpty()) return emptyList()
        val messages = folder.getMessagesByUID(uids.toLongArray()).filterNotNull()
        folder.fetch(
            messages.toTypedArray(),
            FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.CONTENT_INFO)
                add(UIDFolder.FetchProfileItem.UID)
                add("Message-ID")
            },
        )
        val validity = folder.uidValidity
        return messages.flatMap { m ->
            val uid = folder.getUID(m)
            val subject = m.subject.orEmpty().ifBlank { "(no subject)" }
            val sender = (m.from?.firstOrNull() as? InternetAddress)?.let { it.personal ?: it.address } ?: ""
            val sentAt = (m.sentDate ?: m.receivedDate)?.time ?: 0L
            val messageId = (m as IMAPMessage).messageID
            fun candidate(kind: String, path: String, name: String, type: String, size: Long) = GmailCandidate(
                key = "$validity:$uid:$path", uid = uid, uidValidity = validity, messageId = messageId,
                subject = subject, sender = sender, sentAt = sentAt, kind = kind, partPath = path,
                fileName = name, mimeType = type, size = size, foundAt = now,
            )
            val attachments = paperParts(m).map { candidate(GmailCandidate.KIND_ATTACHMENT, it.path, it.fileName, it.mimeType, it.size) }
            when {
                attachments.isNotEmpty() -> attachments
                includeEmails -> listOf(
                    candidate(GmailCandidate.KIND_EMAIL, "", safeFileName(subject) + ".pdf", "application/pdf", m.size.toLong().coerceAtLeast(0)),
                )
                else -> emptyList()
            }
        }
    }

    /** Saves an attachment into [to]. */
    fun downloadPart(c: GmailCandidate, to: File) {
        val part = findPart(message(c), c.partPath) ?: throw GmailException("The attachment is no longer in this email")
        part.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
    }

    /** The email's body as HTML (plain-text emails wrapped as preformatted text), with its subject and sender on top. */
    fun emailHtml(c: GmailCandidate): String {
        val m = message(c)
        var html: String? = null
        var text: String? = null
        walk(m, "") { part, _ ->
            if (Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return@walk
            if (html == null && part.isMimeType("text/html")) html = part.content as? String
            if (text == null && part.isMimeType("text/plain")) text = part.content as? String
        }
        val header = "<p style=\"font-family:sans-serif;color:#555;font-size:12px\">${escape(c.sender)} · ${escape(c.subject)}</p>"
        return when {
            html != null -> header + html
            text != null -> "$header<pre style=\"white-space:pre-wrap;font-family:sans-serif\">${escape(text!!)}</pre>"
            else -> throw GmailException("The email has no text")
        }
    }

    private fun message(c: GmailCandidate): IMAPMessage {
        if (c.uidValidity == folder.uidValidity) {
            (folder.getMessageByUID(c.uid) as? IMAPMessage)?.let { return it }
        }
        // The UIDs changed: find it by its Message-ID.
        val id = c.messageId ?: throw GmailException("The email can't be found anymore")
        val uid = search("rfc822msgid:" + id.trim('<', '>')).firstOrNull() ?: throw GmailException("The email was deleted")
        return folder.getMessageByUID(uid) as IMAPMessage
    }

    override fun close() {
        runCatching { folder.close(false) }
        runCatching { store.close() }
    }

    companion object {
        /** Images smaller than this are logos, signatures and tracking pixels, not photographed receipts. */
        private const val MIN_IMAGE_BYTES = 30_000

        fun connect(address: String, appPassword: String): GmailClient {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", "imap.gmail.com")
                put("mail.imaps.port", "993")
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "20000")
                put("mail.imaps.timeout", "60000")
                // Fewer round trips for attachments.
                put("mail.imaps.fetchsize", (1024 * 1024).toString())
            }
            val store = Session.getInstance(props).getStore("imaps")
            try {
                store.connect("imap.gmail.com", address, appPassword)
            } catch (_: AuthenticationFailedException) {
                throw GmailException("Gmail refused the login. Check the address and the app password.")
            }
            // All Mail by its special-use flag (its name is translated in other languages).
            val allMail = store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
                .firstOrNull { f -> f.attributes.any { it.equals("\\All", ignoreCase = true) } }
                ?: run {
                    store.close()
                    throw GmailException("No \"All Mail\" folder — is IMAP access to All Mail turned off in Gmail?")
                }
            allMail.open(Folder.READ_ONLY)
            return GmailClient(store, allMail)
        }

        /** Gmail searches: attachments, and emails that read like receipts (most of which have no attachment). */
        fun attachmentQuery(window: String) =
            "newer_than:$window has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png OR filename:heic)"

        fun receiptQuery(window: String) =
            "newer_than:$window (category:purchases OR subject:(receipt OR invoice OR \"order confirmation\" OR \"payment confirmation\" OR \"your order\" OR קבלה OR חשבונית OR \"אישור הזמנה\" OR \"אישור תשלום\"))"

        /** An attachment worth offering: where it is in the email, and what it is. */
        data class PaperPart(val path: String, val fileName: String, val mimeType: String, val size: Long)

        /** The PDFs and photos attached to an email, skipping logos and pictures shown inside it. */
        fun paperParts(message: Part): List<PaperPart> {
            val found = mutableListOf<PaperPart>()
            walk(message, "") { part, path ->
                paperAttachment(part)?.let { (name, type) -> found += PaperPart(path, name, type, part.size.toLong().coerceAtLeast(0)) }
            }
            return found
        }

        private fun walk(part: Part, path: String, visit: (Part, String) -> Unit) {
            val content = runCatching { if (part.isMimeType("multipart/*")) part.content else null }.getOrNull()
            if (content is Multipart) {
                for (i in 0 until content.count) walk(content.getBodyPart(i), if (path.isEmpty()) "$i" else "$path.$i", visit)
            } else {
                visit(part, path)
            }
        }

        private fun findPart(part: Part, path: String): Part? {
            if (path.isEmpty()) return part
            var current: Part = part
            for (index in path.split('.').map { it.toInt() }) {
                val mp = current.content as? Multipart ?: return null
                if (index >= mp.count) return null
                current = mp.getBodyPart(index)
            }
            return current
        }

        /** (file name, type) when the part is a PDF or a photo worth importing. */
        private fun paperAttachment(part: Part): Pair<String, String>? {
            val name = runCatching { part.fileName?.let(MimeUtility::decodeText) }.getOrNull()
            val type = part.contentType.substringBefore(';').trim().lowercase()
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val isPdf = type == "application/pdf" || ext == "pdf"
            val isImage = type.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "heic")
            if (!isPdf && !isImage) return null
            if (isImage) {
                if (part.size in 0 until MIN_IMAGE_BYTES) return null
                // Pictures shown inside the email (logos, banners) rather than attached.
                val inline = Part.INLINE.equals(part.disposition, ignoreCase = true) || (part.getHeader("Content-ID") != null && name == null)
                if (inline && !Part.ATTACHMENT.equals(part.disposition, ignoreCase = true)) return null
            }
            val fileName = name ?: if (isPdf) "attachment.pdf" else "photo.${ext.ifEmpty { "jpg" }}"
            return fileName to if (isPdf) "application/pdf" else type.takeIf { it.startsWith("image/") } ?: "image/jpeg"
        }

        private fun safeFileName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().take(80).ifBlank { "Email" }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
