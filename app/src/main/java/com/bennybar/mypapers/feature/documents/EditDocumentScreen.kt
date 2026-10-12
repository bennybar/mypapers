package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDocumentScreen(id: String, onDone: () -> Unit) {
    val docFlow = remember(id) { app.repository.observe(id) }
    val doc by docFlow.collectAsStateWithLifecycle(null)
    val allTags by remember {
        app.repository.observeAll().map { docs -> docs.flatMap { it.tags }.distinct().sorted() }
    }.collectAsStateWithLifecycle(emptyList())
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        // Fields start from the first value read; later syncs don't overwrite what's being typed.
        doc?.let { EditForm(remember(id) { it }, allTags, Modifier.padding(padding), onDone) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditForm(doc: Document, allTags: List<String>, modifier: Modifier, onDone: () -> Unit) {
    var vendor by remember { mutableStateOf(doc.vendor) }
    var docType by remember { mutableStateOf(doc.docType) }
    var date by remember { mutableStateOf(doc.date) }
    var total by remember { mutableStateOf(Format.amountInput(doc.totalCents)) }
    var currency by remember {
        mutableStateOf(doc.currency ?: runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault(""))
    }
    var vat by remember { mutableStateOf(Format.amountInput(doc.vatCents)) }
    var tags by remember { mutableStateOf(doc.tags) }
    var tagInput by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(doc.notes) }
    var pickDate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val totalBad = total.isNotBlank() && Format.parseAmount(total) == null
    val vatBad = vat.isNotBlank() && Format.parseAmount(vat) == null

    fun addTag(raw: String) {
        val t = raw.trim().trimEnd(',').trim()
        if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags = tags + t
        tagInput = ""
    }

    Column(
        modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
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
        OutlinedTextField(
            Format.date(date).orEmpty(), {},
            label = { Text("Date") },
            readOnly = true,
            trailingIcon = { IconButton(onClick = { pickDate = true }) { Icon(Icons.Outlined.CalendarMonth, contentDescription = "Pick date") } },
            modifier = Modifier.fillMaxWidth(),
        )
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
        Button(
            enabled = !totalBad && !vatBad,
            onClick = {
                if (tagInput.isNotBlank()) addTag(tagInput)
                scope.launch {
                    // The latest copy, so a file or sync change since opening isn't lost.
                    val current = app.repository.observe(doc.id).first() ?: return@launch onDone()
                    app.repository.update(
                        current.copy(
                            vendor = vendor.trim(),
                            docType = docType,
                            date = date,
                            totalCents = Format.parseAmount(total),
                            currency = currency.trim().ifBlank { null },
                            vatCents = Format.parseAmount(vat),
                            tags = tags,
                            notes = notes.trim(),
                        ),
                    )
                    onDone()
                }
            },
            modifier = Modifier.align(Alignment.End),
        ) { Text("Save") }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
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
