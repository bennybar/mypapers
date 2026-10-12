package com.bennybar.mypapers.feature.documents

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.ToggleFloatingActionButtonDefaults.animateIcon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bennybar.mypapers.data.AI_FAILED
import com.bennybar.mypapers.data.AI_PENDING
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.data.ImportResult
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.documentContainerKey
import com.bennybar.mypapers.sync.SyncState
import com.bennybar.mypapers.ui.Format
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.time.LocalDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DocumentsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onGmail: () -> Unit, vm: DocumentsViewModel = viewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val selectedTags by vm.selectedTags.collectAsStateWithLifecycle()
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val gmailFound by vm.gmailFound.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }

    // One new paper: open it. Already there: say so, with a way to it.
    fun importAll(uris: List<Uri>, name: String? = null) = scope.launch {
        val results = uris.map { vm.import(it, name) }
        val added = results.filterIsInstance<ImportResult.Added>()
        val duplicates = results.filterIsInstance<ImportResult.Duplicate>()
        if (added.size == 1 && duplicates.isEmpty()) onOpen(added.single().id)
        if (duplicates.isNotEmpty()) {
            val one = duplicates.singleOrNull()
            val r = snackbar.showSnackbar(
                if (one != null) "Already in My Papers: ${one.existing.title()}" else "${duplicates.size} were already in My Papers",
                actionLabel = if (one != null) "Open" else null,
            )
            if (r == SnackbarResult.ActionPerformed && one != null) onOpen(one.existing.id)
        }
    }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importAll(it) }

    // Google's document scanner (Play Services): edge detection, cropping, several pages → one PDF.
    val activity = LocalActivity.current
    val scanner = remember {
        GmsDocumentScanning.getClient(
            GmsDocumentScannerOptions.Builder()
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .setGalleryImportAllowed(true)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
                .build(),
        )
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pdf = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf ?: return@rememberLauncherForActivityResult
        importAll(listOf(pdf.uri), name = "Scan ${Format.dateTime(LocalDateTime.now())}.pdf")
    }
    fun startScan() {
        val a = activity ?: return
        scanner.getStartScanIntent(a)
            .addOnSuccessListener { scan.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { e -> scope.launch { snackbar.showSnackbar("Can't open the scanner: ${e.message}") } }
    }
    var addMenu by remember { mutableStateOf(false) }
    BackHandler(addMenu) { addMenu = false }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            FloatingActionButtonMenu(
                expanded = addMenu,
                button = {
                    ToggleFloatingActionButton(checked = addMenu, onCheckedChange = { addMenu = it }) {
                        val icon by remember { derivedStateOf { if (checkedProgress > 0.5f) Icons.Filled.Close else Icons.Filled.Add } }
                        Icon(
                            rememberVectorPainter(icon),
                            contentDescription = if (addMenu) "Close" else "Add",
                            modifier = Modifier.animateIcon({ checkedProgress }),
                        )
                    }
                },
            ) {
                FloatingActionButtonMenuItem(
                    onClick = { addMenu = false; startScan() },
                    icon = { Icon(Icons.Outlined.DocumentScanner, contentDescription = null) },
                    text = { Text("Scan") },
                )
                FloatingActionButtonMenuItem(
                    onClick = { addMenu = false; pick.launch(arrayOf("application/pdf", "image/*")) },
                    icon = { Icon(Icons.Outlined.UploadFile, contentDescription = null) },
                    text = { Text("Choose files") },
                )
            }
        },
        topBar = {
            Column {
                LargeTopAppBar(
                    title = { Text("My Papers") },
                    actions = {
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                    },
                    scrollBehavior = scroll,
                )
                SearchField(query, onChange = { vm.query.value = it })
                if (tags.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        items(tags, key = { it }) { tag ->
                            FilterChip(
                                selected = tag in selectedTags,
                                onClick = { vm.toggleTag(tag) },
                                label = { Text(tag) },
                                shape = CircleShape,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    vm.refresh()
                    refreshing = false
                }
            },
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    // Room for the button over the last card.
                    bottom = padding.calculateBottomPadding() + 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                val s = session
                if (s == null || s.isExpired()) {
                    item(key = "account") {
                        Notice(
                            if (s == null) "Sign in to back up and sync your papers." else "Your sign-in expired. Sign in again to sync.",
                            "Sign in",
                            onSettings,
                        )
                    }
                } else (syncState as? SyncState.Failed)?.let {
                    item(key = "syncError") { Notice("Sync failed: ${it.message}", "Settings", onSettings) }
                }
                if (gmailFound > 0) {
                    item(key = "gmail") {
                        Notice(
                            if (gmailFound == 1) "1 paper found in Gmail" else "$gmailFound papers found in Gmail",
                            "Review", onGmail, Icons.Outlined.Email,
                        )
                    }
                }
                val list = results
                if (list != null && list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (query.isBlank() && selectedTags.isEmpty()) "No papers yet. Tap + to scan or add a receipt or document."
                            else "Nothing matches.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                        )
                    }
                }
                items(list.orEmpty(), key = { it.id }) { doc -> DocumentCard(doc, onClick = { onOpen(doc.id) }) }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search vendor, tag, amount…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
        },
        singleLine = true,
        shape = CircleShape,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit, icon: ImageVector = Icons.Outlined.CloudOff) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** What a document is called in lists: its vendor, else its file name. */
fun Document.title(): String = vendor.ifBlank { fileName ?: "Untitled" }

/** "Receipt · 8 Oct 2026", skipping what isn't set. */
fun Document.subtitle(): String = listOfNotNull(docType.ifBlank { null }, Format.date(date)).joinToString(" · ")

/** The card is the container that grows into the detail page (container transform). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DocumentCard(doc: Document, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    with(LocalSharedTransitionScope.current) {
        Card(
            onClick = onClick,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(
                    rememberSharedContentState(documentContainerKey(doc.id)),
                    LocalNavAnimatedScope.current,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(shape),
                ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.title(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    doc.totalCents?.let {
                        Text(
                            Format.amount(it, doc.currency),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
                val subtitle = doc.subtitle()
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (doc.tags.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 12.dp))
                    TagRow(doc.tags)
                }
                AiStatus(doc, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** Reading with the AI / couldn't read / looks like a duplicate — nothing when all is settled. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AiStatus(doc: Document, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val (text, color) = when {
        doc.aiState == AI_PENDING -> "Reading with AI…" to cs.primary
        doc.aiState == AI_FAILED -> "Couldn't read it: ${doc.aiError.orEmpty()}" to cs.error
        doc.duplicateOf != null -> "Possible duplicate" to cs.tertiary
        else -> return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (doc.aiState == AI_PENDING) LoadingIndicator(Modifier.size(24.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun TagRow(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(tag, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}
