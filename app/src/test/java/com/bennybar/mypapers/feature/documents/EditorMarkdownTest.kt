package com.bennybar.mypapers.feature.documents

import com.mohamedrejeb.richeditor.model.RichTextState
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorMarkdownTest {
    /** Through the real editor and back: headings, lists, bold and a table come out as they went in. */
    @Test fun tableSurvivesTheEditor() {
        val md = """
            # עבודה זוגית - שלב א

            1. ראשון **מודגש** כאן
            2. שני

            - תבליט

            | עמודה | ערך |
            |---|---|
            | א | 1 |
            | ב | 2 |

            סוף.
        """.trimIndent()
        val state = RichTextState().apply { setMarkdown(EditorMarkdown.toEditor(md)) }
        assertEquals(md, EditorMarkdown.fromEditor(state.toMarkdown()))
    }
}
