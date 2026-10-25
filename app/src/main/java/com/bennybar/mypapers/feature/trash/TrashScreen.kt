package com.bennybar.mypapers.feature.trash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.TRASH_DAYS
import com.bennybar.mypapers.feature.documents.title
import com.bennybar.mypapers.ui.Format
import com.bennybar.mypapers.ui.layoutStart
import kotlinx.coroutines.launch

/** Deleted papers, for [TRASH_DAYS] days: restore one, delete it for good, or empty the trash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val trash by remember { app.repository.observeTrash() }.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    var confirmEmpty by remember { mutableStateOf(false) }
    var forever by remember { mutableStateOf<Document?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trash") },
                navigationIcon = {
                    IconButton(shapes = IconButtonDefaults.shapes(), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!trash.isNullOrEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            item(key = "about") {
                Text(
                    if (trash?.isEmpty() == true) "Nothing in the trash."
                    else "Deleted papers stay here for $TRASH_DAYS days, then they're deleted for good.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            items(trash.orEmpty(), key = { it.id }) { doc ->
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
                        Text(
                            doc.title(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = layoutStart(), modifier = Modifier.fillMaxWidth(),
                        )
                        val daysLeft = (TRASH_DAYS - (System.currentTimeMillis() - doc.trashedAt!!) / (24 * 60 * 60 * 1000L)).coerceAtLeast(0)
                        Text(
                            "Deleted ${Format.day(doc.trashedAt)} · " + if (daysLeft <= 1) "gone tomorrow" else "gone in $daysLeft days",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = layoutStart(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { forever = doc }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
                            FilledTonalButton(onClick = { scope.launch { app.repository.restore(doc.id) } }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the trash?") },
            text = { Text(if (trash.orEmpty().size == 1) "1 paper is deleted for good, from all your devices." else "${trash.orEmpty().size} papers are deleted for good, from all your devices.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    scope.launch { app.repository.emptyTrash() }
                }) { Text("Empty trash", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }
    forever?.let { doc ->
        AlertDialog(
            onDismissRequest = { forever = null },
            title = { Text("Delete for good?") },
            text = { Text("“${doc.title()}” is deleted from all your devices. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    forever = null
                    scope.launch { app.repository.delete(doc.id) }
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forever = null }) { Text("Cancel") } },
        )
    }
}
