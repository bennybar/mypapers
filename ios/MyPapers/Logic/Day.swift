import Foundation

/** A calendar day (no time), like java.time.LocalDate: what papers' dates and expiries are. */
struct Day: Comparable, Hashable, Sendable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    /** nil for a day that doesn't exist (31 February). */
    init?(_ year: Int, _ month: Int, _ day: Int) {
        guard (1...12).contains(month), day >= 1, day <= Day.length(year, month) else { return nil }
        self.year = year
        self.month = month
        self.day = day
    }

    init(_ date: Date, calendar: Calendar = .current) {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        year = c.year!
        month = c.month!
        day = c.day!
    }

    /** "2026-10-12"; nil otherwise. */
    init?(iso: String?) {
        guard let iso else { return nil }
        let p = iso.split(separator: "-")
        guard p.count == 3, p[0].count == 4, let y = Int(p[0]), let m = Int(p[1]), let d = Int(p[2]) else { return nil }
        self.init(y, m, d)
    }

    static var today: Day { Day(Date()) }

    static func length(_ year: Int, _ month: Int) -> Int {
        var c = DateComponents()
        c.year = year
        c.month = month
        let cal = Calendar(identifier: .gregorian)
        guard let date = cal.date(from: c), let r = cal.range(of: .day, in: .month, for: date) else { return 31 }
        return r.count
    }

    /** The last day of [month]. */
    static func endOfMonth(_ year: Int, _ month: Int) -> Day? { Day(year, month, length(year, month)) }

    var iso: String { String(format: "%04d-%02d-%02d", year, month, day) }
    var description: String { iso }

    var date: Date {
        Calendar(identifier: .gregorian).date(from: DateComponents(year: year, month: month, day: day))!
    }

    /** Days from self to [other]: negative when [other] is earlier. */
    func days(to other: Day) -> Int {
        Calendar(identifier: .gregorian).dateComponents([.day], from: date, to: other.date).day ?? 0
    }

    func adding(days: Int) -> Day { Day(Calendar(identifier: .gregorian).date(byAdding: .day, value: days, to: date)!) }

    static func < (a: Day, b: Day) -> Bool { (a.year, a.month, a.day) < (b.year, b.month, b.day) }

    static func fromEpochMs(_ ms: Int64) -> Day { Day(Date(timeIntervalSince1970: Double(ms) / 1000)) }
}
