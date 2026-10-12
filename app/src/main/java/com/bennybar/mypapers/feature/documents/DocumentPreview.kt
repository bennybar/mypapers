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

/** The document's pages: a photo as itself, a PDF page by page. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DocumentPreview(file: File, mimeType: String?, available: Boolean, modifier: Modifier = Modifier) {
    val pages by produceState<List<Bitmap>?>(null, file, available) {
        value = if (available) withContext(Dispatchers.IO) { runCatching { render(file, mimeType) }.onFailure { Log.w("DocumentPreview", "Can't render ${file.name} ($mimeType, ${file.length()} bytes)", it) }.getOrDefault(emptyList()) } else null
    }
    val shape = MaterialTheme.shapes.large
    val list = pages
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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        list.forEach { page ->
            Image(
                page.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                // On white, like paper (a transparent image would show dark text on the dark page).
                modifier = Modifier.fillMaxWidth().clip(shape).background(androidx.compose.ui.graphics.Color.White),
            )
        }
    }
}

private fun render(file: File, mimeType: String?): List<Bitmap> =
    if ((mimeType ?: DocumentFiles.sniffMimeType(file)) == "application/pdf") renderPdf(file) else listOfNotNull(decodeImage(file))

private fun renderPdf(file: File): List<Bitmap> =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            (0 until minOf(pdf.pageCount, MAX_PAGES)).map { i ->
                pdf.openPage(i).use { page ->
                    val scale = PREVIEW_WIDTH.toFloat() / page.width
                    val height = minOf(page.height * scale, PREVIEW_WIDTH * MAX_PAGE_ASPECT).toInt()
                    val bitmap = Bitmap.createBitmap(PREVIEW_WIDTH, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }

/** Upright, as the camera took it (EXIF orientation). */
private fun decodeImage(file: File): Bitmap? = Images.decode(file, maxSide = PREVIEW_WIDTH * 2)
