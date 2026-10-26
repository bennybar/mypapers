import Foundation

/**
 * When a paper runs out (a warranty, a policy, a subscription's renewal, a bill's due date), from its
 * key details: a detail whose label says so ("Renewal date", "Valid until", "תוקף") and whose value is
 * a date. The AI writes these as 2027-08-15; older readings may say 15/08/2027.
 */
enum Expiry {
    /** How far ahead "expiring soon" looks. */
    static let soonDays = 60

    private static let label = try! NSRegularExpression(pattern: "expir|valid|renew|due|until|thru|through|ends?\\b|end date|תוקף|תפוג|חידוש|לתשלום", options: .caseInsensitive)
    private static let iso = try! NSRegularExpression(pattern: "\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b")
    private static let numeric = try! NSRegularExpression(pattern: "\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4}|\\d{2})\\b")
    private static let monthYear = try! NSRegularExpression(pattern: "\\b(\\d{1,2})[./-](\\d{4})\\b")
    private static let months = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"]
    private static let named = try! NSRegularExpression(pattern: "\\b(?:(\\d{1,2})\\s+)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(?:(\\d{1,2}),?\\s+)?(\\d{4})\\b", options: .caseInsensitive)

    /** The paper's next expiry or due date, else the latest one already past; nil if none. */
    static func date(_ details: [KeyDetail], dayFirst: Bool, today: Day = .today) -> Day? {
        let dates = details.filter { matches(label, $0.label) }.compactMap { parse($0.value, dayFirst: dayFirst) }
        return dates.filter { $0 >= today }.min() ?? dates.max()
    }

    static func daysLeft(_ expires: Day, today: Day) -> Int { today.days(to: expires) }

    static func isSoon(_ details: [KeyDetail], today: Day, dayFirst: Bool) -> Bool {
        guard let d = date(details, dayFirst: dayFirst, today: today) else { return false }
        return (0...soonDays).contains(daysLeft(d, today: today))
    }

    static func parse(_ value: String, dayFirst: Bool) -> Day? {
        if let g = groups(iso, value), let y = Int(g[0]), let m = Int(g[1]), let d = Int(g[2]) { return Day(y, m, d) }
        if let g = groups(numeric, value), let a = Int(g[0]), let b = Int(g[1]), let y = Int(g[2]) {
            let year = y < 100 ? 2000 + y : y
            // A number over 12 can only be the day, whatever the region.
            let (day, month) = a > 12 ? (a, b) : b > 12 ? (b, a) : dayFirst ? (a, b) : (b, a)
            return Day(year, month, day)
        }
        if let g = groups(named, value) {
            guard let mi = months.firstIndex(of: String(g[1].lowercased().prefix(3))), let year = Int(g[3]) else { return nil }
            if let day = Int(g[0].isEmpty ? g[2] : g[0]) { return Day(year, mi + 1, day) }
            return Day.endOfMonth(year, mi + 1)
        }
        // "08/2027": valid to the end of that month.
        if let g = groups(monthYear, value), let m = Int(g[0]), let y = Int(g[1]) { return Day.endOfMonth(y, m) }
        return nil
    }

    private static func matches(_ r: NSRegularExpression, _ s: String) -> Bool {
        r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    /** The first match's groups ("" for one that didn't take part). */
    private static func groups(_ r: NSRegularExpression, _ s: String) -> [String]? {
        guard let m = r.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) else { return nil }
        return (1..<m.numberOfRanges).map { i in
            Range(m.range(at: i), in: s).map { String(s[$0]) } ?? ""
        }
    }
}
