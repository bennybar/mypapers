package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.data.Document
import com.bennybar.mypapers.search.DateWindow
import com.bennybar.mypapers.search.Filters
import com.bennybar.mypapers.search.SortBy
import com.bennybar.mypapers.ui.Format
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private fun SortBy.label() = when (this) {
    SortBy.NEWEST -> "Date on paper: newest"
    SortBy.OLDEST -> "Date on paper: oldest"
    SortBy.AMOUNT_HIGH -> "Amount: high to low"
    SortBy.AMOUNT_LOW -> "Amount: low to high"
    SortBy.VENDOR -> "Vendor A–Z"
}

private fun DateWindow.label() = when (this) {
    DateWindow.THIS_MONTH -> "This month"
    DateWindow.LAST_MONTH -> "Last month"
    DateWindow.THIS_YEAR -> "This year"
    DateWindow.LAST_YEAR -> "Last year"
    DateWindow.CUSTOM -> "Custom…"
}

/** "Oct 1 – Oct 31, 2026" style text for a custom range (or the window's name). */
private fun Filters.dateLabel(): String? = when (date) {
    null -> null
    DateWindow.CUSTOM -> listOfNotNull(from?.let { Format.date(it.toString()) }, to?.let { Format.date(it.toString()) }).joinToString(" – ").ifEmpty { "Custom dates" }
    else -> date.label()
}

private fun Filters.amountLabel(): String? = when {
    minCents != null && maxCents != null -> "${Format.amount(minCents, null)} – ${Format.amount(maxCents, null)}"
    minCents != null -> "From ${Format.amount(minCents, null)}"
    maxCents != null -> "Up to ${Format.amount(maxCents, null)}"
    else -> null
}

/** Sort, dates, amounts, vendors and "show only" — everything beyond the type and tag chips. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(filters: Filters, vendors: List<String>, onChange: (Filters) -> Unit, onClearAll: () -> Unit, onDismiss: () -> Unit) {
    var pickRange by remember { mutableStateOf(false) }
    var min by remember { mutableStateOf(Format.amountInput(filters.minCents)) }
    var max by remember { mutableStateOf(Format.amountInput(filters.maxCents)) }
    var allVendors by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter and sort", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { min = ""; max = ""; onClearAll() }) { Text("Clear all") }
            }

            SheetSection("Sort by")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The default: what was added last first.
                FilterChip(
                    selected = filters.sort == null,
                    onClick = { onChange(filters.copy(sort = null)) },
                    label = { Text("Recently added") },
                    shape = CircleShape,
                )
                SortBy.entries.forEach { s ->
                    FilterChip(
                        selected = filters.sort == s,
                        onClick = { onChange(filters.copy(sort = if (filters.sort == s) null else s)) },
                        label = { Text(s.label()) },
                        shape = CircleShape,
                    )
                }
            }

            SheetSection("Date")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateWindow.entries.forEach { w ->
                    FilterChip(
                        selected = filters.date == w,
                        onClick = {
                            if (w == DateWindow.CUSTOM) pickRange = true
                            else onChange(filters.copy(date = if (filters.date == w) null else w, from = null, to = null))
                        },
                        label = { Text(if (w == DateWindow.CUSTOM && filters.date == w) filters.dateLabel()!! else w.label()) },
                        shape = CircleShape,
                    )
                }
            }

            SheetSection("Amount")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    min,
                    { min = it; onChange(filters.copy(minCents = Format.parseAmount(it))) },
                    label = { Text("From") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    max,
                    { max = it; onChange(filters.copy(maxCents = Format.parseAmount(it))) },
                    label = { Text("To") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }

            if (vendors.isNotEmpty()) {
                SheetSection("Vendor")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = if (allVendors) vendors else vendors.take(12)
                    // Picked ones stay visible even when the list is short.
                    (shown + filters.vendors.filter { it !in shown }).forEach { v ->
                        FilterChip(
                            selected = v in filters.vendors,
                            onClick = { onChange(filters.copy(vendors = if (v in filters.vendors) filters.vendors - v else filters.vendors + v)) },
                            label = { Text(v) },
                            shape = CircleShape,
                        )
                    }
                }
                if (vendors.size > 12) TextButton(onClick = { allVendors = !allVendors }) { Text(if (allVendors) "Fewer" else "All ${vendors.size} vendors") }
            }

            SheetSection("Show only")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filters.missingInfo, { onChange(filters.copy(missingInfo = !filters.missingInfo)) }, { Text("Missing amount, date or vendor") }, shape = CircleShape)
                FilterChip(filters.duplicates, { onChange(filters.copy(duplicates = !filters.duplicates)) }, { Text("Possible duplicates") }, shape = CircleShape)
                FilterChip(filters.unread, { onChange(filters.copy(unread = !filters.unread)) }, { Text("Not read by AI") }, shape = CircleShape)
            }

            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(vertical = 12.dp)) { Text("Done") }
        }
    }

    if (pickRange) {
        fun ms(d: LocalDate?) = d?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = ms(filters.from), initialSelectedEndDateMillis = ms(filters.to))
        DatePickerDialog(
            onDismissRequest = { pickRange = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        pickRange = false
                        onChange(filters.copy(date = DateWindow.CUSTOM, from = day(state.selectedStartDateMillis), to = day(state.selectedEndDateMillis ?: state.selectedStartDateMillis)))
                    },
                ) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickRange = false }) { Text("Cancel") } },
        ) { DateRangePicker(state, modifier = Modifier.weight(1f)) }
    }
}

@Composable
private fun SheetSection(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

/** The sheet's active filters as chips to remove one by one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActiveFilters(filters: Filters, onChange: (Filters) -> Unit, modifier: Modifier = Modifier) {
    if (filters.activeCount == 0) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        @Composable
        fun chip(text: String, remove: Filters.() -> Filters) = InputChip(
            selected = true,
            onClick = { onChange(filters.remove()) },
            label = { Text(text) },
            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $text") },
            shape = CircleShape,
        )
        filters.sort?.let { chip("Sort: ${it.label()}") { copy(sort = null) } }
        filters.dateLabel()?.let { chip(it) { copy(date = null, from = null, to = null) } }
        filters.amountLabel()?.let { chip(it) { copy(minCents = null, maxCents = null) } }
        filters.vendors.forEach { v -> chip(v) { copy(vendors = vendors - v) } }
        if (filters.missingInfo) chip("Missing info") { copy(missingInfo = false) }
        if (filters.duplicates) chip("Possible duplicates") { copy(duplicates = false) }
        if (filters.unread) chip("Not read by AI") { copy(unread = false) }
    }
}

/** "12 papers · ₪3,420.50" — the sum per currency of what's shown (and how many have no amount). */
@Composable
fun ResultSummary(docs: List<Document>, modifier: Modifier = Modifier) {
    val sums = docs.filter { it.totalCents != null }.groupBy { it.currency }
        .map { (currency, list) -> Format.amount(list.sumOf { it.totalCents!! }, currency) }
    val withoutAmount = docs.count { it.totalCents == null }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (docs.size == 1) "1 paper" else "${docs.size} papers",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (sums.isNotEmpty()) {
            Text(
                " · " + sums.joinToString(" + "),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (withoutAmount > 0 && sums.isNotEmpty()) {
            Text(
                " ($withoutAmount without amount)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
