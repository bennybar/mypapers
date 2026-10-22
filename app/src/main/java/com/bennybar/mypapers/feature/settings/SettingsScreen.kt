package com.bennybar.mypapers.feature.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.ai.AiSettings
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.FormatSettings
import com.bennybar.mypapers.feature.documents.Sharing
import com.bennybar.mypapers.gmail.GmailAuth
import com.bennybar.mypapers.sync.DriveAuth
import com.bennybar.mypapers.sync.DriveAuthException
import com.bennybar.mypapers.sync.SyncState
import com.bennybar.mypapers.ui.Format
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenGmail: () -> Unit, onOpenTrash: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            AccountSection()
            AiSection()
            GmailSection(onOpenGmail)
            PapersSection(onOpenTrash)
            FormatsSection()
        }
    }
}

/** The trash, and a copy of everything. */
@Composable
private fun PapersSection(onOpenTrash: () -> Unit) {
    val all by remember { app.repository.observeAll() }.collectAsStateWithLifecycle(emptyList())
    val trash by remember { app.repository.observeTrash() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Your papers")
        Text(
            "Export: a spreadsheet of every paper (date, amounts, VAT, tags, details) and the files, as one ZIP for your accountant, a tax refund or a copy of your own.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = all.isNotEmpty(), onClick = { scope.launch { Sharing.export(context, all) } }) { Text("Export all (${all.size})") }
            OutlinedButton(onClick = onOpenTrash) { Text(if (trash.isEmpty()) "Trash" else "Trash (${trash.size})") }
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun AccountSection() {
    val email by app.drive.email.collectAsStateWithLifecycle()
    val oldServer by app.server.session.collectAsStateWithLifecycle()
    val syncState by app.sync.state.collectAsStateWithLifecycle()
    val lastSync by app.drive.lastSync.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    // Signed in, waiting on Google's Drive consent screen.
    var pending by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun finish(address: String) = scope.launch {
        busy = true
        status = try {
            app.connectDrive(address)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        busy = false
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val address = pending
        pending = null
        if (r.resultCode == Activity.RESULT_OK && DriveAuth.tokenFrom(context, r.data) != null && address != null) finish(address)
        else status = "Google Drive access wasn't allowed, so your papers can't be backed up."
    }
    fun connect(address: String) = scope.launch {
        status = try {
            when (val r = DriveAuth.authorize(context, address)) {
                is DriveAuth.Result.Token -> finish(address)
                is DriveAuth.Result.NeedsConsent -> {
                    pending = address
                    consent.launch(IntentSenderRequest.Builder(r.intent).build())
                }
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }
    val needsAccess = (syncState as? SyncState.Failed)?.message == DriveAuthException().message

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Account")
        val e = email
        Text(
            when {
                e != null -> "Backed up to the Google Drive of $e, in a hidden folder only My Papers can see. Synced between your devices."
                oldServer != null -> "Your papers now back up to your own Google Drive. Sign in to move them there; everything on this phone and the old server comes along."
                else -> "Sign in to back up your papers to your own Google Drive and sync them between your devices."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (e != null) {
            val s = syncState
            Text(
                when {
                    s is SyncState.Uploading -> "Backing up · ${s.done + 1} of ${s.total}"
                    s is SyncState.Syncing -> "Syncing…"
                    s is SyncState.Failed -> "Sync failed: ${s.message}"
                    lastSync > 0 -> "Everything's backed up · last sync ${ago(lastSync)}"
                    else -> "Not synced yet"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (s is SyncState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (e == null) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            status = try {
                                connect(app.account.signIn(context))
                                null
                            } catch (_: GetCredentialCancellationException) {
                                null
                            } catch (_: androidx.credentials.exceptions.NoCredentialException) {
                                "No Google account on this phone. Add one in the phone's Settings → Accounts."
                            } catch (ex: Exception) {
                                ex.message ?: ex.javaClass.simpleName
                            }
                        }
                    },
                ) { Text(if (busy) "Moving your papers…" else "Sign in with Google") }
            } else {
                if (needsAccess) Button(enabled = !busy, onClick = { connect(e) }) { Text("Allow Drive access") }
                OutlinedButton(enabled = !busy, onClick = { confirmSignOut = true }) { Text("Sign out") }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your papers are removed from this phone. They stay in your Google Drive and come back when you sign in again. Changes not yet synced are lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    app.signOut()
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AiSection() {
    val saved by app.ai.config.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var server by remember { mutableStateOf(saved.baseUrl) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val config = saved.copy(apiKey = apiKey.trim(), model = model.trim().ifBlank { AiSettings.DEFAULT_MODEL }, baseUrl = server.trim())
    val serverBad = server.isNotBlank() && !Regex("(?i)^https://\\S+$").matches(server.trim())
    val unread by remember { app.repository.observeUnread() }.collectAsStateWithLifecycle(emptyList())
    val readable by remember { app.repository.observeReadableCount() }.collectAsStateWithLifecycle(0)
    var confirmReread by remember { mutableStateOf(false) }
    // Off by default: what was asked of the AI is kept.
    var clearAsked by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("AI reading")
        Text(
            "Your own OpenAI key (or your own proxy server) reads your papers: a clear title, the vendor, amounts, date, type and tags, for searching and filtering. The key stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            apiKey, { apiKey = it; status = null },
            label = { Text(if (server.isBlank()) "OpenAI API key" else "API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            model, { model = it; status = null },
            label = { Text("Model") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // An OpenAI-compatible proxy (LiteLLM…) instead of OpenAI: its address, its key, its model name.
        OutlinedTextField(
            server, { server = it; status = null },
            label = { Text("Proxy server (optional)") },
            placeholder = { Text("https://litellm.example.com/v1") },
            supportingText = { Text(if (serverBad) "Starts with https:// (your key goes with each request, so it must be encrypted)" else "Leave empty for OpenAI. For LiteLLM or another OpenAI-compatible proxy: its address. It needs the Responses API.") },
            isError = serverBad,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = config.isSet && !busy && !serverBad,
                onClick = {
                    scope.launch {
                        busy = true
                        status = runCatching { app.openAi.check(config) }.fold({ "Key and model work" }, { it.message })
                        busy = false
                    }
                },
            ) { Text("Test") }
            Button(
                enabled = !busy && config != saved && !serverBad,
                onClick = {
                    app.ai.save(config)
                    status = "Saved"
                },
            ) { Text("Save") }
        }
        if (saved.isSet) {
            SettingSwitch(
                "Read new papers automatically",
                "In the background as each one comes in. Off: only when you ask.",
                saved.autoRead,
            ) { app.ai.save(saved.copy(autoRead = it)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (unread.isNotEmpty()) {
                    OutlinedButton(onClick = { app.repository.readUnread() }) {
                        Text(if (unread.size == 1) "Read 1 unread" else "Read ${unread.size} unread")
                    }
                }
                if (readable > 0) OutlinedButton(onClick = { confirmReread = true }) { Text("Re-read all ($readable)") }
            }
        }
    }

    if (confirmReread) {
        AlertDialog(
            onDismissRequest = { confirmReread = false },
            title = { Text("Re-read all $readable papers?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("The AI reads each paper again and refreshes what it read: title, vendor, amounts, date, type, tags and details. What you corrected by hand stays. One AI request per paper, in the background.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Also remove Ask AI answers and extracted text",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(checked = clearAsked, onCheckedChange = { clearAsked = it })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmReread = false
                    val clear = clearAsked
                    app.scope.launch { app.repository.rereadAll(clearAsked = clear) }
                }) { Text("Re-read") }
            },
            dismissButton = { TextButton(onClick = { confirmReread = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SettingSwitch(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GmailSection(onOpenGmail: () -> Unit) {
    val saved by app.gmail.settings.config.collectAsStateWithLifecycle()
    val driveEmail by app.drive.email.collectAsStateWithLifecycle()
    val oldServer by app.server.session.collectAsStateWithLifecycle()
    val busy by app.gmail.observeBusy().collectAsStateWithLifecycle(false)
    val error by app.gmail.observeScanError().collectAsStateWithLifecycle(null)
    val newCount by app.gmail.observeNewCount().collectAsStateWithLifecycle(0)
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val email = driveEmail ?: oldServer?.email

    fun connected(address: String) {
        app.gmail.settings.save(saved.copy(email = address))
        app.gmail.scheduleSweep()
        status = null
    }
    // Google's consent screen for read-only Gmail access.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && GmailAuth.tokenFrom(context, r.data) != null && email != null) connected(email)
        else status = "Gmail wasn't connected"
    }
    // The scan's progress shows as a notification; ask once (Android 13+). The scan doesn't wait for
    // the answer: this screen is left right away, which would drop the callback.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Gmail")
        Text(
            if (saved.isConnected) "Connected to ${saved.email} (read-only). Finds receipts and documents — attachments, and receipts written in the email — for you to pick from."
            else "Finds receipts and documents in your Gmail — attachments, and receipts written in the email — for you to pick from. Read-only: My Papers can't change, send or delete anything.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (saved.isConnected) {
            Text("First scan goes back", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 6, 12, 24).forEach { m ->
                    FilterChip(
                        selected = saved.monthsBack == m,
                        onClick = { app.gmail.settings.save(saved.copy(monthsBack = m)) },
                        label = { Text("$m months") },
                        shape = CircleShape,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Check daily for new papers", style = MaterialTheme.typography.bodyLarge)
                    Text("A notification when there's something new", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = saved.dailySweep, onCheckedChange = {
                    app.gmail.settings.save(saved.copy(dailySweep = it))
                    app.gmail.scheduleSweep()
                })
            }
            // Read again when a scan finishes (that's when it's saved).
            val lastScan = remember(busy) { app.gmail.settings.lastScanAt }
            if (lastScan > 0) {
                val ms = app.gmail.settings.lastScanTook
                val took = ms / 1000
                Text(
                    "Last sync ${ago(lastScan)}" + when {
                        ms == 0L -> "" // scanned before this was kept
                        took < 60 -> " · took ${took}s"
                        else -> " · took ${took / 60} min ${took % 60}s"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        (status ?: error)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!saved.isConnected || error?.contains("Connect Gmail again") == true) {
                Button(
                    enabled = email != null,
                    onClick = {
                        scope.launch {
                            status = try {
                                when (val r = GmailAuth.authorize(context, email!!)) {
                                    is GmailAuth.Result.Token -> { connected(email); null }
                                    is GmailAuth.Result.NeedsConsent -> { consent.launch(IntentSenderRequest.Builder(r.intent).build()); null }
                                }
                            } catch (e: Exception) {
                                e.message ?: e.javaClass.simpleName
                            }
                        }
                    },
                ) { Text(if (saved.isConnected) "Connect again" else "Connect Gmail") }
            }
            if (saved.isConnected) {
                Button(
                    enabled = !busy,
                    onClick = {
                        app.gmail.scanNow()
                        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        if (!granted) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        onOpenGmail()
                    },
                ) { Text(if (busy) "Scanning…" else if (app.gmail.settings.lastScanAt == 0L) "Scan now" else "Scan again") }
                OutlinedButton(onClick = {
                    scope.launch { app.gmail.disconnect() }
                }) { Text("Disconnect") }
            }
        }
        if (email == null) Text("Sign in first (above) — Gmail uses the same Google account.", style = MaterialTheme.typography.bodySmall)
        if (newCount > 0) TextButton(onClick = onOpenGmail) { Text("Review $newCount found in Gmail") }
    }
}

/** "just now", "5 minutes ago", "Oct 12". */
private fun ago(time: Long): CharSequence =
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

/** Which region's date and number formats to show (and to read typed amounts and ambiguous dates with). */
@Composable
private fun FormatsSection() {
    val region by app.formats.region.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val phone = LocalConfiguration.current.locales[0]
    fun label(tag: String) =
        if (tag.isEmpty()) "Phone setting (${phone.getDisplayName(phone)})"
        else Locale.forLanguageTag(tag).let { it.getDisplayName(it) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Dates and amounts")
        Text(
            "How dates and amounts are shown, the currency new papers start with, and how a date like 03/04 is read when the paper itself gives no clue.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedTextField(
                value = label(region),
                onValueChange = {},
                readOnly = true,
                label = { Text("Region") },
                trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Over the field, so a tap anywhere on it opens the menu (a read-only field still takes focus).
            Box(Modifier.matchParentSize().clickable { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                (listOf("") + FormatSettings.REGIONS).forEach { tag ->
                    DropdownMenuItem(
                        text = { Text(label(tag)) },
                        onClick = { app.formats.save(tag); open = false },
                    )
                }
            }
        }
        // What it looks like: today and an amount in the region's currency.
        Text(
            "${Format.date(java.time.LocalDate.now().toString())} · ${Format.amount(124950, Format.defaultCurrency().ifEmpty { null })}",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
