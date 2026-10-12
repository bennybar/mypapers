package com.bennybar.mypapers.feature.documents

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.DOCUMENT_TYPES
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
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
import androidx.compose.runtime.LaunchedEffect
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
fun DocumentsScreen(onOpen: (String) -> Unit, onEdit: (String) -> Unit, onSettings: () -> Unit, onGmail: () -> Unit, vm: DocumentsViewModel = viewModel()) {
    val results by vm.results.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val selectedTags by vm.selectedTags.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val selectedTypes by vm.selectedTypes.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle(0)
    val ai by vm.ai.collectAsStateWithLifecycle()
    val reading by vm.readingProgress.collectAsStateWithLifecycle(null)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val vendors by vm.vendors.collectAsStateWithLifecycle()
    var filterSheet by remember { mutableStateOf(false) }
    val narrowed = query.isNotBlank() || selectedTags.isNotEmpty() || selectedTypes.isNotEmpty() || filters.activeCount > 0
    val selection by vm.selection.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    val context = LocalContext.current
    var tagDialog by remember { mutableStateOf(false) }
    var typeDialog by remember { mutableStateOf(false) }
    var selectionMenu by remember { mutableStateOf(false) }

    // Gone from the list at once; really deleted only if not undone in time.
    fun deleteWithUndo(all: Set<String>) = scope.launch {
        val ids = vm.hide(all)
        if (ids.isEmpty()) return@launch // already being deleted
        vm.clearSelection()
        val r = snackbar.showSnackbar(
            if (ids.size == 1) "Deleted" else "Deleted ${ids.size} papers",
            actionLabel = "Undo",
            duration = SnackbarDuration.Long,
        )
        if (r != SnackbarResult.ActionPerformed) vm.delete(ids)
        vm.unhide(ids)
    }

    fun share(docs: List<Document>) = scope.launch {
        if (!Sharing.share(context, docs)) snackbar.showSnackbar("The file isn't on this phone yet")
    }
    BackHandler(selecting) { vm.clearSelection() }
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val gmailFound by vm.gmailFound.collectAsStateWithLifecycle(0)
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
            if (!selecting) FloatingActionButtonMenu(
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
            if (selecting) {
                val docs = results.orEmpty().filter { it.id in selection }
                TopAppBar(
                    title = { Text("${selection.size} selected") },
                    navigationIcon = {
                        IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, contentDescription = "Done") }
                    },
                    actions = {
                        IconButton(onClick = { share(docs) }) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
                        IconButton(onClick = { deleteWithUndo(selection) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                        IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                onClick = { selectionMenu = false; vm.selectAll(results.orEmpty().map { it.id }) },
                            )
                            DropdownMenuItem(text = { Text("Add tag…") }, onClick = { selectionMenu = false; tagDialog = true })
                            DropdownMenuItem(text = { Text("Set type…") }, onClick = { selectionMenu = false; typeDialog = true })
                            DropdownMenuItem(
                                text = { Text("Read with AI") },
                                enabled = ai.isSet,
                                onClick = {
                                    selectionMenu = false
                                    scope.launch { vm.read(selection); vm.clearSelection() }
                                },
                            )
                            if (docs.size == 1) {
                                DropdownMenuItem(
                                    text = { Text("Edit") },
                                    onClick = { selectionMenu = false; vm.clearSelection(); onEdit(docs.single().id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Open with…") },
                                    enabled = docs.single().fileLocal,
                                    onClick = { selectionMenu = false; openWith(context, docs.single()) },
                                )
                            }
                        }
                    },
                )
            } else Column {
                LargeTopAppBar(
                    title = { Text("My Papers") },
                    actions = {
                        // Read everything the AI hasn't read yet (a spinner while it's at it).
                        IconButton(onClick = {
                            when {
                                reading != null -> {}
                                !ai.isSet -> onSettings()
                                unread == 0 -> scope.launch { snackbar.showSnackbar("Every paper is read") }
                                else -> vm.readUnread()
                            }
                        }) {
                            if (reading != null) LoadingIndicator(Modifier.size(28.dp))
                            else BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                                Icon(Icons.Outlined.AutoAwesome, contentDescription = "Read unread papers with AI")
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                    },
                    scrollBehavior = scroll,
                )
                SearchField(query, onChange = { vm.query.value = it }, filterCount = filters.activeCount, onFilters = { filterSheet = true })
                ActiveFilters(filters, onChange = { vm.filters.value = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                if (tags.isNotEmpty() || types.isNotEmpty()) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        // Types first (filled when on), then tags.
                        items(types, key = { "type-$it" }) { type ->
                            FilterChip(
                                selected = type in selectedTypes,
                                onClick = { vm.toggleType(type) },
                                label = { Text(type) },
                                leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null, modifier = Modifier.size(18.dp)) },
                                shape = CircleShape,
                            )
                        }
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
                reading?.let { (done, total) ->
                    item(key = "reading") {
                        Notice("Reading papers with AI · ${done + 1} of $total", null, {}, Icons.Outlined.AutoAwesome)
                    }
                }
                if (unread > 0 && reading == null) {
                    item(key = "unread") {
                        val n = if (unread == 1) "1 paper isn't" else "$unread papers aren't"
                        if (ai.isSet) Notice("$n read by the AI yet.", "Read now", { vm.readUnread() }, Icons.Outlined.AutoAwesome)
                        else Notice("$n read yet. Add your OpenAI key to fill in titles, amounts and tags.", "Add key", onSettings, Icons.Outlined.AutoAwesome)
                    }
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
                // What's shown adds up to: count and totals, when searching or filtering.
                if (narrowed && !list.isNullOrEmpty()) {
                    item(key = "summary") { ResultSummary(list, Modifier.padding(horizontal = 8.dp)) }
                }
                if (list != null && list.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (!narrowed) "No papers yet. Tap + to scan or add a receipt or document."
                            else "Nothing matches. Try fewer filters.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                        )
                    }
                }
                items(list.orEmpty(), key = { it.id }) { doc ->
                    // Swipe right to share, left to delete (not while selecting).
                    SwipeActions(enabled = !selecting, onShare = { share(listOf(doc)) }, onDelete = { deleteWithUndo(setOf(doc.id)) }) {
                        DocumentCard(
                            doc,
                            selected = doc.id in selection,
                            onClick = { if (selecting) vm.toggleSelected(doc.id) else onOpen(doc.id) },
                            onLongClick = { vm.toggleSelected(doc.id) },
                        )
                    }
                }
            }
        }
    }

    if (filterSheet) {
        FilterSheet(
            filters,
            vendors,
            onChange = { vm.filters.value = it },
            onClearAll = { vm.clearFilters() },
            onDismiss = { filterSheet = false },
        )
    }
    if (tagDialog) {
        AddTagDialog(tags, onDismiss = { tagDialog = false }) { tag ->
            tagDialog = false
            scope.launch { vm.addTag(selection, tag); vm.clearSelection() }
        }
    }
    if (typeDialog) {
        SetTypeDialog(onDismiss = { typeDialog = false }) { type ->
            typeDialog = false
            scope.launch { vm.setType(selection, type); vm.clearSelection() }
        }
    }
}

