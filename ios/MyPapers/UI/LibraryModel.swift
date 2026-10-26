import Foundation
import Observation

/** How the library lists papers; picked in the view switcher and remembered. */
enum ListStyle: String, CaseIterable, Identifiable {
    case cards, compact, grid, timeline
    var id: String { rawValue }

    var label: String {
        switch self {
        case .cards: "Cards"
        case .compact: "Compact list"
        case .grid: "Thumbnails"
        case .timeline: "Timeline by month"
        }
    }

    var symbol: String {
        switch self {
        case .cards: "rectangle.grid.1x2"
        case .compact: "list.bullet"
        case .grid: "square.grid.2x2"
        case .timeline: "calendar"
        }
    }
}

/** The library screen's state: search, chips, filters, the view and the selection. */
@MainActor @Observable
final class LibraryModel {
    var query = ""
    var selectedTags: Set<String> = []
    var selectedTypes: Set<String> = []
    /** The sort is kept from the last time. */
    var filters = Filters(sort: UserDefaults.standard.string(forKey: "sort").flatMap(SortBy.init(rawValue:))) {
        didSet { if filters.sort != oldValue.sort { UserDefaults.standard.set(filters.sort?.rawValue, forKey: "sort") } }
    }
    var style = UserDefaults.standard.string(forKey: "view").flatMap(ListStyle.init(rawValue:)) ?? .cards {
        didSet { UserDefaults.standard.set(style.rawValue, forKey: "view") }
    }
    /** Papers picked with a long press (selection mode while not empty). */
    var selection: Set<String> = []
    var selecting: Bool { !selection.isEmpty }

    /** Search, chips or filters are narrowing the list. */
    var narrowed: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty || !selectedTags.isEmpty || !selectedTypes.isEmpty || filters.activeCount > 0
    }

    func results(_ all: [Paper]) -> [Paper] {
        let today = Day.today
        let dayFirst = Format.dayFirst()
        let filtered = all.filter { (selectedTypes.isEmpty || selectedTypes.contains($0.docType)) && filters.matches($0, today: today, dayFirst: dayFirst) }
        return filters.sorted(FuzzySearch.search(filtered, query, tags: selectedTags, locale: Format.locale))
    }

    /** Every tag in use, most used first. */
    func tags(_ all: [Paper]) -> [String] {
        let counts = Dictionary(all.flatMap(\.tags).map { ($0, 1) }, uniquingKeysWith: +)
        return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.map(\.key)
    }

    /** The paper types in use, in the usual order. */
    func types(_ all: [Paper]) -> [String] {
        let used = Set(all.map(\.docType))
        return documentTypes.filter(used.contains)
    }

    /** Vendors in use, most papers first. */
    func vendors(_ all: [Paper]) -> [String] {
        let counts = Dictionary(all.map(\.vendor).filter { !$0.isEmpty }.map { ($0, 1) }, uniquingKeysWith: +)
        return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.map(\.key)
    }

    func toggle(_ id: String) {
        if selection.contains(id) { selection.remove(id) } else { selection.insert(id) }
    }

    /** Papers by month of their date (the day added when there's none). */
    func months(_ papers: [Paper]) -> [(year: Int, month: Int, papers: [Paper])] {
        let oldestFirst = filters.sort == .oldest
        let byDay = papers.sorted { oldestFirst ? Filters.paperDay($0) < Filters.paperDay($1) : Filters.paperDay($0) > Filters.paperDay($1) }
        var out: [(year: Int, month: Int, papers: [Paper])] = []
        for p in byDay {
            let d = Filters.paperDay(p)
            if let last = out.last, last.year == d.year, last.month == d.month {
                out[out.count - 1].papers.append(p)
            } else {
                out.append((d.year, d.month, [p]))
            }
        }
        return out
    }
}
