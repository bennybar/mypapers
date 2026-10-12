package com.bennybar.mypapers.feature.documents

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
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
import kotlinx.coroutines.async
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aiSet = app.ai.config.collectAsStateWithLifecycle().value.isSet
    var askDialog by rememberSaveable { mutableStateOf(false) }
    var asking by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun ask(instruction: String) {
        asking = instruction
        // Runs on if the page is left; the answer lands in the paper's details either way.
        val answer = app.scope.async { runCatching { app.repository.ask(id, instruction) } }
        scope.launch {
            val result = answer.await()
            asking = null
            val found = result.getOrNull()
            snackbar.showSnackbar(
                when {
                    found == null -> "Couldn't ask: ${result.exceptionOrNull()?.message}"
                    found.isEmpty() -> "Not found in this paper"
                    found.size == 1 -> "Added to details: ${found.single().label}"
                    else -> "Added ${found.size} details"
                },
            )
        }
    }

    with(LocalSharedTransitionScope.current) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            snackbarHost = { SnackbarHost(snackbar) },
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
                                text = { Text("Ask AI…") },
                                enabled = aiSet && doc?.fileLocal == true && asking == null,
                                onClick = { menu = false; askDialog = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Move to trash", color = MaterialTheme.colorScheme.error) },
                                // Restorable from the trash for 30 days: no need to ask. The page closes as it goes.
                                onClick = { menu = false; scope.launch { app.repository.trash(id) } },
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
                    asking = asking,
                    onRead = { scope.launch { app.repository.requestReading(id) } },
                    onOpen = onOpen,
                    onKeepBoth = { scope.launch { app.repository.keepBoth(id) } },
                    onDelete = { scope.launch { app.repository.trash(id) } },
                )
            }
        }
    }
    if (askDialog) AskDialog(onDismiss = { askDialog = false }, onAsk = { askDialog = false; ask(it) })
}

/** "What should the AI find?": a one-off instruction for this paper. */
@Composable
private fun AskDialog(onDismiss: () -> Unit, onAsk: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ask AI") },
        text = {
            Column {
                Text(
                    "What should it find in this paper? The answer is added to its key details.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("e.g. extract the URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAsk(text.trim()) }),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAsk(text.trim()) }) { Text("Ask") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DocumentDetails(
    doc: Document,
    modifier: Modifier,
    onEdit: () -> Unit,
    canRead: Boolean,
    asking: String?,
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
        if (asking != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                LoadingIndicator(Modifier.size(28.dp))
                Text(
                    "Asking the AI: $asking",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        ExpiryLabel(doc, Modifier.padding(top = 12.dp))
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
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // Dates the AI wrote as 2027-08-15, in the region's format.
    fun shown(value: String) = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) Format.date(value) ?: value else value
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Details", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                // All of them at once, "Label: value" a line.
                IconButton(onClick = {
                    val text = details.joinToString("\n") { "${it.label}: ${shown(it.value)}" }
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Details", text))) }
                }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy details") }
            }
            // Any part can be selected and copied; web addresses open.
            SelectionContainer {
                Column(Modifier.padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    details.forEach { d ->
                        Column {
                            Text(d.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(withLinks(shown(d.value)), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+[^\s<>".,;:!?)\]]""")

/** [text] with its web addresses as links. */
@Composable
private fun withLinks(text: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        URL.findAll(text).forEach { m ->
            append(text.substring(at, m.range.first))
            val url = if (m.value.startsWith("www.", ignoreCase = true)) "https://${m.value}" else m.value
            withLink(LinkAnnotation.Url(url, style)) { append(m.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}
