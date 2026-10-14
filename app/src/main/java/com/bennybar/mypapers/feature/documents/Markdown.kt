package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.data.tableRows

/** Mostly Hebrew (or Arabic) letters: read right to left. */
fun isRtl(text: String): Boolean =
    text.count { it in '\u0590'..'\u06FF' } > text.count { it in 'A'..'Z' || it in 'a'..'z' }

/**
 * The Markdown the AI writes a paper out in: headings, lists, **bold**, tables and paragraphs.
 * All of it in the direction of the whole text: a Hebrew page right to left, lines of only
 * numbers or [?] and the odd English word included (each line on its own would flip about).
 */
@Composable
fun Markdown(text: String, modifier: Modifier = Modifier) {
    val rtl = isRtl(text)
    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        MarkdownBlocks(text, modifier, if (rtl) TextDirection.Rtl else TextDirection.Ltr)
    }
}

@Composable
private fun MarkdownBlocks(text: String, modifier: Modifier, direction: TextDirection) {
    val body = MaterialTheme.typography.bodyLarge.copy(textDirection = direction)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.isEmpty() -> i++
                line.startsWith("|") -> {
                    // The whole table at once.
                    val start = i
                    while (i < lines.size && lines[i].trim().startsWith("|")) i++
                    val rows = tableRows(lines.subList(start, i).joinToString("\n"))
                    if (rows != null) DetailTable(rows) else Text(inline(lines.subList(start, i).joinToString("\n")), style = body)
                }
                line.startsWith("#") -> {
                    val level = line.takeWhile { it == '#' }.length
                    Text(
                        inline(line.drop(level).trim()),
                        style = (if (level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium).copy(textDirection = direction),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    i++
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    Text(inline("•  " + line.drop(2)), style = body)
                    i++
                }
                else -> {
                    Text(inline(line), style = body)
                    i++
                }
            }
        }
    }
}

/** **bold** and web addresses as links. */
@Composable
private fun inline(text: String): AnnotatedString {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        text.split("**").forEachIndexed { n, part ->
            val bold = n % 2 == 1
            if (bold) pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold))
            var at = 0
            URL.findAll(part).forEach { m ->
                append(part.substring(at, m.range.first))
                val url = if (m.value.startsWith("www.", ignoreCase = true)) "https://${m.value}" else m.value
                withLink(LinkAnnotation.Url(url, link)) { append(m.value) }
                at = m.range.last + 1
            }
            append(part.substring(at))
            if (bold) pop()
        }
    }
}
