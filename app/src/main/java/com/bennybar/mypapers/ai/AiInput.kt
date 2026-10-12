package com.bennybar.mypapers.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.bennybar.mypapers.data.DocumentFiles
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File

/** A document file as Responses API content parts. */
object AiInput {
    /** Longest side of a photo sent to the model: enough to read a receipt, far cheaper than the full photo. */
    private const val MAX_IMAGE_SIDE = 2048

    /** PDFs up to this size go as they are (the model reads their text and pages); bigger ones as page images. */
    private const val MAX_PDF_BYTES = 20L * 1024 * 1024
    private const val MAX_PDF_PAGES_AS_IMAGES = 5

    fun parts(file: File, mimeType: String?, fileName: String?): List<JsonObject> {
        val type = mimeType ?: DocumentFiles.sniffMimeType(file)
        return when {
            type == "application/pdf" && file.length() <= MAX_PDF_BYTES -> listOf(
                buildJsonObject {
                    put("type", "input_file")
                    put("filename", fileName?.takeIf { it.endsWith(".pdf", ignoreCase = true) } ?: "document.pdf")
                    put("file_data", "data:application/pdf;base64," + Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                },
            )
            type == "application/pdf" -> pdfPages(file).map(::imagePart)
            else -> listOfNotNull(decodeScaled(file)?.let(::imagePart))
        }
    }

    private fun imagePart(bitmap: Bitmap): JsonObject {
        // JPEG has no transparency: a transparent PNG would turn black, hiding dark text. Paper is white.
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return buildJsonObject {
            put("type", "input_image")
            put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun pdfPages(file: File): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, MAX_PDF_PAGES_AS_IMAGES)).map { i ->
                    pdf.openPage(i).use { page ->
                        val scale = MAX_IMAGE_SIDE.toFloat() / maxOf(page.width, page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }
}
