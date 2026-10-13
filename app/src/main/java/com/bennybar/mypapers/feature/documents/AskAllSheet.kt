package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.ai.AskAllAnswer
import com.bennybar.mypapers.ui.Format

/** A question about all the papers: the AI's answer, and the papers it comes from to open. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskAllSheet(question: String, result: Result<AskAllAnswer>?, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            val r = result
            when {
                r == null -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                r.isFailure -> Text(
                    "Couldn't ask: ${r.exceptionOrNull()?.message}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                else -> {
                    val a = r.getOrThrow()
                    SelectionContainer { Text(a.answer, style = MaterialTheme.typography.bodyLarge) }
                    // Added up here, not by the AI.
                    a.totals?.takeIf { it.isNotEmpty() }?.let { totals ->
                        Text(
                            "Total: " + totals.entries.joinToString(" · ") { (currency, cents) -> Format.amount(cents, currency.ifEmpty { null }) },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        val counted = a.papers.count { it.totalCents != null }
                        Text(
                            if (counted == 1) "From 1 paper" else "From $counted papers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (a.papers.isNotEmpty()) {
                        Text(
                            "From your papers",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            a.papers.forEachIndexed { i, doc ->
                                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                ListItem(
                                    headlineContent = { Text(doc.title()) },
                                    supportingContent = { Text(doc.subtitle()) },
                                    trailingContent = doc.totalCents?.let { c -> { Text(Format.amount(c, doc.currency)) } },
                                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                    modifier = Modifier.clickable { onOpen(doc.id) },
                                )
                            }
                        }
                    }
                }
            }
            Text(
                "Answers can be wrong — check the papers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}
