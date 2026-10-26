import Foundation

/** The prompts and answer schemas, word for word as on Android, so both apps read papers alike. */
enum Prompts {
    static func extraction(existingTags: [String], locale: Locale) -> String {
        let currency = locale.currency?.identifier ?? "USD"
        let dateOrder = Format.dayFirst(locale) ? "day before month (03/04/2026 is 3 April 2026)" : "month before day (03/04/2026 is March 4, 2026)"
        let tags = existingTags.isEmpty ? "(none yet)" : existingTags.joined(separator: ", ")
        let tagLanguage = existingTags.isEmpty ? "English" : "the same language as the existing tags"
        return """
        You read receipts and documents for a personal paper archive. Fill every field from the attached document.
        - title: a short, clear name for it, the way someone would label it in a folder: what it is and from whom, plus the month for periodic bills (e.g. "019 Mobile bill, September 2026", "Shufersal groceries", "Washing machine warranty"). Never a numeric date (the date has its own field). In the document's own language. Not the file name.
        - vendor: the business or issuer, named the way a person would say it (e.g. "Shufersal", not "SHUFERSAL DEAL BRANCH 123"), in the document's own language and script. Empty if there is none.
        - docType: Receipt (proof of a payment), Invoice (a bill or tax invoice, paid or to pay), Warranty (a warranty or guarantee), Contract (an agreement or policy), or Other.
        - date: the document's date (purchase or issue) as YYYY-MM-DD, or null. Read numeric dates the way the document's country writes them: day before month for Israeli, European and most non-US documents (12/10/2026 is 12 October 2026), month before day for US documents. Only if the document gives no clue, numeric dates are \(dateOrder). A document's date is almost never in the future.
        - total: the final amount paid or due, tax included, as a number; null if none.
        - currency: ISO 4217 code. If only a symbol or nothing is printed, infer it from the country and language; otherwise \(currency).
        - vat: the VAT or sales-tax amount if printed, else null.
        - tags: 1 to 3 short category tags. Existing tags: \(tags). Reuse an existing tag, spelled exactly, whenever one fits; add a new one only if none fits, in \(tagLanguage), Title Case.
        - details: the specific facts worth keeping for this kind of document, as short label/value pairs, in the document's language. Examples: a glasses or contact-lens prescription — one entry per eye with SPH, CYL, AXIS and ADD (e.g. "Right eye (OD)" → "SPH -1.25 · CYL -0.50 · AXIS 90 · ADD +1.00"), plus PD and the prescription date; insurance — policy number, coverage period, insured; warranty — period, serial or model number; subscription — plan, renewal date, account number; bills — account or customer number, billing period, due date; medical — the test results that matter. Empty for an ordinary shop receipt. When the details are what matters (a prescription), the total can be null. Write an expiry, renewal, valid-until or due date as YYYY-MM-DD, with a label that says which ("Valid until", "Renewal date", "Due date"), so the app can remind before it.
        - keywords: 4 to 8 short search terms someone might type to find this document, in English AND in the document's language when that isn't English (e.g. "eye prescription", "glasses", "optometrist", "מרשם משקפיים", "בדיקת ראייה").
        - text: the document's readable text, in full, up to about 6000 characters.
        """
    }

    /**
     * A question or instruction about one paper, in a conversation: [record] is the paper as the app
     * has it now, [history] the earlier turns.
     */
    static func ask(record: String, history: [PaperTurn], instruction: String) -> String {
        var s = "You help with one document from a personal paper archive (attached). The app's record of it now:\n\(record)\n"
        if !history.isEmpty {
            s += "\nEarlier in this conversation:\n"
            for t in history {
                s += "User: \(t.question)\nYou: \(t.reply ?? "")\n"
                for d in t.details ?? [] { s += "  \(d.label): \(d.value)\n" }
            }
        }
        s += "\nNow the user says: \"\(instruction.trimmingCharacters(in: .whitespacesAndNewlines))\"\n\n"
        s += """
        - To find, extract, explain or reshape information ("extract the URL", "as a table", "only section 3", "in English"): put it in details, short label/value pairs in the document's language unless asked otherwise (e.g. "URL" → "https://…"), values copied exactly as printed, dates as YYYY-MM-DD. A follow-up that reshapes an earlier answer returns the whole reshaped answer. Reworking one of the record's details keeps its exact label. For a table (line items, a schedule, results), or when asked for one, one entry whose value is the table: one row per line, cells separated by " | ", the first row the column headers.
        - To change the record (title, vendor, type, date, amount, currency, tags, removing a detail by its label): fill only those fields of changes; leave the rest null or empty. Types: Receipt, Invoice, Warranty, Contract, Other. Dates YYYY-MM-DD.
        - reply: one or two sentences in the user's language: what you found, what will change, or that the document doesn't have it.
        - Never invent what isn't in the document or the user's words.

        """
        return s
    }

    /** All of a paper's text, written out (a handwritten page too). */
    static let transcribe = """
    Write out all the text of the attached document as Markdown, every word, in its own language and script, top to bottom.
    Keep its structure: "#"/"##" headings for titles, "1." for numbered sections, "-" for bullets, **bold** for underlined or emphasized words, Markdown tables (with a |---| row) for tables, a blank line between paragraphs.
    The page comes whole and then in overlapping close-ups (rows top to bottom, columns left to right as in the picture): read the words from the close-ups, use the whole page for the order, and write overlapping text once.
    Handwriting too: work out each word from its letters and the sentence around it, and write your best reading even when unsure. Use [?] only for a word that's truly illegible; never leave out a part or summarize. No commentary of your own.
    """

    static let transcribeSchema = #"{"type":"object","additionalProperties":false,"required":["text"],"properties":{"text":{"type":"string"}}}"#

    static let askSchema = #"""
    {"type":"object","additionalProperties":false,"required":["reply","details","changes"],
    "properties":{"reply":{"type":"string"},
    "details":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","value"],"properties":{"label":{"type":"string"},"value":{"type":"string"}}}},
    "changes":{"type":"object","additionalProperties":false,"required":["title","vendor","docType","date","total","currency","addTags","removeTags","removeDetails"],
    "properties":{"title":{"type":["string","null"]},"vendor":{"type":["string","null"]},"docType":{"type":["string","null"]},"date":{"type":["string","null"]},
    "total":{"type":["number","null"]},"currency":{"type":["string","null"]},"addTags":{"type":"array","items":{"type":"string"}},
    "removeTags":{"type":"array","items":{"type":"string"}},"removeDetails":{"type":"array","items":{"type":"string"}}}}}}
    """#

    /** The extraction answer's schema (strict structured output). */
    static let schema = #"""
    {"type":"object","additionalProperties":false,
    "required":["title","vendor","docType","date","total","currency","vat","tags","details","keywords","text"],
    "properties":{"title":{"type":"string"},"vendor":{"type":"string"},"docType":{"type":"string","enum":["Receipt","Invoice","Warranty","Contract","Other"]},
    "date":{"type":["string","null"]},"total":{"type":["number","null"]},"currency":{"type":["string","null"]},
    "vat":{"type":["number","null"]},"tags":{"type":"array","items":{"type":"string"}},"keywords":{"type":"array","items":{"type":"string"}},
    "details":{"type":"array","items":{"type":"object","additionalProperties":false,"required":["label","value"],"properties":{"label":{"type":"string"},"value":{"type":"string"}}}},"text":{"type":"string"}}}
    """#
}
