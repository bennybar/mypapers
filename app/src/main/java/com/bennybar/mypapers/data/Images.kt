package com.bennybar.mypapers.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File

/** Image decoding that honors the camera's orientation (EXIF), unlike BitmapFactory. */
object Images {
    /** Decoded upright, scaled down so the longest side is at most [maxSide]. */
    fun decode(source: ImageDecoder.Source, maxSide: Int): Bitmap =
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= maxSide) sample *= 2
            decoder.setTargetSampleSize(sample)
            // Software bitmaps: drawn into PDFs and compressed, not just shown.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    fun decode(file: File, maxSide: Int): Bitmap? = runCatching { decode(ImageDecoder.createSource(file), maxSide) }.getOrNull()

    /** The scanner's page photos as one PDF, each page upright. */
    fun scanToPdf(context: Context, pages: List<Uri>, out: File) {
        val pdfPages = pages.map { uri ->
            val bitmap = decode(ImageDecoder.createSource(context.contentResolver, uri), maxSide = 2400)
            val jpeg = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            JpegPdf.Page(jpeg, bitmap.width, bitmap.height).also { bitmap.recycle() }
        }
        out.outputStream().use { JpegPdf.write(pdfPages, it) }
    }
}
