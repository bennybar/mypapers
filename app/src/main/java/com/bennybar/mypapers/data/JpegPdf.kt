package com.bennybar.mypapers.data

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A PDF with one JPEG image per page, embedded as-is (DCTDecode): scans stay small
 * and look exactly like the photos. Each page is the image's size at 150 dpi.
 */
object JpegPdf {
    class Page(val jpeg: ByteArray, val width: Int, val height: Int)

    fun write(pages: List<Page>, out: OutputStream) {
        require(pages.isNotEmpty())
        val body = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun obj(content: ByteArray) {
            offsets += body.size()
            body.write("${offsets.size} 0 obj\n".toByteArray())
            body.write(content)
            body.write("\nendobj\n".toByteArray())
        }
        fun obj(text: String) = obj(text.toByteArray())

        body.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        // 1: catalog, 2: page tree; then per page: page, content, image.
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        val kids = pages.indices.joinToString(" ") { "${3 + it * 3} 0 R" }
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        pages.forEachIndexed { i, p ->
            val pageObj = 3 + i * 3
            // Points at 150 dpi.
            val w = p.width * 72f / 150f
            val h = p.height * 72f / 150f
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(w)} ${fmt(h)}] /Contents ${pageObj + 1} 0 R /Resources << /XObject << /Im0 ${pageObj + 2} 0 R >> >> >>")
            val draw = "q ${fmt(w)} 0 0 ${fmt(h)} 0 0 cm /Im0 Do Q".toByteArray()
            obj("<< /Length ${draw.size} >>\nstream\n".toByteArray() + draw + "\nendstream".toByteArray())
            obj(
                "<< /Type /XObject /Subtype /Image /Width ${p.width} /Height ${p.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${p.jpeg.size} >>\nstream\n".toByteArray() +
                    p.jpeg + "\nendstream".toByteArray(),
            )
        }
        val xref = body.size()
        val table = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { table.append(String.format("%010d 00000 n \n", it)) }
        table.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        body.write(table.toString().toByteArray())
        body.writeTo(out)
    }

    private fun fmt(f: Float) = String.format(java.util.Locale.ROOT, "%.2f", f)
}