/** Swipe right (toward the end) to share, left to delete — reversed in right-to-left layouts, like the swipe itself. */
@Composable
private fun SwipeActions(enabled: Boolean, onShare: () -> Unit, onDelete: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    // The box reports a dismissal more than once: act on the first only.
    var acted by remember { mutableStateOf(false) }
    // The list restores a card's saved swipe position when it comes back (after Undo): settle it.
    LaunchedEffect(Unit) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { direction ->
            if (!acted && direction != SwipeToDismissBoxValue.Settled) {
                acted = true
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        onShare()
                        // Sharing keeps the paper: back into place, ready for the next swipe.
                        scope.launch { state.reset(); acted = false }
                    }
                    else -> onDelete()
                }
            }
        },
        backgroundContent = {
            val share = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val delete = state.dismissDirection == SwipeToDismissBoxValue.EndToStart
            val cs = MaterialTheme.colorScheme
            Row(
                Modifier
                    .fillMaxSize()
                    .background(if (delete) cs.errorContainer else cs.primaryContainer, MaterialTheme.shapes.extraLarge)
                    .padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (delete) Arrangement.End else Arrangement.Start,
            ) {
                if (share) Icon(Icons.Outlined.Share, contentDescription = "Share", tint = cs.onPrimaryContainer)
                if (delete) Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = cs.onErrorContainer)
            }
        },
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagDialog(existing: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val suggestions = existing.filter { text.isBlank() || it.contains(text.trim(), ignoreCase = true) }.take(12)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { t -> SuggestionChip(onClick = { onAdd(t) }, label = { Text(t) }, shape = CircleShape) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onAdd(text) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SetTypeDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set type") },
        text = {
            Column {
                DOCUMENT_TYPES.forEach { type ->
                    Text(
                        type,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Hands the paper's file to another app to view. */
fun openWith(context: Context, doc: Document) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", app.repository.files.file(doc.id))
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null),
    )
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, filterCount: Int, onFilters: () -> Unit) {
    TextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search vendor, tag, amount…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            Row {
                if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                IconButton(onClick = onFilters) {
                    BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                        Icon(Icons.Outlined.Tune, contentDescription = "Filter and sort")
                    }
                }
            }
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
private fun Notice(text: String, action: String?, onAction: () -> Unit, icon: ImageVector = Icons.Outlined.CloudOff) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            if (action != null) TextButton(onClick = onAction) { Text(action) } else Spacer(Modifier.padding(vertical = 24.dp))
        }
    }
}

/** What a document is called in lists: its title, else its vendor, else its file name. */
fun Document.title(): String = title.ifBlank { vendor.ifBlank { fileName ?: "Untitled" } }

/** "Shufersal · Receipt · 8 Oct 2026", skipping what isn't set (the vendor when it's already the title). */
fun Document.subtitle(): String =
    listOfNotNull(vendor.takeIf { it.isNotBlank() && title.isNotBlank() }, docType.ifBlank { null }, Format.date(date)).joinToString(" · ")

/** The card is the container that grows into the detail page (container transform). */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(doc: Document, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    val cs = MaterialTheme.colorScheme
    with(LocalSharedTransitionScope.current) {
        Card(
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = if (selected) cs.secondaryContainer else cs.surfaceContainerHigh),
            border = if (selected) BorderStroke(2.dp, cs.primary) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
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
