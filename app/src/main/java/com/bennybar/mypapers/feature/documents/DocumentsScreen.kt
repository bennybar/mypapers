package com.bennybar.mypapers.feature.documents

import androidx.compose.material.icons.outlined.Schedule
import com.bennybar.mypapers.search.SortBy
import com.bennybar.mypapers.search.Filters
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.outlined.Sort
import java.time.LocalDate
import com.bennybar.mypapers.ai.ExtractionPrompt
import com.bennybar.mypapers.data.Expiry
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import android.app.Activity
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
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
import com.bennybar.mypapers.data.Images
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.CloudDone
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.toShape
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
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
import com.bennybar.mypapers.ui.layoutStart
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
    val asked by vm.asked.collectAsStateWithLifecycle()
    // Asking runs in the background with notifications; asked for once (Android 13+), without waiting.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val view by vm.view.collectAsStateWithLifecycle()
    // Put away while a paper from the answer is open; a notice brings it back.
    var askHidden by rememberSaveable { mutableStateOf(false) }
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
        // Undo right away, not queued behind an "Added" message.
        snackbar.currentSnackbarData?.dismiss()
        var undone = false
        try {
            undone = snackbar.showSnackbar(
                if (ids.size == 1) "Moved to trash" else "${ids.size} papers moved to trash",
                actionLabel = "Undo",
                duration = SnackbarDuration.Long,
            ) == SnackbarResult.ActionPerformed
        } finally {
            // Also when the snackbar is cut short (another screen opened, the phone rotated): delete anyway.
            vm.finishDelete(ids, undone)
        }
    }

    fun share(docs: List<Document>) = scope.launch {
        if (!Sharing.share(context, docs)) snackbar.showSnackbar("The file isn't on this phone yet")
    }
    BackHandler(selecting) { vm.clearSelection() }
    val syncState by vm.syncState.collectAsStateWithLifecycle()
    val email by vm.email.collectAsStateWithLifecycle()
    val oldServer by vm.oldServer.collectAsStateWithLifecycle()
    val gmailFound by vm.gmailFound.collectAsStateWithLifecycle(0)
    var refreshing by remember { mutableStateOf(false) }

    // Stays on the list (the new paper is on top; the AI reads it in the background if that's on),
    // with a way to open it. Already there: say so, with a way to it.
    fun importAll(uris: List<Uri>, name: String? = null) = scope.launch {
        // One file that can't be read (not downloaded from the cloud, no permission) doesn't stop the rest.
        val results = uris.map { uri ->
            try {
                vm.import(uri, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        val added = results.filterIsInstance<ImportResult.Added>()
        val duplicates = results.filterIsInstance<ImportResult.Duplicate>()
        val failed = results.count { it == null }
        if (failed > 0) {
            snackbar.showSnackbar(
                when {
                    uris.size == 1 -> "Couldn't read this file"
                    added.isEmpty() && duplicates.isEmpty() -> "Couldn't read these files"
                    else -> "Added ${added.size}, $failed couldn't be read" + if (duplicates.isNotEmpty()) ", ${duplicates.size} already here" else ""
                },
            )
            return@launch
        }
        if (added.isNotEmpty() && duplicates.isEmpty()) {
            val one = added.singleOrNull()
            val r = snackbar.showSnackbar(
                if (one != null) "Added" else "Added ${added.size} papers",
                actionLabel = if (one != null) "Open" else null,
            )
            if (r == SnackbarResult.ActionPerformed && one != null) onOpen(one.id)
        }
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
                // Page photos, made into an upright PDF here (the scanner's own PDF showed some scans rotated).
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .build(),
        )
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val pages = GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pages?.map { it.imageUri }
        if (pages.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val pdf = File(context.cacheDir, "scan-${System.currentTimeMillis()}.pdf")
            val made = withContext(Dispatchers.IO) { runCatching { Images.scanToPdf(context, pages, pdf) } }
            if (made.isFailure) {
                snackbar.showSnackbar("Couldn't save the scan: ${made.exceptionOrNull()?.message}")
                return@launch
            }
            importAll(listOf(Uri.fromFile(pdf)), name = "Scan ${Format.dateTime(LocalDateTime.now())}.pdf").join()
            pdf.delete()
        }
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
                                text = { Text("Export (spreadsheet + files)") },
                                onClick = {
                                    selectionMenu = false
                                    val docs = results.orEmpty().filter { it.id in selection }
                                    scope.launch { Sharing.export(context, docs) }
                                },
                            )
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
                        // Cards → compact list → thumbnails → timeline → cards.
                        IconButton(onClick = { vm.nextView() }) {
                            Icon(view.icon, contentDescription = "${view.label} view. Switch to ${view.next().label}")
                        }
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
                    // One colour with the search and chips below it (no seam when scrolled).
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surface),
                )
                SearchField(query, onChange = { vm.query.value = it }, filterCount = filters.activeCount, onFilters = { filterSheet = true })
                ActiveFilters(filters, onChange = { vm.filters.value = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
                if (!results.isNullOrEmpty() || narrowed) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        // The order first (remembered), then types (filled when on), then tags.
                        item(key = "sort") {
                            SortChip(filters.sort, searching = query.isNotBlank()) { vm.filters.value = filters.copy(sort = it) }
                        }
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
                // Lines close together in the dense views.
                verticalArrangement = Arrangement.spacedBy(if (view == ListView.COMPACT || view == ListView.TIMELINE) 4.dp else 12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                // One notice at a time, the most important; none while searching or selecting.
                val failed = syncState as? SyncState.Failed
                val uploading = syncState as? SyncState.Uploading
                val progress = reading
                if (!narrowed && !selecting) when {
                    email == null -> item(key = "account") {
                        Notice(
                            if (oldServer != null) "Your papers now back up to your own Google Drive. Sign in to move them."
                            else "Sign in to back up your papers to your Google Drive.",
                            if (oldServer != null) "Move" else "Set up",
                            onSettings,
                        )
                    }
                    failed != null -> item(key = "syncError") { Notice("Sync failed: ${failed.message}", "Settings", onSettings) }
                    uploading != null && uploading.total > 1 -> item(key = "uploading") {
                        Notice("Backing up to Google Drive · ${uploading.done + 1} of ${uploading.total}", null, {}, Icons.Outlined.CloudUpload)
                    }
                    progress != null -> item(key = "reading") {
                        Notice("Reading papers with AI · ${progress.first + 1} of ${progress.second}", null, {}, Icons.Outlined.AutoAwesome)
                    }
                    gmailFound > 0 -> item(key = "gmail") {
                        Notice(
                            if (gmailFound == 1) "1 paper found in Gmail" else "$gmailFound papers found in Gmail",
                            "Review", onGmail, Icons.Outlined.Email,
                        )
                    }
                    unread > 0 -> item(key = "unread") {
                        val n = if (unread == 1) "1 paper isn't" else "$unread papers aren't"
                        if (ai.isSet) Notice("$n read by the AI yet.", "Read now", { vm.readUnread() }, Icons.Outlined.AutoAwesome)
                        else Notice("$n read yet. Add your OpenAI key to fill in titles, amounts and tags.", "Add key", onSettings, Icons.Outlined.AutoAwesome)
                    }
                }
                // What's typed in search, as a question for the AI about all the papers.
                val shelved = asked?.first?.takeIf { askHidden }
                if (shelved != null && !selecting) item(key = "answer") {
                    Notice("AI answer: “$shelved”", "Show", { askHidden = false }, Icons.Outlined.AutoAwesome)
                }
                if (query.isNotBlank() && query.trim() != shelved && ai.isSet && !selecting) item(key = "askAll") {
                    Notice("Ask AI: “${query.trim()}”", "Ask", {
                        askHidden = false
                        vm.askAll(query.trim())
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }, Icons.Outlined.AutoAwesome)
                }
                val list = results
                // What's shown adds up to: count and totals, when searching or filtering.
                if (narrowed && !list.isNullOrEmpty()) {
                    item(key = "summary") { ResultSummary(list, Modifier.padding(horizontal = 8.dp)) }
                }
                if (list != null && list.isEmpty()) {
                    item(key = "empty") {
                        if (!narrowed) EmptyLibrary(onScan = ::startScan, onPick = { pick.launch(arrayOf("application/pdf", "image/*")) })
                        else Text(
                            "Nothing matches. Try fewer filters.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 16.dp),
                        )
                    }
                }
                val papers = list.orEmpty()
                fun open(doc: Document) = if (selecting) vm.toggleSelected(doc.id) else onOpen(doc.id)
                when (view) {
                    ListView.CARDS -> items(papers, key = { it.id }) { doc ->
                        // Swipe right to share, left to delete (not while selecting).
                        SwipeActions(enabled = !selecting, onShare = { share(listOf(doc)) }, onDelete = { deleteWithUndo(setOf(doc.id)) }) {
                            DocumentCard(
                                doc,
                                signedIn = email != null,
                                selected = doc.id in selection,
                                onClick = { open(doc) },
                                onLongClick = { vm.toggleSelected(doc.id) },
                            )
                        }
                    }
                    ListView.COMPACT -> items(papers, key = { it.id }) { doc ->
                        SwipeActions(enabled = !selecting, onShare = { share(listOf(doc)) }, onDelete = { deleteWithUndo(setOf(doc.id)) }) {
                            CompactRow(doc, email != null, doc.id in selection, onClick = { open(doc) }, onLongClick = { vm.toggleSelected(doc.id) })
                        }
                    }
                    ListView.GRID -> items(papers.chunked(2), key = { row -> "row-" + row.first().id }) { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { doc ->
                                GridTile(doc, email != null, doc.id in selection, onClick = { open(doc) }, onLongClick = { vm.toggleSelected(doc.id) }, modifier = Modifier.weight(1f))
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    // By the date on the paper, whatever the sort (oldest month first if that's the sort).
                    ListView.TIMELINE -> monthGroups(papers, oldestFirst = filters.sort == SortBy.OLDEST).forEach { (month, docs) ->
                        item(key = "month-$month") { MonthHeader(month, docs) }
                        items(docs, key = { it.id }) { doc ->
                            SwipeActions(enabled = !selecting, onShare = { share(listOf(doc)) }, onDelete = { deleteWithUndo(setOf(doc.id)) }) {
                                CompactRow(
                                    doc, email != null, doc.id in selection,
                                    onClick = { open(doc) }, onLongClick = { vm.toggleSelected(doc.id) },
                                    day = Filters.paperDay(doc).dayOfMonth,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    asked?.takeUnless { askHidden }?.let { (q, result) ->
        AskAllSheet(q, result, onOpen = { askHidden = true; onOpen(it) }, onDismiss = { vm.closeAnswer() })
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
    val keyboard = LocalSoftwareKeyboardController.current
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
        // Results show as you type; Search just puts the keyboard away.
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
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

/** What a document is called in lists: its title, else its vendor, else its file name (without ".pdf"). */
fun Document.title(): String = title.ifBlank { vendor.ifBlank { fileName?.substringBeforeLast('.')?.ifBlank { null } ?: "Untitled" } }

/**
 * "Shufersal · Receipt · 8 Oct 2026", skipping what isn't set (the vendor when it's already the title).
 * Nothing known yet: "Added 12 Oct 2026 · PDF".
 */
fun Document.subtitle(): String =
    listOfNotNull(vendor.takeIf { it.isNotBlank() && title.isNotBlank() }, docType.ifBlank { null }, Format.date(date)).joinToString(" · ")
        .ifEmpty {
            val kind = when {
                mimeType == "application/pdf" -> "PDF"
                mimeType?.startsWith("image/") == true -> "Photo"
                else -> null
            }
            listOfNotNull("Added ${Format.day(createdAt)}", kind).joinToString(" · ")
        }

/** The card is the container that grows into the detail page (container transform). */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalFoundationApi::class)
@Composable
private fun DocumentCard(doc: Document, signedIn: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    val cs = MaterialTheme.colorScheme
    with(LocalSharedTransitionScope.current) {
        Card(
            shape = shape,
            // Not secondaryContainer: that's the notices' and the tags' colour.
            colors = CardDefaults.cardColors(containerColor = if (selected) cs.tertiaryContainer else cs.surfaceContainerHigh),
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
                    if (selected) {
                        Icon(
                            Icons.Filled.CheckCircle, contentDescription = "Selected", tint = cs.tertiary,
                            modifier = Modifier.padding(end = 12.dp).size(24.dp),
                        )
                    }
                    Text(
                        doc.title(),
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = layoutStart(),
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
                    // Backed up to Google Drive yet (like Google Photos).
                    if (signedIn) {
                        Icon(
                            if (doc.dirty) Icons.Outlined.CloudUpload else Icons.Outlined.CloudDone,
                            contentDescription = if (doc.dirty) "Not backed up yet" else "Backed up",
                            tint = if (doc.dirty) cs.outline else cs.primary,
                            modifier = Modifier.padding(start = 12.dp).size(18.dp),
                        )
                    }
                }
                val subtitle = doc.subtitle()
                if (subtitle.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 4.dp))
                    Text(
                        subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = layoutStart(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                ExpiryLabel(doc, Modifier.padding(top = 8.dp))
                if (doc.tags.isNotEmpty()) {
                    Spacer(Modifier.padding(top = 12.dp))
                    TagRow(doc.tags)
                }
                AiStatus(doc, Modifier.padding(top = 12.dp))
            }
        }
    }
}

/** The list's order, one tap away: recently added (best match when searching), date on paper, amount, vendor. */
@Composable
private fun SortChip(sort: SortBy?, searching: Boolean, onChange: (SortBy?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val default = if (searching) "Best match" else "Recently added"
    Box {
        FilterChip(
            selected = false,
            onClick = { open = true },
            label = { Text(sort?.label() ?: default) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort", modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            shape = CircleShape,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf<SortBy?>(null) + SortBy.entries).forEach { s ->
                DropdownMenuItem(
                    text = { Text(s?.label() ?: default) },
                    leadingIcon = { if (s == sort) Icon(Icons.Filled.Check, contentDescription = "Selected") },
                    onClick = { open = false; onChange(s) },
                )
            }
        }
    }
}

/** No papers yet: the first one is one tap away. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyLibrary(onScan: () -> Unit, onPick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
        }
        Text("Your papers live here", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp))
        Text(
            "Scan a receipt, a bill or any document, or add PDFs and photos you already have.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onScan, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Outlined.DocumentScanner, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Scan a paper", Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
        OutlinedButton(onClick = onPick, modifier = Modifier.padding(top = 8.dp)) {
            Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Text("Choose files", Modifier.padding(start = ButtonDefaults.IconSpacing))
        }
    }
}

/** "Expires in 12 days" from the paper's key details, when that's close (or just past); nothing otherwise. */
@Composable
fun ExpiryLabel(doc: Document, modifier: Modifier = Modifier) {
    val expires = Expiry.date(doc, ExtractionPrompt.dayFirst(Format.locale)) ?: return
    val left = Expiry.daysLeft(expires, LocalDate.now())
    if (left < -30 || left > Expiry.SOON_DAYS) return
    val text = when {
        left < -1 -> "Expired ${-left} days ago"
        left == -1L -> "Expired yesterday"
        left == 0L -> "Expires today"
        left == 1L -> "Expires tomorrow"
        else -> "Expires in $left days"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val color = if (left <= 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
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
