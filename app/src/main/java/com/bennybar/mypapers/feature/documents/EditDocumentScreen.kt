package com.bennybar.mypapers.feature.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bennybar.mypapers.app
import com.bennybar.mypapers.data.DOCUMENT_TYPES
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.ui.Format
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDocumentScreen(id: String, onDone: () -> Unit) {
    val docFlow = remember(id) { app.repository.observe(id) }
    val doc by docFlow.collectAsStateWithLifecycle(null)
    val allTags by remember {
        app.repository.observeAll().map { docs -> docs.flatMap { it.tags }.distinct().sorted() }
    }.collectAsStateWithLifecycle(emptyList())
    // Fields start from the first value read; later syncs don't overwrite what's being typed.
    val first = doc
    if (first == null) {
        // Still reading the paper (a moment): just the bar.
        TopAppBar(title = { Text("Edit") })
        return
    }
    EditForm(remember(id) { first }, allTags, onDone)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(doc: Document, allTags: List<String>, onDone: () -> Unit) {
    var title by remember { mutableStateOf(doc.title) }
    var vendor by remember { mutableStateOf(doc.vendor) }
    var docType by remember { mutableStateOf(doc.docType) }
    var date by remember { mutableStateOf(doc.date) }
    var total by remember { mutableStateOf(Format.amountInput(doc.totalCents)) }
    var currency by remember {
        mutableStateOf(doc.currency ?: Format.defaultCurrency())
    }
    var vat by remember { mutableStateOf(Format.amountInput(doc.vatCents)) }
    var tags by remember { mutableStateOf(doc.tags) }
    var tagInput by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(doc.notes) }
    var pickDate by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalBad = total.isNotBlank() && Format.parseAmount(total) == null
    val vatBad = vat.isNotBlank() && Format.parseAmount(vat) == null

    fun addTag(raw: String) {
        val t = raw.trim().trimEnd(',').trim()
        if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags = tags + t
        tagInput = ""
    }

    val changed = title != doc.title || vendor != doc.vendor || docType != doc.docType || date != doc.date ||
        total != Format.amountInput(doc.totalCents) || currency != (doc.currency ?: Format.defaultCurrency()) ||
        vat != Format.amountInput(doc.vatCents) || tags != doc.tags || notes != doc.notes || tagInput.isNotBlank()

    fun save() {
        if (saving) return // a second tap would leave the screen twice
        saving = true
        if (tagInput.isNotBlank()) addTag(tagInput)
        scope.launch {
            // The latest copy, and only what was changed here: the AI may have filled the rest meanwhile.
            val c = app.repository.observe(doc.id).first() ?: return@launch onDone()
            app.repository.update(
                c.copy(
                    title = if (title != doc.title) title.trim() else c.title,
                    vendor = if (vendor != doc.vendor) vendor.trim() else c.vendor,
                    docType = if (docType != doc.docType) docType else c.docType,
                    date = if (date != doc.date) date else c.date,
                    totalCents = if (total != Format.amountInput(doc.totalCents)) Format.parseAmount(total) else c.totalCents,
                    currency = if (currency != (doc.currency ?: Format.defaultCurrency())) currency.trim().ifBlank { null } else c.currency ?: currency.trim().ifBlank { null },
                    vatCents = if (vat != Format.amountInput(doc.vatCents)) Format.parseAmount(vat) else c.vatCents,
                    tags = if (tags != doc.tags) tags else c.tags,
                    notes = if (notes != doc.notes) notes.trim() else c.notes,
                ),
            )
            onDone()
        }
    }

    fun leave() {
        if (changed) confirmDiscard = true else onDone()
    }
    BackHandler { leave() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    // Always in reach, whatever the font size or keyboard.
                    Button(
                        enabled = !totalBad && !vatBad && !saving,
                        onClick = ::save,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Save") }
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                title, { title = it },
                label = { Text("Title") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                vendor, { vendor = it },
                label = { Text("Vendor") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DOCUMENT_TYPES.forEach { type ->
                    FilterChip(
                        selected = docType == type,
                        onClick = { docType = if (docType == type) "" else type },
                        label = { Text(type) },
                        shape = CircleShape,
                    )
                }
            }
            // The whole field opens the picker (a read-only field takes the tap otherwise).
            Box {
                OutlinedTextField(
                    Format.date(date).orEmpty(), {},
                    label = { Text("Date") },
                    readOnly = true,
                    trailingIcon = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable(onClickLabel = "Pick date") { pickDate = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    total, { total = it },
                    label = { Text("Total") },
                    isError = totalBad,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(2f),
                )
                OutlinedTextField(
                    currency, { currency = it.uppercase().take(3) },
                    label = { Text("Currency") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                vat, { vat = it },
                label = { Text("VAT") },
                isError = vatBad,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tags.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = { tags = tags - tag },
                                label = { Text(tag) },
                                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $tag") },
                                shape = CircleShape,
                            )
                        }
                    }
                }
                OutlinedTextField(
                    tagInput,
                    { if (it.endsWith(",")) addTag(it) else tagInput = it },
                    label = { Text("Add tag") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = KeyboardCapitalization.Words),
                    keyboardActions = KeyboardActions(onDone = { addTag(tagInput) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Existing tags first, so the same thing isn't tagged two ways.
                val suggestions = allTags.filter { t ->
                    tags.none { it.equals(t, ignoreCase = true) } && (tagInput.isBlank() || t.contains(tagInput.trim(), ignoreCase = true))
                }.take(12)
                if (suggestions.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { t -> SuggestionChip(onClick = { addTag(t) }, label = { Text(t) }, shape = CircleShape) }
                    }
                }
            }
            OutlinedTextField(
                notes, { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onDone() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    date = state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}
