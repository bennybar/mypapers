package com.bennybar.mypapers.data

import java.math.BigDecimal

/** Papers as a spreadsheet (for an accountant, or a tax refund claim). */
object Export {
    /**
     * CSV with a UTF-8 byte order mark, so Excel shows Hebrew right. Amounts as plain
     * numbers (1234.50), dates as 2026-10-12, so any spreadsheet can sort and sum them.
     * [fileNames]: each paper's file name in the export, by id.
     */
    fun csv(docs: List<Document>, fileNames: Map<String, String>): String {
        val rows = listOf(listOf("Date", "Title", "Vendor", "Type", "Total", "VAT", "Currency", "Tags", "Details", "Notes", "File")) +
            docs.map { d ->
                listOf(
                    d.date.orEmpty(),
                    d.title,
                    d.vendor,
                    d.docType,
                    d.totalCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
                    d.vatCents?.let { BigDecimal.valueOf(it, 2).toPlainString() }.orEmpty(),
                    d.currency.orEmpty(),
                    d.tags.joinToString(", "),
                    d.details.joinToString("; ") { "${it.label}: ${it.value}" },
                    d.notes,
                    fileNames[d.id].orEmpty(),
                )
            }
        return "\uFEFF" + rows.joinToString("\r\n") { row -> row.joinToString(",", transform = ::cell) } + "\r\n"
    }

    private fun cell(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
