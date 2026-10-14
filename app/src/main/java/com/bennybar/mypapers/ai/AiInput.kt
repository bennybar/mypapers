package com.bennybar.mypapers.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.Images
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
    private const val MAX_PDF_BYTES = 8L * 1024 * 1024
    private const val MAX_PDF_PAGES_AS_IMAGES = 5

    /** A page for writing out: decoded this big, then cut into close-ups about [TILE] across. */
    private const val CLOSE_UP_SIDE = 3600
    private const val TILE = 1300
    private const val MIN_TILE_SIDE = 1000

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
        }.ifEmpty { throw AiException("Can't read this kind of file", retryable = false) }
    }

    /**
     * For writing a whole paper out: each page whole, then in overlapping close-ups, so small
     * writing gets many more pixels (a whole photo is read at well under 1000px across).
     * A PDF with its own text goes as it is.
     */
    fun closeUpParts(file: File, mimeType: String?, fileName: String?): List<JsonObject> {
        val type = mimeType ?: DocumentFiles.sniffMimeType(file)
        if (type == "application/pdf" && PdfText.read(file).isNotEmpty()) return parts(file, mimeType, fileName)
        // One big page in memory at a time.
        fun page(p: Int, count: Int, page: Bitmap): List<JsonObject> =
            listOf(textPart("Page ${p + 1} of $count, whole:"), imagePart(scaled(page, MAX_IMAGE_SIDE))) +
                tiles(page).flatMap { (label, tile) -> listOf(textPart("Page ${p + 1}, close-up $label:"), imagePart(tile)) }
        val parts = if (type == "application/pdf") {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { pdf ->
                    val count = minOf(pdf.pageCount, MAX_PDF_PAGES_AS_IMAGES)
                    (0 until count).flatMap { i -> pdf.openPage(i).use { page(i, count, render(it, CLOSE_UP_SIDE)) } }
                }
            }
        } else {
            Images.decode(file, CLOSE_UP_SIDE)?.let { page(0, 1, it) }.orEmpty()
        }
        return parts.ifEmpty { throw AiException("Can't read this kind of file", retryable = false) }
    }

    /**
     * Overlapping pieces about [TILE] across, labeled "row 1 of 3, column 2 of 2" (columns left to right).
     * Always at least 2 across and about square, even for a small photo (one sent through a chat app):
     * enlarged, its close-ups are read far better than the whole of it.
     */
    private fun tiles(page: Bitmap): List<Pair<String, Bitmap>> {
        val cols = maxOf(2, Math.ceil(page.width.toDouble() / TILE).toInt())
        val rows = maxOf(Math.ceil(page.height.toDouble() / TILE).toInt(), Math.round(cols.toDouble() * page.height / page.width).toInt(), 1)
        // Each a sixth bigger than its share, so a line cut at one edge is whole in the next.
        val w = minOf(page.width, page.width * 7 / (cols * 6))
        val h = minOf(page.height, page.height * 7 / (rows * 6))
        return (0 until rows).flatMap { r ->
            (0 until cols).map { c ->
                val x = if (cols == 1) 0 else (page.width - w) * c / (cols - 1)
                val y = if (rows == 1) 0 else (page.height - h) * r / (rows - 1)
                "row ${r + 1} of $rows, column ${c + 1} of $cols" to enlarged(Bitmap.createBitmap(page, x, y, w, h))
            }
        }
    }

    /** A close-up at least [MIN_TILE_SIDE] on its shorter side (the model reads small pictures at their size). */
    private fun enlarged(b: Bitmap): Bitmap {
        val scale = MIN_TILE_SIDE.toFloat() / minOf(b.width, b.height)
        return if (scale <= 1f) b else Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
    }

    private fun scaled(b: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / maxOf(b.width, b.height)
        return if (scale >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
    }

    private fun textPart(text: String) = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    private fun imagePart(bitmap: Bitmap): JsonObject {
        // JPEG has no transparency: a transparent PNG would turn black, hiding dark text. Paper is white.
        val opaque = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(opaque).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
        val out = ByteArrayOutputStream()
        opaque.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return buildJsonObject {
            put("type", "input_image")
            // Looked at closely (left to the model, a photo of small writing may be read too small).
            put("detail", "high")
            put("image_url", "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        }
    }

    /** Upright (EXIF orientation), so the model doesn't read a sideways receipt. */
    private fun decodeScaled(file: File): Bitmap? = Images.decode(file, MAX_IMAGE_SIDE)

    private fun pdfPages(file: File): List<Bitmap> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                (0 until minOf(pdf.pageCount, MAX_PDF_PAGES_AS_IMAGES)).map { i -> pdf.openPage(i).use { render(it, MAX_IMAGE_SIDE) } }
            }
        }

    /** A PDF page on white, [side] pixels on its longer side. */
    private fun render(page: PdfRenderer.Page, side: Int): Bitmap {
        val scale = side.toFloat() / maxOf(page.width, page.height)
        val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }
}
