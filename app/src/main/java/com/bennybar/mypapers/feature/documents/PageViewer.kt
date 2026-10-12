package com.bennybar.mypapers.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bennybar.mypapers.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Sharper than the preview, for zooming; a long page gets fewer pixels across instead of being cut. */
private const val VIEWER_WIDTH = 2000
private const val VIEWER_MAX_PIXELS = 12_000_000
private const val MAX_ZOOM = 8f

/**
 * Every page full screen: swipe between pages, pinch or double-tap to zoom.
 * [pageCount]: 1 for a photo.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PageViewer(file: File, isPdf: Boolean, pageCount: Int, startPage: Int, onClose: () -> Unit) {
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        val pager = rememberPagerState(startPage.coerceIn(0, pageCount - 1)) { pageCount }
        Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1) { i ->
                val page by produceState<Bitmap?>(null, file, i) {
                    value = withContext(Dispatchers.IO) {
                        runCatching { if (isPdf) renderPage(file, i) else Images.decode(file, maxSide = VIEWER_WIDTH * 2) }.getOrNull()
                    }
                }
                val bitmap = page
                if (bitmap == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                } else {
                    ZoomableImage(bitmap)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    // Readable over a zoomed white page too.
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f))
                    .safeDrawingPadding()
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = androidx.compose.ui.graphics.Color.White)
                }
                if (pageCount > 1) {
                    Text(
                        "${pager.currentPage + 1} of $pageCount",
                        style = MaterialTheme.typography.titleMedium,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
}

/** Pinch to zoom, drag to move once zoomed (a one-finger swipe at normal size turns the page). */
@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        // Zoom in on the tapped spot.
                        scale = 3f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((center - tap) * 2f, scale, size.width, size.height)
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers > 1 || scale > 1f) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale == 1f) Offset.Zero else clamp(offset + event.calculatePan(), scale, size.width, size.height)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Image(
            bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}

/** Keeps the zoomed page covering the screen (no dragging it off into the black). */
private fun clamp(offset: Offset, scale: Float, width: Int, height: Int): Offset {
    val maxX = width * (scale - 1) / 2
    val maxY = height * (scale - 1) / 2
    return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
}

/** One PDF page, whole, as sharp as memory allows. */
private fun renderPage(file: File, index: Int): Bitmap =
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { pdf ->
            pdf.openPage(index).use { page ->
                val aspect = page.height.toFloat() / page.width
                val width = minOf(VIEWER_WIDTH.toFloat(), kotlin.math.sqrt(VIEWER_MAX_PIXELS / aspect)).toInt()
                val scale = width.toFloat() / page.width
                val bitmap = Bitmap.createBitmap(width, (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
