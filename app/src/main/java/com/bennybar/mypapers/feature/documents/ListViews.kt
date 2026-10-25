package com.bennybar.mypapers.feature.documents

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Handshake
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Icon
import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.DocumentFiles
import com.bennybar.mypapers.data.Images
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.documentContainerKey
import com.bennybar.mypapers.search.Filters
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** How the home screen lists papers, picked in [ViewToggle]. */
enum class ListView(val label: String, val icon: ImageVector) {
    CARDS("Cards", Icons.Outlined.ViewAgenda),
    COMPACT("Compact list", Icons.AutoMirrored.Outlined.ViewList),
    GRID("Thumbnails", Icons.Outlined.GridView),
    TIMELINE("Timeline by month", Icons.Outlined.CalendarMonth),
}

/** The four views as one connected group: the chosen one fills and changes shape. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ViewToggle(view: ListView, onChange: (ListView) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        ListView.entries.forEachIndexed { i, v ->
            ToggleButton(
                checked = v == view,
                onCheckedChange = { onChange(v) },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    ListView.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.heightIn(min = 32.dp).semantics { role = Role.RadioButton },
            ) {
                Icon(v.icon, contentDescription = v.label, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The paper's kind at a glance. */
private fun Document.kindIcon() = when (docType) {
    "Receipt" -> Icons.Outlined.Receipt
    "Invoice" -> Icons.Outlined.RequestQuote
    "Warranty" -> Icons.Outlined.VerifiedUser
    "Contract" -> Icons.Outlined.Handshake
    else -> Icons.Outlined.Description
}

/** Each kind in its own shape, so a list reads at a glance: receipt, invoice, warranty, contract, other. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Document.kindShape(): Shape = when (docType) {
    "Receipt" -> MaterialShapes.Cookie6Sided
    "Invoice" -> MaterialShapes.Clover4Leaf
    "Warranty" -> MaterialShapes.Gem
    "Contract" -> MaterialShapes.Sunny
    else -> MaterialShapes.Square
}.toShape()

/** "12 Oct", with the year when it isn't this one ("12 Oct 2025"). */
private fun shortDate(day: LocalDate): String =
    day.format(DateTimeFormatter.ofPattern(if (day.year == LocalDate.now().year) "d MMM" else "d MMM yyyy", Format.locale))

/** Opening a paper grows it from where it is in the list, as the cards do. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.paperBounds(id: String, shape: Shape): Modifier = with(LocalSharedTransitionScope.current) {
    this@paperBounds.sharedBounds(
        rememberSharedContentState(documentContainerKey(id)),
        LocalNavAnimatedScope.current,
        resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
        clipInOverlayDuringTransition = OverlayClip(shape),
    )
}

/** Backed up to Google Drive yet (signed in only). */
@Composable
private fun CloudMark(doc: Document, signedIn: Boolean, modifier: Modifier = Modifier) {
    if (!signedIn) return
    val cs = MaterialTheme.colorScheme
    Icon(
        if (doc.dirty) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone,
        contentDescription = if (doc.dirty) "Not backed up yet" else "Backed up",
        tint = if (doc.dirty) cs.outline else cs.primary,
        modifier = modifier.size(16.dp),
    )
}

/**
 * One line per paper: its kind (or, in the timeline, the day of the month), title, date and amount.
 * [day]: the timeline's day number, shown instead of the kind and the date.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CompactRow(doc: Document, signedIn: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, day: Int? = null) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) cs.tertiaryContainer else cs.surfaceContainerLow)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .paperBounds(doc.id, shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when {
            selected -> Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = cs.tertiary, modifier = Modifier.size(32.dp))
            day != null -> Text(
                "$day",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(32.dp),
            )
            else -> Box(Modifier.size(34.dp).background(cs.secondaryContainer, doc.kindShape()), contentAlignment = Alignment.Center) {
                Icon(doc.kindIcon(), contentDescription = doc.docType.ifBlank { null }, tint = cs.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
        }
        Text(
            doc.title(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (day == null) Text(shortDate(Filters.paperDay(doc)), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        doc.totalCents?.let { Text(Format.amount(it, doc.currency), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
        CloudMark(doc, signedIn)
    }
}

/** A page thumbnail with the title and "₪312 · 12 Oct" under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GridTile(doc: Document, signedIn: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .clip(shape)
                .background(cs.surfaceContainerHighest)
                .then(if (selected) Modifier.border(3.dp, cs.primary, shape) else Modifier)
                .paperBounds(doc.id, shape),
        ) {
            Thumbnail(doc)
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = cs.primary, modifier = Modifier.align(Alignment.TopStart).padding(8.dp).size(24.dp))
            }
            if (signedIn) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).background(cs.surface.copy(alpha = 0.9f), CircleShape), contentAlignment = Alignment.Center) {
                    CloudMark(doc, signedIn = true)
                }
            }
        }
        Text(doc.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
        Text(
            listOfNotNull(doc.totalCents?.let { Format.amount(it, doc.currency) }, shortDate(Filters.paperDay(doc))).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** "SEPTEMBER 2026", then how many and what they add up to (per currency). */
@Composable
fun MonthHeader(month: YearMonth, docs: List<Document>) {
    val totals = docs.filter { it.totalCents != null }.groupBy { it.currency }
        .map { (currency, l) -> Format.amount(l.sumOf { it.totalCents!! }, currency) }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Format.locale)).uppercase(Format.locale),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(if (docs.size == 1) "1 paper" else "${docs.size} papers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (totals.isNotEmpty()) Text(totals.joinToString(" · "), style = MaterialTheme.typography.titleSmall)
    }
}

/** Papers by the month of their date (the day added when there's none), newest month first unless [oldestFirst]. */
fun monthGroups(docs: List<Document>, oldestFirst: Boolean): List<Pair<YearMonth, List<Document>>> {
    val byDay = if (oldestFirst) docs.sortedBy(Filters::paperDay) else docs.sortedByDescending(Filters::paperDay)
    return byDay.groupBy { YearMonth.from(Filters.paperDay(it)) }.toList()
}

/** The first page, small: photos and PDFs drawn once and kept while the app runs. */
@Composable
private fun Thumbnail(doc: Document) {
    val key = "${doc.id}:${doc.fileHash}"
    val bitmap by produceState(Thumbnails.cache.get(key), key, doc.fileLocal) {
        if (value == null && doc.fileLocal) value = withContext(Dispatchers.IO) { Thumbnails.render(doc) }?.also { Thumbnails.cache.put(key, it) }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxSize())
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(doc.kindIcon(), contentDescription = null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(36.dp))
        }
    }
}

private object Thumbnails {
    private const val WIDTH = 400

    /** About 30 MB of thumbnails. */
    val cache = object : LruCache<String, Bitmap>(30 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun render(doc: Document): Bitmap? = runCatching {
        val file = app.repository.files.file(doc.id)
        if ((doc.mimeType ?: DocumentFiles.sniffMimeType(file)) != "application/pdf") return@runCatching Images.decode(file, WIDTH * 2)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                pdf.openPage(0).use { page ->
                    val scale = WIDTH.toFloat() / page.width
                    // The top of a long page is enough to know it by.
                    val height = minOf(page.height * scale, WIDTH * 1.5f).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, android.graphics.Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    }.getOrNull()
}
