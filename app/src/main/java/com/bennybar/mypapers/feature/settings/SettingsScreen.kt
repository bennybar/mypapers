package com.bennybar.mypapers.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.ai.AiConfig
import com.bennybar.mypapers.ai.AiSettings
import com.bennybar.mypapers.app
import com.bennybar.mypapers.gmail.GmailConfig
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenGmail: () -> Unit) {
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
        }
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun AccountSection() {
    val session by app.server.session.collectAsStateWithLifecycle()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Account")
        val s = session
        Text(
            when {
                s == null -> "Sign in to back up your papers and sync them between your devices."
                s.isExpired() -> "Signed in as ${s.email}, but the sign-in has expired. Sign in again to sync."
                else -> "Signed in as ${s.email}"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null || s.isExpired()) {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            status = try {
                                app.onSignedIn(app.account.signIn(context))
                                null
                            } catch (_: GetCredentialCancellationException) {
                                null
                            } catch (e: Exception) {
                                e.message ?: e.javaClass.simpleName
                            }
                            busy = false
                        }
                    },
                ) { Text("Sign in with Google") }
            }
            if (s != null) OutlinedButton(enabled = !busy, onClick = { confirmSignOut = true }) { Text("Sign out") }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your papers are removed from this phone. They stay on the server and come back when you sign in again. Changes not yet synced are lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    scope.launch { app.signOut() }
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
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val config = AiConfig(apiKey.trim(), model.trim().ifBlank { AiSettings.DEFAULT_MODEL })

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("AI reading")
        Text(
            "Your own OpenAI key reads each new paper and fills in the vendor, amounts, date and tags. It stays on this phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            apiKey, { apiKey = it; status = null },
            label = { Text("OpenAI API key") },
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
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                enabled = config.isSet && !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        status = runCatching { app.openAi.check(config) }.fold({ "Key and model work" }, { it.message })
                        busy = false
                    }
                },
            ) { Text("Test") }
            Button(
                enabled = !busy && config != saved,
                onClick = {
                    app.ai.save(config)
                    status = "Saved"
                },
            ) { Text("Save") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GmailSection(onOpenGmail: () -> Unit) {
    val saved by app.gmail.settings.config.collectAsStateWithLifecycle()
    val busy by app.gmail.observeBusy().collectAsStateWithLifecycle(false)
    val error by app.gmail.observeScanError().collectAsStateWithLifecycle(null)
    val newCount by app.gmail.observeNewCount().collectAsStateWithLifecycle(0)
    var address by remember { mutableStateOf(saved.address) }
    var password by remember { mutableStateOf(saved.appPassword) }
    var months by remember { mutableIntStateOf(saved.monthsBack) }
    var daily by remember { mutableStateOf(saved.dailySweep) }
    val config = GmailConfig(address.trim(), password.replace(" ", ""), months, daily)
    val scope = rememberCoroutineScope()
    // The scan's progress shows as a notification; ask once (Android 13+).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { app.gmail.scanNow() }
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionTitle("Gmail")
        Text(
            "Finds receipts and documents in your Gmail (attachments, and receipts written in the email) for you to pick from. " +
                "Uses an app password: Google Account → Security → 2-Step Verification → App passwords.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            address, { address = it },
            label = { Text("Gmail address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            password, { password = it },
            label = { Text("App password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("First scan goes back", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(3, 6, 12, 24).forEach { m ->
                FilterChip(selected = months == m, onClick = { months = m }, label = { Text("$m months") }, shape = CircleShape)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Check daily for new papers", style = MaterialTheme.typography.bodyLarge)
                Text("A notification when there's something new", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = daily, onCheckedChange = { daily = it })
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = config.isSet && config != saved,
                onClick = {
                    scope.launch {
                        if (config.address != saved.address) app.gmail.reset()
                        app.gmail.settings.save(config)
                        app.gmail.scheduleSweep()
                    }
                },
            ) { Text("Save") }
            OutlinedButton(
                enabled = saved.isSet && config == saved && !busy,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                    if (granted) app.gmail.scanNow() else askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    onOpenGmail()
                },
            ) { Text(if (busy) "Scanning…" else if (app.gmail.settings.lastScanAt == 0L) "Scan now" else "Scan again") }
        }
        if (newCount > 0) TextButton(onClick = onOpenGmail) { Text("Review $newCount found in Gmail") }
    }
}
