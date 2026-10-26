import Foundation

/** What the model read off a document (the extraction schema's fields). */
struct Extraction: Sendable {
    var title = ""
    var vendor = ""
    var docType = ""
    var date: String?
    var total: Double?
    var currency: String?
    var vat: Double?
    var tags: [String] = []
    var text = ""
    var details: [KeyDetail] = []
    var keywords: [String] = []

    /** From the answer's JSON text. */
    init(json: Data) throws {
        guard let o = try JSONSerialization.jsonObject(with: json) as? [String: Any] else { throw AIError.badAnswer }
        title = o["title"] as? String ?? ""
        vendor = o["vendor"] as? String ?? ""
        docType = o["docType"] as? String ?? ""
        date = o["date"] as? String
        total = (o["total"] as? NSNumber)?.doubleValue
        currency = o["currency"] as? String
        vat = (o["vat"] as? NSNumber)?.doubleValue
        tags = (o["tags"] as? [Any])?.compactMap { $0 as? String } ?? []
        keywords = (o["keywords"] as? [Any])?.compactMap { $0 as? String } ?? []
        text = o["text"] as? String ?? ""
        details = Extraction.details(o["details"])
    }

    init() {}

    /** Label/value pairs, the blank ones dropped. */
    static func details(_ v: Any?) -> [KeyDetail] {
        ((v as? [Any]) ?? []).compactMap { (e: Any) -> KeyDetail? in
            guard let d = e as? [String: Any] else { return nil }
            let label = (d["label"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            let value = (d["value"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            return label.isEmpty || value.isEmpty ? nil : KeyDetail(label: label, value: value)
        }
    }
}

/** How much of a paper's text search looks through (more makes typing in search slow). */
let searchTextChars = 1500

enum ExtractionRules {
    /** [details] with the [found] ones added; one with the same label (any case) is replaced. */
    static func mergeDetails(_ details: [KeyDetail], _ found: [KeyDetail]) -> [KeyDetail] {
        let labels = Set(found.map { $0.label.lowercased() })
        return details.filter { !labels.contains($0.label.lowercased()) } + found
    }

    /** [details] with the one labeled [label] replaced by [found], in its place; any other with one of [found]'s labels goes. */
    static func replaceDetail(_ details: [KeyDetail], label: String, _ found: [KeyDetail]) -> [KeyDetail] {
        guard let at = details.firstIndex(where: { $0.label.caseInsensitiveCompare(label) == .orderedSame }) else { return mergeDetails(details, found) }
        let labels = Set(found.map { $0.label.lowercased() })
        let keep = { (d: KeyDetail) in !labels.contains(d.label.lowercased()) }
        return details[..<at].filter(keep) + found + details[(at + 1)...].filter(keep)
    }

    /** A date read with day and month the wrong way round lands in the future (12/10 read as December 10 in October): swapped, it's a real past date. */
    static func fixFutureDate(_ iso: String, today: Day) -> String {
        guard let d = Day(iso: iso), d > today, d.day <= 12, let swapped = Day(d.year, d.day, d.month) else { return iso }
        return swapped > today ? iso : swapped.iso
    }

    private static let finalForms: [Character: Character] = ["מ": "ם", "נ": "ן", "צ": "ץ", "פ": "ף", "כ": "ך"]

    /** Hebrew words end in the final letter forms (ם ן ץ ף ך), which models sometimes miss. A word followed by a geresh or gershayim is left alone. */
    static func fixHebrewFinals(_ s: String) -> String {
        let chars = Array(s)
        var out = chars
        var i = 0
        func hebrew(_ c: Character) -> Bool { ("\u{05D0}"..."\u{05EA}").contains(c) }
        while i < chars.count {
            guard hebrew(chars[i]) else { i += 1; continue }
            var j = i
            while j < chars.count && hebrew(chars[j]) { j += 1 }
            let next: Character? = j < chars.count ? chars[j] : nil
            let last = chars[j - 1]
            if j - i >= 2, let final = finalForms[last], !["'", "\"", "׳", "״"].contains(next) { out[j - 1] = final }
            i = j
        }
        return String(out)
    }

    /**
     * [p] with what the model read. Tags are added to the ones already there, and a tag that matches an
     * existing one (any case) uses its spelling. Fields in [Paper.edited] were corrected by hand and stay.
     */
    static func apply(_ e: Extraction, to p: Paper, existingTags: [String], today: Day = .today) {
        func cents(_ v: Double?) -> Int64? { v.map { Int64(($0 * 100).rounded()) } }
        func keep(_ field: String) -> Bool { p.edited.contains(field) }
        let newTags = e.tags.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
            .map { t in existingTags.first { $0.caseInsensitiveCompare(t) == .orderedSame } ?? t }
        if !keep("title") { let t = fixHebrewFinals(e.title.trimmingCharacters(in: .whitespaces)); if !t.isEmpty { p.title = t } }
        if !keep("vendor") { let v = fixHebrewFinals(e.vendor.trimmingCharacters(in: .whitespaces)); if !v.isEmpty { p.vendor = v } }
        if !keep("docType"), documentTypes.contains(e.docType) { p.docType = e.docType }
        if !keep("date"), let d = e.date, Day(iso: d) != nil { p.date = fixFutureDate(d, today: today) }
        if !keep("total"), let c = cents(e.total) { p.totalCents = c }
        if !keep("currency"), let c = e.currency?.trimmingCharacters(in: .whitespaces).uppercased(), Locale.commonISOCurrencyCodes.contains(c) { p.currency = c }
        if !keep("vat"), let c = cents(e.vat) { p.vatCents = c }
        if !keep("tags") {
            var seen = Set<String>()
            p.tags = (p.tags + newTags).filter { seen.insert($0.lowercased()).inserted }
        }
        // Search looks at the beginning; questions across papers at all of it (unless the PDF's own text is kept).
        let text = e.text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty { p.extractedText = String(text.prefix(searchTextChars)) }
        if p.fullText.isEmpty { p.fullText = text }
        // Ones saved from Ask AI stay (replacing a read one with the same label).
        if !keep("details") {
            let read = e.details.isEmpty ? p.details.filter { !$0.asked } : e.details
            p.details = mergeDetails(read, p.details.filter(\.asked))
        }
        var seenK = Set<String>()
        let keywords = e.keywords.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty && seenK.insert($0.lowercased()).inserted }
        if !keywords.isEmpty { p.keywords = keywords }
        p.aiState = aiDone
        p.aiError = nil
    }
}

/**
 * A detail value that is a table (rows on separate lines, cells split by "|", the first row the
 * headers) as its rows of cells; nil for an ordinary value. Markdown's outer pipes and |---| rows are allowed.
 */
func tableRows(_ value: String) -> [[String]]? {
    let rows = value.split(separator: "\n", omittingEmptySubsequences: false)
        .map { $0.trimmingCharacters(in: .whitespaces) }
        .filter { !$0.isEmpty }
        .filter { line in !(line.contains("-") && line.allSatisfy { "|-: ".contains($0) }) }
        .map { line -> [String] in
            var l = Substring(line)
            if l.hasPrefix("|") { l = l.dropFirst() }
            if l.hasSuffix("|") { l = l.dropLast() }
            return l.split(separator: "|", omittingEmptySubsequences: false).map { $0.trimmingCharacters(in: .whitespaces) }
        }
    guard rows.count >= 2, rows.allSatisfy({ $0.count >= 2 }) else { return nil }
    let columns = rows.map(\.count).max()!
    return rows.map { $0 + Array(repeating: "", count: columns - $0.count) }
}

/** Mostly Hebrew (or Arabic) letters: read right to left. */
func isRtl(_ text: String) -> Bool {
    var rtl = 0, latin = 0
    for u in text.unicodeScalars {
        if (0x0590...0x06FF).contains(u.value) { rtl += 1 } else if ("A"..."Z").contains(u) || ("a"..."z").contains(u) { latin += 1 }
    }
    return rtl > latin
}

enum AIError: LocalizedError {
    case badAnswer
    case message(String, retryable: Bool = false, code: Int = 0)

    var errorDescription: String? {
        switch self {
        case .badAnswer: "The AI's answer couldn't be read"
        case .message(let m, _, _): m
        }
    }

    var retryable: Bool { if case .message(_, let r, _) = self { r } else { false } }
    var code: Int { if case .message(_, _, let c) = self { c } else { 0 } }
}
