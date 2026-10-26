import Foundation

enum DateWindow: String, CaseIterable, Codable, Sendable { case thisMonth, lastMonth, thisYear, lastYear, custom }

enum SortBy: String, CaseIterable, Codable, Sendable {
    case newest, oldest, amountHigh, amountLow, vendor

    var label: String {
        switch self {
        case .newest: "Date on paper: newest"
        case .oldest: "Date on paper: oldest"
        case .amountHigh: "Amount: high to low"
        case .amountLow: "Amount: low to high"
        case .vendor: "Vendor A–Z"
        }
    }
}

/** The filter sheet's choices (types and tags are the chips above the list). A paper without a date counts by the day it was added. */
struct Filters: Equatable, Sendable {
    var date: DateWindow?
    /** For [DateWindow.custom], inclusive. */
    var from: Day?
    var to: Day?
    var minCents: Int64?
    var maxCents: Int64?
    var vendors: Set<String> = []
    /** No amount, date or vendor yet. */
    var missingInfo = false
    var duplicates = false
    /** Not read by the AI and not filled in. */
    var unread = false
    /** Expires within [Expiry.soonDays] days (from its key details). */
    var expiring = false
    /** nil: best match first when searching, recently added first otherwise. */
    var sort: SortBy?

    /** Filters narrowing the list (the sort isn't one: it has its own switch, and is remembered). */
    var activeCount: Int {
        [date != nil, minCents != nil || maxCents != nil, !vendors.isEmpty, missingInfo, duplicates, unread, expiring].filter { $0 }.count
    }

    /** The date window's first and last day. */
    func range(today: Day) -> (Day, Day)? {
        switch date {
        case .thisMonth: return (Day(today.year, today.month, 1)!, Day.endOfMonth(today.year, today.month)!)
        case .lastMonth:
            let m = today.month == 1 ? 12 : today.month - 1, y = today.month == 1 ? today.year - 1 : today.year
            return (Day(y, m, 1)!, Day.endOfMonth(y, m)!)
        case .thisYear: return (Day(today.year, 1, 1)!, Day(today.year, 12, 31)!)
        case .lastYear: return (Day(today.year - 1, 1, 1)!, Day(today.year - 1, 12, 31)!)
        case .custom: return (from ?? Day(1, 1, 1)!, to ?? Day(9999, 12, 31)!)
        case nil: return nil
        }
    }

    func matches(_ d: Paper, today: Day, dayFirst: Bool = true) -> Bool {
        if let (first, last) = range(today: today) {
            let day = Filters.paperDay(d)
            if day < first || day > last { return false }
        }
        if minCents != nil || maxCents != nil {
            guard let total = d.totalCents else { return false }
            if let minCents, total < minCents { return false }
            if let maxCents, total > maxCents { return false }
        }
        if !vendors.isEmpty && !vendors.contains(d.vendor) { return false }
        if missingInfo && d.totalCents != nil && d.date != nil && !d.vendor.isEmpty { return false }
        if duplicates && d.duplicateOf == nil { return false }
        if expiring && !Expiry.isSoon(d.details, today: today, dayFirst: dayFirst) { return false }
        if unread && !((d.aiState.isEmpty || d.aiState == aiFailed) && d.title.isEmpty && d.vendor.isEmpty && d.totalCents == nil) { return false }
        return true
    }

    func sorted(_ docs: [Paper]) -> [Paper] {
        switch sort {
        case .newest: return docs.sorted { Filters.paperDay($0) > Filters.paperDay($1) }
        case .oldest: return docs.sorted { Filters.paperDay($0) < Filters.paperDay($1) }
        // Papers without an amount last either way.
        case .amountHigh: return docs.sorted { a, b in a.totalCents == nil ? false : b.totalCents == nil ? true : a.totalCents! > b.totalCents! }
        case .amountLow: return docs.sorted { a, b in a.totalCents == nil ? false : b.totalCents == nil ? true : a.totalCents! < b.totalCents! }
        case .vendor: return docs.sorted { a, b in a.vendor.isEmpty ? false : b.vendor.isEmpty ? true : a.vendor.lowercased() < b.vendor.lowercased() }
        case nil: return docs
        }
    }

    static func paperDay(_ d: Paper) -> Day { Day(iso: d.date) ?? Day.fromEpochMs(d.createdAt) }
}
