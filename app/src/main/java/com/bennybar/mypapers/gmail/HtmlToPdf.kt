package com.bennybar.mypapers.gmail

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfDocument
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume

/**
 * An email's HTML as an A4 PDF, drawn by an offscreen WebView. Nothing is
 * loaded from the network (no remote images or tracking pixels): the receipt's
 * text and layout are what matters.
 */
object HtmlToPdf {
    /** Layout width in CSS pixels: what most emails are designed for. */
    private const val WIDTH_PX = 800
    private const val A4_WIDTH_PT = 595
    private const val A4_HEIGHT_PT = 842

    private var slowDrawEnabled = false

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun render(context: Context, html: String, out: File) = withContext(Dispatchers.Main) {
        if (!slowDrawEnabled) {
            // Lets an offscreen WebView draw its whole page, not just a screenful (must precede the first WebView).
            WebView.enableSlowWholeDocumentDraw()
            slowDrawEnabled = true
        }
        val webView = WebView(context.applicationContext)
        try {
            webView.settings.apply {
                javaScriptEnabled = false
                blockNetworkLoads = true
                blockNetworkImage = true
                loadsImagesAutomatically = false
            }
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            val widthSpec = View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            webView.measure(widthSpec, heightSpec)
            webView.layout(0, 0, WIDTH_PX, 1)
            withTimeout(30_000) {
                suspendCancellableCoroutine { cont ->
                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            if (cont.isActive) cont.resume(Unit)
                        }
                    }
                    webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
            // Let it lay out the loaded page, then size the view to the whole document.
            delay(300)
            webView.measure(widthSpec, heightSpec)
            val height = maxOf(webView.measuredHeight, (webView.contentHeight * webView.scale).toInt(), 1)
            webView.layout(0, 0, WIDTH_PX, height)
            delay(100)

            val scale = A4_WIDTH_PT.toFloat() / WIDTH_PX
            val pageHeightPx = (A4_HEIGHT_PT / scale).toInt()
            val pdf = PdfDocument()
            try {
                var top = 0
                var number = 1
                while (top < height) {
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, number++).create())
                    page.canvas.apply {
                        scale(scale, scale)
                        translate(0f, -top.toFloat())
                        clipRect(0, top, WIDTH_PX, top + pageHeightPx)
                        webView.draw(this)
                    }
                    pdf.finishPage(page)
                    top += pageHeightPx
                }
                withContext(Dispatchers.IO) { out.outputStream().use { pdf.writeTo(it) } }
            } finally {
                pdf.close()
            }
        } finally {
            webView.destroy()
        }
    }
}
