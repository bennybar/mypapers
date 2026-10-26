import Foundation

/** What a question about all the papers came back with; [totals] (per currency, in cents) only for a question about amounts. */
struct AskAllAnswer {
    var answer: String
    var papers: [Paper]
    var totals: [String: Int64]?
}

/**
 * A question about all the papers ("where did I buy the washing machine?"), in two steps: the model
 * picks the papers that may help from a one-line-each list, then answers from their whole text.
 * Papers are numbered in the prompts (shorter than their ids).
 */
enum AskAll {
    /** Papers read in full for the answer; the others it picked still count for a total. */
    static let maxRead = 10
    private static let maxTextChars = 10_000

    /** One line per paper: number, date, type, title, vendor, amount, tags, keywords, details. */
    static func line(_ n: Int, _ d: Paper) -> String {
        [
            "#\(n)",
            d.date ?? "",
            d.docType,
            d.title,
            d.vendor,
            d.totalCents.map { String(format: "%.2f %@", Double($0) / 100, d.currency ?? "") } ?? "",
            d.tags.joined(separator: ", "),
            d.keywords.joined(separator: ", "),
            d.details.map { "\($0.label): \(String($0.value.replacingOccurrences(of: "\n", with: " ").prefix(80)))" }.joined(separator: "; "),
        ].joined(separator: " | ")
    }

    /** "They ask", or for a follow-up ("and last year?") the conversation so far and "Now they ask". */
    private static func earlier(_ history: [AllTurn]) -> String {
        if history.isEmpty { return "They ask" }
        return "Earlier in this conversation:\n" + history.map { "Q: \($0.question)\nA: \($0.answer ?? "")" }.joined(separator: "\n") + "\nNow they ask"
    }

    static func pick(_ question: String, _ docs: [Paper], history: [AllTurn] = []) -> String {
        let q = question.trimmingCharacters(in: .whitespacesAndNewlines)
        return """
        You help someone find things in their personal archive of receipts and documents. \(earlier(history).replacingOccurrences(of: "\n", with: " ")): "\(q)" (a follow-up reads with what came before)
        Below is one line per paper: number | date | type | title | vendor | amount | tags | keywords | details.
        - papers: the numbers of every paper that may help answer it, the most useful first (at most 100). None if nothing fits.
        - total: true if the answer is an amount added up over papers (how much spent or paid), else false.

        \(docs.enumerated().map { line($0.offset + 1, $0.element) }.joined(separator: "\n"))
        """
    }

    static let pickSchema = #"""
    {"type":"object","additionalProperties":false,"required":["papers","total"],
    "properties":{"papers":{"type":"array","items":{"type":"integer"}},"total":{"type":"boolean"}}}
    """#

    /** [read]: the papers given in full; [listed]: more picked ones, by their line only. */
    static func answer(_ question: String, history: [AllTurn], total: Bool, read: [(Int, Paper)], listed: [(Int, Paper)]) -> String {
        var s = "Answer a question about someone's personal archive of receipts and documents, from the papers below.\n"
        s += earlier(history) + ": \"\(question.trimmingCharacters(in: .whitespacesAndNewlines))\" (a follow-up reads with what came before)\n"
        s += "- answer: short and direct, in the language of the question. Name the papers it comes from by their title. Copy names, numbers and dates exactly as written. If the papers don't answer it, say so plainly.\n"
        if total { s += "  Don't add amounts up yourself: the app shows the total of the papers you list.\n" }
        s += "- papers: the numbers of the papers the answer is based on\(total ? " (for a total: every paper that counts towards it)" : "").\n"
        for (n, d) in read {
            s += "\n" + line(n, d) + "\n"
            if !d.notes.trimmingCharacters(in: .whitespaces).isEmpty { s += "Notes: \(d.notes)\n" }
            let text = !d.transcript.isEmpty ? d.transcript : !d.fullText.isEmpty ? d.fullText : d.extractedText
            if !text.trimmingCharacters(in: .whitespaces).isEmpty { s += "Text:\n\(text.prefix(maxTextChars))\n" }
        }
        if !listed.isEmpty {
            s += "\nMore papers (their text isn't included):\n"
            for (n, d) in listed { s += line(n, d) + "\n" }
        }
        return s
    }

    static let answerSchema = #"""
    {"type":"object","additionalProperties":false,"required":["answer","papers"],
    "properties":{"answer":{"type":"string"},"papers":{"type":"array","items":{"type":"integer"}}}}
    """#

    private static func object(_ json: String) -> [String: Any] {
        ((try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]) ?? [:]
    }

    /** The numbers listed, each once, in order. */
    static func numbers(_ json: String) -> [Int] {
        var seen = Set<Int>()
        return ((object(json)["papers"] as? [Any]) ?? []).compactMap { ($0 as? NSNumber)?.intValue }.filter { seen.insert($0).inserted }
    }

    static func isTotal(_ json: String) -> Bool { object(json)["total"] as? Bool == true }

    static func answerText(_ json: String) -> String { object(json)["answer"] as? String ?? "" }

    /** Amounts added up per currency (cents), papers without an amount left out. */
    static func totals(_ docs: [Paper]) -> [String: Int64] {
        var out: [String: Int64] = [:]
        for d in docs { if let c = d.totalCents { out[d.currency ?? "", default: 0] += c } }
        return out
    }
}
