package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.ai.PaperChanges
import com.bennybar.mypapers.ai.PaperTurn
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.ui.Format

/**
 * Ask AI about one paper, as a conversation: each question or instruction with the AI's reply,
 * what it found (save to details) and what it would change (apply), then a field for a follow-up
 * that reshapes the answer or changes the paper.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskPaperSheet(
    doc: Document,
    turns: List<PaperTurn>,
    onFollowUp: (String) -> Unit,
    onSave: (Int) -> Unit,
    onApply: (Int) -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(doc.title(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }
            turns.forEachIndexed { n, t ->
                if (n > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(t.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                when {
                    !t.done -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    t.error != null -> Text("Couldn't ask: ${t.error}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!t.reply.isNullOrBlank()) SelectionContainer { Text(t.reply, style = MaterialTheme.typography.bodyLarge) }
                        val details = t.details.orEmpty()
                        if (details.isNotEmpty()) {
                            DetailList(details)
                            OutlinedButton(onClick = { onSave(n) }, enabled = !t.saved) { Text(if (t.saved) "Saved to details" else "Save to details") }
                        }
                        t.changes?.let { c -> ChangesCard(doc, c, t.applied) { onApply(n) } }
                    }
                }
            }
            // Only once the last one is answered.
            if (turns.lastOrNull()?.done == true) {
                FollowUpField("Follow up, or tell it what to change", onFollowUp, Modifier.padding(top = 20.dp))
            }
        }
    }
}

/** What the AI would change on the paper, before → after, with the button that does it. */
@Composable
private fun ChangesCard(doc: Document, c: PaperChanges, applied: Boolean, onApply: () -> Unit) {
    val lines = buildList {
        c.title?.let { add("Title: ${doc.title.ifBlank { "—" }} → $it") }
        c.vendor?.let { add("Vendor: ${doc.vendor.ifBlank { "—" }} → $it") }
        c.docType?.let { add("Type: ${doc.docType.ifBlank { "—" }} → $it") }
        c.date?.let { add("Date: ${Format.date(doc.date) ?: "—"} → ${Format.date(it) ?: it}") }
        c.total?.let { t ->
            val currency = c.currency ?: doc.currency
            add("Amount: ${doc.totalCents?.let { Format.amount(it, doc.currency) } ?: "—"} → ${Format.amount(Math.round(t * 100), currency)}")
        }
        if (c.total == null) c.currency?.let { add("Currency: ${doc.currency ?: "—"} → $it") }
        if (c.addTags.isNotEmpty()) add("Add tags: ${c.addTags.joinToString(", ")}")
        if (c.removeTags.isNotEmpty()) add("Remove tags: ${c.removeTags.joinToString(", ")}")
        if (c.removeDetails.isNotEmpty()) add("Remove details: ${c.removeDetails.joinToString(", ")}")
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Changes to this paper", style = MaterialTheme.typography.labelLarge)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Button(onClick = onApply, enabled = !applied, modifier = Modifier.padding(top = 6.dp)) { Text(if (applied) "Applied" else "Apply changes") }
        }
    }
}
