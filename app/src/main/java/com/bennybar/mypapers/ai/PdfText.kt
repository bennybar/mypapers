package com.bennybar.mypapers.ai

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/** A PDF's own text (Android 15's reader), page after page, on the phone: nothing sent anywhere. */
object PdfText {
    /** Enough for a long contract; a paper's text is kept and synced with it. */
    const val MAX_CHARS = 30_000

    /** "" for a scan with no text in it, or a file that isn't a readable PDF. */
    fun read(file: File): String = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                buildString {
                    for (i in 0 until pdf.pageCount) {
                        if (length >= MAX_CHARS) break
                        pdf.openPage(i).use { page -> page.textContents.forEach { append(it.text).append('\n') } }
                    }
                }.trim().take(MAX_CHARS)
            }
        }
    }.getOrDefault("")
}
