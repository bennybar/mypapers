package com.bennybar.mypapers.feature.documents

import android.content.Intent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.AI_FAILED
import com.bennybar.mypapers.data.AI_PENDING
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.KeyDetail
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.documentContainerKey
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DocumentDetailScreen(id: String, onBack: () -> Unit, onEdit: () -> Unit, onOpen: (String) -> Unit) {
    val flow = remember(id) { app.repository.observe(id) }
    val doc by flow.collectAsStateWithLifecycle(null)
    var seen by remember { mutableStateOf(false) }
    // Deleted (here or on another device) while open: leave.
    LaunchedEffect(doc) { if (doc != null) seen = true else if (seen) onBack() }

    val animatedScope = LocalNavAnimatedScope.current
    // The page's corners round off as it shrinks back into the card (28dp, the card's radius).
    val corner by animatedScope.transition.animateDp(label = "corner") {
        if (it == EnterExitState.Visible) 0.dp else 28.dp
    }
    val shape = RoundedCornerShape(corner)
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSet = app.ai.config.collectAsStateWithLifecycle().value.isSet
    val signedIn = app.server.session.collectAsStateWithLifecycle().value != null

    with(LocalSharedTransitionScope.current) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.sharedBounds(
                rememberSharedContentState(documentContainerKey(id)),
                animatedScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(shape),
            ),
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(
                            enabled = doc?.fileLocal == true,
                            onClick = { doc?.let { d -> scope.launch { Sharing.share(context, listOf(d)) } } },
                        ) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                        IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Open with…") },
                                enabled = doc?.fileLocal == true,
                                onClick = {
                                    menu = false
                                    doc?.let { d ->
                                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", app.repository.files.file(d.id))
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                                null,
                                            ),
                                        )
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Read with AI") },
                                enabled = aiSet && doc?.fileLocal == true && doc?.aiState != AI_PENDING,
                                onClick = {
                                    menu = false
                                    scope.launch { app.repository.requestReading(id) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            },
        ) { padding ->
            doc?.let { d ->
                DocumentDetails(
                    d, Modifier.padding(padding), onEdit,
                    canRead = aiSet,
                    onRead = { scope.launch { app.repository.requestReading(id) } },
                    onOpen = onOpen,
                    onKeepBoth = { scope.launch { app.repository.keepBoth(id) } },
                    onDelete = { confirmDelete = true },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this paper?") },
            text = {
                Text(if (signedIn) "It's removed from all your devices." else "It's removed from this phone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { app.repository.delete(id) }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DocumentDetails(
    doc: Document,
    modifier: Modifier,
    onEdit: () -> Unit,
    canRead: Boolean,
    onRead: () -> Unit,
    onOpen: (String) -> Unit,
    onKeepBoth: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Text(
            doc.title(),
            style = MaterialTheme.typography.headlineMedium,
            color = if (doc.title.isBlank() && doc.vendor.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = doc.subtitle()
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.padding(top = 4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (doc.aiState == AI_PENDING || doc.aiState == AI_FAILED) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                AiStatus(doc, Modifier.weight(1f))
                if (doc.aiState == AI_FAILED && canRead) TextButton(onClick = onRead) { Text("Retry") }
            }
        }
        doc.duplicateOf?.let { otherId -> DuplicateBanner(otherId, onOpen, onKeepBoth, onDelete) }
        Spacer(Modifier.padding(top = 16.dp))
        if (doc.totalCents != null) {
            Text(Format.amount(doc.totalCents, doc.currency), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
            doc.vatCents?.let {
                Text(
                    "VAT ${Format.amount(it, doc.currency)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else if (doc.aiState.isEmpty() && doc.title.isBlank() && doc.vendor.isBlank()) {
            // Nothing known about it yet: the ways to fill it in.
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (canRead && doc.fileLocal) {
                    FilledTonalButton(onClick = onRead) {
                        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Read with AI")
                    }
                    OutlinedButton(onClick = onEdit) { Text("Fill in by hand") }
                } else {
                    FilledTonalButton(onClick = onEdit) { Text("Fill in by hand") }
                }
            }
        }
        if (doc.tags.isNotEmpty()) {
            Spacer(Modifier.padding(top = 16.dp))
            TagRow(doc.tags)
        }
        if (doc.details.isNotEmpty()) {
            Spacer(Modifier.padding(top = 16.dp))
            DetailsCard(doc.details)
        }
        if (doc.notes.isNotBlank()) {
            Spacer(Modifier.padding(top = 16.dp))
            Text(doc.notes, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.padding(top = 24.dp))
        DocumentPreview(app.repository.files.file(doc.id), doc.mimeType, available = doc.fileLocal)
        Spacer(Modifier.padding(top = 24.dp))
    }
}

/** "Looks like a duplicate of …": open the other one, keep both, or delete this one. */
@Composable
private fun DuplicateBanner(otherId: String, onOpen: (String) -> Unit, onKeepBoth: () -> Unit, onDelete: () -> Unit) {
    val other by remember(otherId) { app.repository.observe(otherId) }.collectAsStateWithLifecycle(null)
    val o = other ?: return // the other one is gone: nothing to compare with
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.padding(top = 16.dp),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp)) {
            Text(
                "Looks like a duplicate of ${o.title()}" + (Format.date(o.date)?.let { " ($it)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Row {
                TextButton(onClick = { onOpen(otherId) }) { Text("Open it") }
                TextButton(onClick = onKeepBoth) { Text("Keep both") }
                TextButton(onClick = onDelete) { Text("Delete this") }
            }
        }
    }
}

/** The AI's key details for this paper (a prescription's values, a policy number…). */
@Composable
private fun DetailsCard(details: List<KeyDetail>) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            details.forEach { d ->
                Column {
                    Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(d.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
