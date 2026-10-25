package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.ai.AskAllAnswer
import com.bennybar.mypapers.ui.Format

/**
 * A conversation about all the papers: each question with the AI's answer and the papers it comes
 * from (tap to open), and a field for a follow-up ("and last year?"). [turns]: each question with its
 * answer, null while it's asked.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AskAllSheet(turns: List<Pair<String, Result<AskAllAnswer>?>>, onFollowUp: (String) -> Unit, onOpen: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            turns.forEachIndexed { n, (question, result) ->
                if (n > 0) HorizontalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                when {
                    result == null -> Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    result.isFailure -> Text(
                        "Couldn't ask: ${result.exceptionOrNull()?.message}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Answer(result.getOrThrow(), onOpen)
                }
            }
            // Only once the last one is answered.
            if (turns.lastOrNull()?.second != null) FollowUpField("Ask a follow-up", onFollowUp, Modifier.padding(top = 20.dp))
            Text(
                "Answers can be wrong — check the papers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

@Composable
private fun Answer(a: AskAllAnswer, onOpen: (String) -> Unit) {
    SelectionContainer { Text(a.answer, style = MaterialTheme.typography.bodyLarge) }
    // Added up here, not by the AI.
    a.totals?.takeIf { it.isNotEmpty() }?.let { totals ->
        Text(
            "Total: " + totals.entries.joinToString(" · ") { (currency, cents) -> Format.amount(cents, currency.ifEmpty { null }) },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp),
        )
        val counted = a.papers.count { it.totalCents != null }
        Text(
            if (counted == 1) "From 1 paper" else "From $counted papers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (a.papers.isNotEmpty()) {
        Text(
            "From your papers",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp, bottom = 4.dp),
        )
        Column {
            a.papers.forEachIndexed { i, doc ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ListItem(
                    headlineContent = { Text(doc.title()) },
                    supportingContent = { Text(doc.subtitle()) },
                    trailingContent = doc.totalCents?.let { c -> { Text(Format.amount(c, doc.currency)) } },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable { onOpen(doc.id) },
                )
            }
        }
    }
}

/** A line to type the next question in, sent with the button or the keyboard's send. */
@Composable
internal fun FollowUpField(placeholder: String, onSend: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable { mutableStateOf("") }
    fun send() {
        if (text.isBlank()) return
        onSend(text.trim())
        text = ""
    }
    OutlinedTextField(
        text, { text = it },
        placeholder = { Text(placeholder) },
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { send() }),
        trailingIcon = {
            IconButton(shapes = IconButtonDefaults.shapes(), onClick = { send() }, enabled = text.isNotBlank()) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        },
        modifier = modifier.fillMaxWidth(),
    )
}
