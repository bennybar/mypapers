import Foundation

/**
 * One search box over title, vendor, tags, type, file name, details, notes and the paper's text,
 * forgiving typos ("shufersl" finds Shufersal) and partial words. A number also matches amounts by
 * closeness: "120" ranks ₪118 and ₪125 first. Every word typed must match something; best first.
 */
enum FuzzySearch {
    /** How far off an amount may be and still match, relative to the number typed. */
    private static let amountTolerance = 0.5

    static func search(_ docs: [Paper], _ query: String, tags: Set<String> = [], locale: Locale) -> [Paper] {
        let tagged = tags.isEmpty ? docs : docs.filter { Set($0.tags).isSuperset(of: tags) }
        let tokens = query.split(whereSeparator: \.isWhitespace).map(String.init)
        if tokens.isEmpty { return tagged.sorted(by: recentlyAdded) }

        var scored: [(Paper, Double)] = []
        for doc in tagged {
            let f = fields(doc)
            var total = 0.0
            var ok = true
            for token in tokens {
                let amount = parseAmount(token, locale: locale).map { amountScore(doc, $0) } ?? 0
                let parts = normalize(token).split(separator: " ").map(String.init)
                let text = parts.map { t in
                    f.map { words, weight in weight * (words.map { tokenScore(t, $0) }.max() ?? 0) }.max() ?? 0
                }.min() ?? 0
                let best = max(amount, text)
                if best <= 0 { ok = false; break }
                total += best
            }
            if ok { scored.append((doc, total)) }
        }
        if scored.isEmpty {
            // A lone number with nothing near it: still show the closest amounts.
            guard tokens.count == 1, let amount = parseAmount(tokens[0], locale: locale) else { return [] }
            return Array(tagged.filter { $0.totalCents != nil }
                .sorted { abs(Double($0.totalCents!) / 100 - amount) < abs(Double($1.totalCents!) / 100 - amount) }
                .prefix(5))
        }
        return scored.sorted { a, b in a.1 != b.1 ? a.1 > b.1 : recentlyAdded(a.0, b.0) }.map(\.0)
    }

    /** What was just added first, whatever date is printed on it. */
    static func recentlyAdded(_ a: Paper, _ b: Paper) -> Bool { a.createdAt > b.createdAt }

    /** Each field's words with how much a match there counts. */
    private static func fields(_ d: Paper) -> [([String], Double)] {
        [
            (words(d.title), 3),
            (words(d.vendor), 3),
            (d.tags.flatMap(words), 3),
            (words(d.docType), 2),
            (words(d.fileName ?? ""), 1),
            (d.details.flatMap { words($0.label) + words($0.value) }, 2),
            (d.keywords.flatMap(words), 2.5),
            (words(d.notes), 1),
            (words(d.extractedText), 1),
        ]
    }

    private static func words(_ s: String) -> [String] { normalize(s).split(separator: " ").map(String.init) }

    /**
     * Lower case, no accents or Hebrew vowel points, Hebrew final letters as their regular form
     * (so "שלום" matches typed "שלומ"), punctuation as spaces.
     */
    static func normalize(_ s: String) -> String {
        let stripped = s.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars
            .filter { c in
                switch c.properties.generalCategory {
                case .nonspacingMark, .spacingMark, .enclosingMark: false
                default: true
                }
            }
        var out = String.UnicodeScalarView()
        for c in stripped {
            switch c {
            case "ך": out.append("כ")
            case "ם": out.append("מ")
            case "ן": out.append("נ")
            case "ף": out.append("פ")
            case "ץ": out.append("צ")
            default:
                if CharacterSet.alphanumerics.contains(c) { out.append(c) } else { out.append(" ") }
            }
        }
        return String(out).trimmingCharacters(in: .whitespaces)
    }

    /** 0 = no match, up to 1 = the same word. */
    static func tokenScore(_ token: String, _ word: String) -> Double {
        if word == token { return 1 }
        if word.hasPrefix(token) { return 0.9 }
        if token.count >= 3 && word.contains(token) { return 0.7 }
        if token.count < 4 { return 0 }
        let allowed = token.count <= 5 ? 1 : 2
        // Whole word or its beginning, so a typo in a partial word still matches.
        let d = min(distance(token, word), distance(token, String(word.prefix(token.count))))
        return d <= allowed ? 0.6 - 0.1 * Double(d) : 0
    }

    /** 0 = no match, up to 3 (like a vendor match) for the exact amount. */
    private static func amountScore(_ doc: Paper, _ amount: Double) -> Double {
        guard let cents = doc.totalCents else { return 0 }
        let total = Double(cents) / 100
        let rel = abs(total - amount) / max(abs(amount), 1)
        return rel > amountTolerance ? 0 : 3 * (1 - rel / amountTolerance) + 0.01
    }

    /** The token as an amount, or nil: "120", "₪120", "1,249", "118.50" ("118,50" where the comma is decimal). */
    static func parseAmount(_ token: String, locale: Locale) -> Double? {
        if token.contains(where: \.isLetter) { return nil }
        let s = token.filter { $0.isNumber || $0 == "." || $0 == "," }
        guard s.contains(where: \.isNumber) else { return nil }
        let decimal = locale.decimalSeparator ?? "."
        let grouping = locale.groupingSeparator ?? ","
        let plain = s.replacingOccurrences(of: grouping, with: "").replacingOccurrences(of: decimal, with: ".")
        return Double(plain)
    }

    /** Optimal-string-alignment distance: edits including swapping two neighbours. */
    static func distance(_ a: String, _ b: String) -> Int {
        let a = Array(a), b = Array(b)
        if a.isEmpty { return b.count }
        if b.isEmpty { return a.count }
        var d = Array(repeating: Array(repeating: 0, count: b.count + 1), count: a.count + 1)
        for i in 0...a.count { d[i][0] = i }
        for j in 0...b.count { d[0][j] = j }
        for i in 1...a.count {
            for j in 1...b.count {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1] { d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1) }
            }
        }
        return d[a.count][b.count]
    }
}
