package com.bennybar.mypapers.feature.documents

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mohamedrejeb.richeditor.model.HeadingStyle
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.RichTextEditor

/**
 * The editor knows no tables: their rows go in as lines of text with "│" between cells
 * (one paragraph each), and come back out as a Markdown table.
 */
object EditorMarkdown {
    private const val BAR = "│"
    private fun isTableRow(line: String) = line.trim().startsWith("|")
    private fun isSeparator(line: String) = line.contains('-') && line.trim().all { it in "|-: " }

    fun toEditor(markdown: String): String = markdown.lines()
        .filterNot { isTableRow(it) && isSeparator(it) }
        .joinToString("\n") { if (isTableRow(it)) it.trim().replace("|", BAR) + "\n" else it }

    fun fromEditor(markdown: String): String {
        val out = mutableListOf<String>()
        var inTable = false
        for (line in markdown.lines()) {
            val row = line.trim().startsWith(BAR)
            when {
                row -> {
                    val cells = line.trim().replace(BAR, "|")
                    if (!inTable) {
                        out += cells
                        // A Markdown table's header line, under its first row.
                        out += "|" + "---|".repeat((cells.count { it == '|' } - 1).coerceAtLeast(1))
                    } else {
                        // Rows were paragraphs apart in the editor: together again.
                        while (out.lastOrNull()?.isBlank() == true) out.removeAt(out.size - 1)
                        out += cells
                    }
                    inTable = true
                }
                line.isBlank() -> out += line
                else -> {
                    if (inTable) out += ""
                    inTable = false
                    out += line
                }
            }
        }
        return out.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }
}

/** The paper's text, edited as it looks: bold, a title, headings and lists from a toolbar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorDialog(markdown: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val state = rememberRichTextState()
    LaunchedEffect(Unit) { state.setMarkdown(EditorMarkdown.toEditor(markdown)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val rtl = isRtl(markdown)
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onDismiss)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Edit text") },
                    navigationIcon = { IconButton(shapes = IconButtonDefaults.shapes(), onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel") } },
                    actions = {
                        IconButton(shapes = IconButtonDefaults.shapes(), onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete text") }
                        TextButton(onClick = { onSave(EditorMarkdown.fromEditor(state.toMarkdown())) }) { Text("Save") }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    IconToggleButton(
                        checked = state.currentSpanStyle.fontWeight == FontWeight.Bold,
                        onCheckedChange = { state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold)) },
                    ) { Icon(Icons.Outlined.FormatBold, contentDescription = "Bold") }
                    IconToggleButton(
                        checked = state.currentHeadingStyle == HeadingStyle.H1,
                        onCheckedChange = { state.setHeadingStyle(if (it) HeadingStyle.H1 else HeadingStyle.Normal) },
                    ) { Icon(Icons.Outlined.Title, contentDescription = "Title") }
                    IconToggleButton(
                        checked = state.currentHeadingStyle == HeadingStyle.H2,
                        onCheckedChange = { state.setHeadingStyle(if (it) HeadingStyle.H2 else HeadingStyle.Normal) },
                    ) { Text("H", style = MaterialTheme.typography.titleMedium) }
                    IconToggleButton(checked = state.isUnorderedList, onCheckedChange = { state.toggleUnorderedList() }) {
                        Icon(Icons.Outlined.FormatListBulleted, contentDescription = "Bulleted list")
                    }
                    IconToggleButton(checked = state.isOrderedList, onCheckedChange = { state.toggleOrderedList() }) {
                        Icon(Icons.Outlined.FormatListNumbered, contentDescription = "Numbered list")
                    }
                }
                HorizontalDivider()
                // A Hebrew page right to left, like on the paper.
                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    RichTextEditor(
                        state,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete the text?") },
            text = { Text("You can extract it again from the paper's menu.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
