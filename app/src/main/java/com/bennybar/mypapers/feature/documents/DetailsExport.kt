package com.bennybar.mypapers.feature.documents

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import com.bennybar.mypapers.data.KeyDetail
import com.bennybar.mypapers.data.tableRows
import java.io.File

/**
 * Details as they read in the app, on a white page: the paper's title, then each label and value,
 * tables as tables (a Hebrew one right to left). A PDF is one page as tall as the details, with real
 * text (sharp, selectable); a JPG is the same page drawn at 3x.
 */
class DetailsPage(private val title: String, private val subtitle: String, private val details: List<KeyDetail>) {

    /** Writes the page to [file] as a PDF or, with [pdf] false, a JPG. */
    fun write(file: File, pdf: Boolean) {
        val height = draw(null)
        if (pdf) {
            val document = PdfDocument()
            val page = document.startPage(PdfDocument.PageInfo.Builder(WIDTH, height, 1).create())
            draw(page.canvas)
            document.finishPage(page)
            file.outputStream().use { document.writeTo(it) }
            document.close()
        } else {
            val bitmap = Bitmap.createBitmap(WIDTH * SCALE, height * SCALE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap).apply { scale(SCALE.toFloat(), SCALE.toFloat()) }
            draw(canvas)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
    }

    /** Draws onto [canvas] (or only measures, when null); returns the page's height. */
    private fun draw(canvas: Canvas?): Int {
        canvas?.drawColor(Color.WHITE)
        var y = MARGIN.toFloat()
        fun text(s: String, paint: TextPaint, top: Float = 0f) {
            y += top
            val layout = layout(s, paint, CONTENT)
            canvas?.let { it.save(); it.translate(MARGIN.toFloat(), y); layout.draw(it); it.restore() }
            y += layout.height
        }
        text(title, paint(20f, bold = true))
        if (subtitle.isNotBlank()) text(subtitle, paint(13f, Color.GRAY), top = 2f)
        details.forEachIndexed { i, d ->
            y += 14f
            if (i > 0) {
                canvas?.drawLine(MARGIN.toFloat(), y, (WIDTH - MARGIN).toFloat(), y, LINE)
                y += 14f
            }
            text(d.label, paint(13f, Color.GRAY))
            val rows = tableRows(d.value)
            if (rows != null) y = table(canvas, rows, y + 4f) else text(shownDate(d.value), paint(15f), top = 3f)
        }
        return (y + MARGIN).toInt()
    }

    /** Columns as wide as their longest cell, shrunk to fit (cells wrap); returns where the table ends. */
    private fun table(canvas: Canvas?, rows: List<List<String>>, top: Float): Float {
        val head = paint(13f, bold = true)
        val body = paint(13f)
        val natural = rows.first().indices.map { c -> rows.maxOf { (if (it === rows.first()) head else body).measureText(it[c]) } + GAP }
        val fit = minOf(1f, CONTENT / natural.sum())
        val widths = natural.map { it * fit }
        val rtl = isRtl(rows.flatten().joinToString(""))
        val tableWidth = widths.sum()
        val left = if (rtl) MARGIN + CONTENT - tableWidth else MARGIN.toFloat()
        var y = top
        rows.forEachIndexed { r, cells ->
            if (r > 0) canvas?.drawLine(left, y, left + tableWidth, y, LINE)
            val layouts = cells.mapIndexed { c, cell -> layout(cell, if (r == 0) head else body, (widths[c] - GAP).toInt().coerceAtLeast(1), rtl) }
            var x = 0f
            layouts.forEachIndexed { c, layout ->
                // First column on the right in a right-to-left table.
                val cellLeft = if (rtl) left + tableWidth - x - widths[c] else left + x
                canvas?.let {
                    it.save()
                    it.translate(if (rtl) cellLeft + GAP else cellLeft, y + ROW_PAD)
                    layout.draw(it)
                    it.restore()
                }
                x += widths[c]
            }
            y += layouts.maxOf { it.height } + 2 * ROW_PAD
        }
        return y
    }

    /** [rtl]: a cell of a right-to-left table (numbers too line up on the right); else by the text's own direction. */
    private fun layout(text: String, paint: TextPaint, width: Int, rtl: Boolean? = null): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .apply { if (rtl != null) setTextDirection(if (rtl) TextDirectionHeuristics.RTL else TextDirectionHeuristics.LTR) }
            .build()

    private fun paint(size: Float, color: Int = Color.BLACK, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    companion object {
        /** US Letter's width in points. */
        const val WIDTH = 612
        private const val MARGIN = 36
        private const val CONTENT = WIDTH - 2 * MARGIN
        private const val SCALE = 3
        private const val GAP = 14f
        private const val ROW_PAD = 6f
        private val LINE = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
    }
}
