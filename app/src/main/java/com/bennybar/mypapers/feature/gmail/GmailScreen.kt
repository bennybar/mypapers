package com.bennybar.mypapers.feature.gmail

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.app
import com.bennybar.mypapers.gmail.GmailCandidate
import com.bennybar.mypapers.gmail.GmailTriage
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.launch

/** Section headings, in [GmailTriage.CATEGORIES] order; null = not sorted by the AI yet. */
private fun sectionTitle(category: String?) = when (category) {
    "Receipt" -> "Receipts"
    "Invoice" -> "Invoices"
    "Bill" -> "Bills"
    "Statement" -> "Statements"
    "Contract" -> "Contracts and forms"
    "Warranty" -> "Warranties"
    "Ticket" -> "Tickets and bookings"
    "Document" -> "Other documents"
    GmailTriage.NOT_A_PAPER -> "Probably not papers"
    else -> "Not sorted yet"
}

private val sectionOrder = GmailTriage.CATEGORIES.dropLast(1) + listOf(null, GmailTriage.NOT_A_PAPER)

/**
 * What the Gmail scan found, grouped by email and — once the AI has sorted it —
 * by kind, with its title, vendor and amount: pick what to add, or mark it "not papers".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GmailScreen(onBack: () -> Unit) {
    val pending by app.gmail.observePending().collectAsStateWithLifecycle(emptyList())
    val busy by app.gmail.observeBusy().collectAsStateWithLifecycle(false)
    val error by app.gmail.observeScanError().collectAsStateWithLifecycle(null)
    val unsorted by app.gmail.observeUnsortedCount().collectAsStateWithLifecycle(0)
    val ai by app.ai.config.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var showNotPapers by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val choosable = pending.filter { it.state != GmailCandidate.STATE_QUEUED }
    // Forget selections of items that left the list (imported, dismissed).
    val chosen = selected.intersect(choosable.map { it.key }.toSet())
    val likely = choosable.filter { it.likelyPaper == true }.map { it.key }.toSet()

    // Emails (an email's items share its sorting), then sections in order.
    val emails = pending.groupBy { it.gmailId }.values.toList()
    val sections = emails.groupBy { it.first().category }.toList().sortedBy { (c, _) -> sectionOrder.indexOf(c) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("From Gmail") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (ai.isSet && unsorted > 0) {
                        IconButton(enabled = !busy, onClick = { app.gmail.sortNow() }) {
                            Icon(Icons.Outlined.AutoAwesome, contentDescription = "Sort with AI")
                        }
                    }
                    IconButton(enabled = !busy, onClick = { app.gmail.scanNow() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Look again")
                    }
                },
            )
        },
        bottomBar = {
            if (choosable.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when {
                            chosen.isNotEmpty() -> TextButton(onClick = { selected = emptySet() }) { Text("Select none") }
                            likely.isNotEmpty() -> TextButton(onClick = { selected = likely }) { Text("Select likely papers") }
                            else -> TextButton(onClick = { selected = choosable.map { it.key }.toSet() }) { Text("Select all") }
                        }
                        TextButton(
                            enabled = chosen.isNotEmpty(),
                            onClick = { scope.launch { app.gmail.dismiss(chosen.toList()); selected = emptySet() } },
                        ) { Text("Not papers") }
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                            Button(
                                enabled = chosen.isNotEmpty(),
                                onClick = { scope.launch { app.gmail.queue(chosen.toList()); selected = emptySet() } },
                            ) { Text(if (chosen.isEmpty()) "Add" else "Add ${chosen.size}") }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
            if (pending.isEmpty() && !busy) {
                Text(
                    "Nothing waiting. New papers from Gmail show up here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (unsorted > 0 && !busy) {
                    item(key = "sort") {
                        SortHint(unsorted, ai.isSet) { app.gmail.sortNow() }
                    }
                }
                sections.forEach { (category, sectionEmails) ->
                    val keys = sectionEmails.flatten().filter { it.state != GmailCandidate.STATE_QUEUED }.map { it.key }.toSet()
                    val collapsed = category == GmailTriage.NOT_A_PAPER && !showNotPapers
                    item(key = "h-$category") {
                        SectionHeader(
                            title = sectionTitle(category),
                            count = sectionEmails.size,
                            allChosen = keys.isNotEmpty() && chosen.containsAll(keys),
                            onToggleAll = { selected = if (chosen.containsAll(keys)) chosen - keys else chosen + keys },
                            collapsible = category == GmailTriage.NOT_A_PAPER,
                            collapsed = collapsed,
                            onToggleCollapsed = { showNotPapers = !showNotPapers },
                        )
                    }
                    if (!collapsed) {
                        items(sectionEmails, key = { it.first().key }) { items ->
                            EmailCard(items, chosen) { key -> selected = if (key in chosen) chosen - key else chosen + key }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SortHint(count: Int, aiSet: Boolean, onSort: () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Text(
                if (aiSet) "$count not sorted yet. The AI can tell receipts and bills from newsletters, and name each one."
                else "Add your OpenAI key in Settings to have the AI sort and name what was found.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            if (aiSet) TextButton(onClick = onSort) { Text("Sort") }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    count: Int,
    allChosen: Boolean,
    onToggleAll: () -> Unit,
    collapsible: Boolean,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).then(if (collapsible) Modifier.clickable(onClick = onToggleCollapsed) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$title · $count", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (collapsible) Icon(if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (collapsed) "Show" else "Hide")
        if (!collapsed) TextButton(onClick = onToggleAll) { Text(if (allChosen) "Unselect" else "Select") }
    }
}

@Composable
private fun EmailCard(items: List<GmailCandidate>, chosen: Set<String>, onToggle: (String) -> Unit) {
    val first = items.first()
    val context = LocalContext.current
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 12.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The AI's name for it when sorted, else the email's subject.
                    Text(
                        first.title ?: first.subject,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    first.totalCents?.let {
                        Text(
                            Format.amount(it, first.currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                Text(
                    listOfNotNull(first.vendor ?: first.sender, Format.day(first.sentAt)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (first.title != null) {
                    Text(
                        first.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            items.forEach { c ->
                val queued = c.state == GmailCandidate.STATE_QUEUED
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !queued) { onToggle(c.key) }
                        .padding(start = 8.dp, end = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = c.key in chosen, onCheckedChange = { onToggle(c.key) }, enabled = !queued)
                    Icon(
                        when {
                            c.kind == GmailCandidate.KIND_EMAIL -> Icons.Outlined.Email
                            c.mimeType == "application/pdf" -> Icons.Outlined.PictureAsPdf
                            else -> Icons.Outlined.Image
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            if (c.kind == GmailCandidate.KIND_EMAIL) "The email itself (as a PDF)" else c.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            when (c.state) {
                                GmailCandidate.STATE_QUEUED -> "Adding…"
                                GmailCandidate.STATE_FAILED -> "Couldn't add: ${c.error.orEmpty()}"
                                else -> if (c.kind == GmailCandidate.KIND_EMAIL) "Receipt in the email" else Formatter.formatShortFileSize(context, c.size)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.state == GmailCandidate.STATE_FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
