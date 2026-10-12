package com.bennybar.mypapers.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pages wider than this are drawn scaled down (a phone-width preview, not print quality). */
private const val PREVIEW_WIDTH = 1080
private const val MAX_PAGES = 10

/** A very long receipt is cut off at this many widths, so one page can't take all the memory. */
private const val MAX_PAGE_ASPECT = 4f

/** What the preview shows: the first pages, out of [total]; [cut]: a long page is shown cut short. */
private class Rendered(val pages: List<Bitmap>, val total: Int, val cut: Boolean)

/** The document's pages: a photo as itself, a PDF page by page. Tap a page for the full-screen viewer. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DocumentPreview(file: File, mimeType: String?, available: Boolean, modifier: Modifier = Modifier) {
    val isPdf = (mimeType ?: DocumentFiles.sniffMimeType(file)) == "application/pdf"
    val rendered by produceState<Rendered?>(null, file, available) {
        value = if (available) withContext(Dispatchers.IO) { runCatching { if (isPdf) renderPdf(file) else Rendered(listOfNotNull(decodeImage(file)), 1, false) }.onFailure { Log.w("DocumentPreview", "Can't render ${file.name} ($mimeType, ${file.length()} bytes)", it) }.getOrDefault(Rendered(emptyList(), 0, false)) } else null
    }
    var viewing by remember { mutableStateOf<Int?>(null) }
    val shape = MaterialTheme.shapes.large
    val list = rendered?.pages
    if (list.isNullOrEmpty()) {
        Box(
            modifier.fillMaxWidth().aspectRatio(0.71f).background(
                // Loading: paper-coloured already, so the page doesn't flash from dark to white.
                if (available && list == null) androidx.compose.ui.graphics.Color.White else MaterialTheme.colorScheme.surfaceContainerLowest,
                shape,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    // Synced from another device: the file follows on the next sync.
                    !available -> {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Text("Downloading…", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                    }
                    list == null -> LoadingIndicator()
                    else -> {
                        Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                        Text("Can't show a preview", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        return
    }
    val r = rendered!!
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEachIndexed { i, page ->
            Image(
                page.asImageBitmap(),
                contentDescription = "Page ${i + 1}",
                contentScale = ContentScale.FillWidth,
                // On white, like paper (a transparent image would show dark text on the dark page).
                modifier = Modifier.fillMaxWidth().clip(shape).background(androidx.compose.ui.graphics.Color.White)
                    .clickable(onClickLabel = "View full screen") { viewing = i },
            )
        }
        // Say what's left out, with the way to it.
        val more = r.total - list.size
        if (more > 0 || r.cut) {
            TextButton(onClick = { viewing = if (more > 0) list.size else 0 }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (more > 0) "Showing ${list.size} of ${r.total} pages · See all" else "Long page cut short · See it all")
            }
        }
    }
    viewing?.let { start -> PageViewer(file, isPdf, r.total, start) { viewing = null } }
}

private fun renderPdf(file: File): Rendered =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            var cut = false
            val pages = (0 until minOf(pdf.pageCount, MAX_PAGES)).map { i ->
                pdf.openPage(i).use { page ->
                    val scale = PREVIEW_WIDTH.toFloat() / page.width
                    if (page.height * scale > PREVIEW_WIDTH * MAX_PAGE_ASPECT) cut = true
                    val height = minOf(page.height * scale, PREVIEW_WIDTH * MAX_PAGE_ASPECT).toInt()
                    val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
            Rendered(pages, pdf.pageCount, cut)
        }
    }

/** Upright, as the camera took it (EXIF orientation). */
private fun decodeImage(file: File): Bitmap? = Images.decode(file, maxSide = PREVIEW_WIDTH * 2)
