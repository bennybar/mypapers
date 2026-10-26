import Foundation

/**
 * Amounts and dates in the chosen region's format (Settings → Dates and amounts; the phone's
 * by default).
 */
enum Format {
    /** The region chosen in Settings; nil = the phone's. */
    nonisolated(unsafe) static var regionTag: String? = UserDefaults.standard.string(forKey: "region")

    static var locale: Locale {
        if let tag = regionTag, !tag.isEmpty { return Locale(identifier: tag) }
        return Locale.current
    }

    /** The default currency for new papers, from the region (₪ for Israel). */
    static func defaultCurrency() -> String { locale.currency?.identifier ?? "" }

    static func amount(_ cents: Int64, _ currency: String?) -> String {
        let value = Decimal(cents) / 100
        let f = NumberFormatter()
        f.locale = locale
        if let c = currency, !c.isEmpty, Locale.commonISOCurrencyCodes.contains(c) {
            f.numberStyle = .currency
            f.currencyCode = c
        } else {
            f.numberStyle = .decimal
            f.minimumFractionDigits = 2
            f.maximumFractionDigits = 2
        }
        return f.string(from: value as NSDecimalNumber) ?? "\(value)"
    }

    /** For an edit field: the number without the currency, e.g. "1,249.00". */
    static func amountInput(_ cents: Int64?) -> String {
        guard let cents else { return "" }
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.minimumFractionDigits = 2
        f.maximumFractionDigits = 2
        return f.string(from: (Decimal(cents) / 100) as NSDecimalNumber) ?? ""
    }

    /** "1,249.5" (or "1.249,5" where that's the format) → 124950; nil if it isn't a number. */
    static func parseAmount(_ text: String) -> Int64? {
        let t = text.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return nil }
        let f = NumberFormatter()
        f.locale = locale
        f.numberStyle = .decimal
        f.generatesDecimalNumbers = true
        guard let n = f.number(from: t) as? NSDecimalNumber else { return nil }
        return n.multiplying(byPowerOf10: 2).int64Value
    }

    static let isoDay: DateFormatter = {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = .current
        f.dateFormat = "yyyy-MM-dd"
        return f
    }()

    /** "12 Oct 2026" for 2026-10-12, in the region's style; nil if it isn't a date. */
    static func date(_ iso: String?) -> String? {
        guard let iso, let d = isoDay.date(from: iso) else { return nil }
        return d.formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(locale))
    }

    static func day(_ epochMs: Int64) -> String {
        Date(timeIntervalSince1970: Double(epochMs) / 1000).formatted(Date.FormatStyle(date: .abbreviated, time: .omitted).locale(locale))
    }

    static func dateTime(_ d: Date) -> String {
        d.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(locale))
    }

    /** Day before month in the region's short dates (03/04 = 3 April)? */
    static func dayFirst(_ locale: Locale = Format.locale) -> Bool {
        let pattern = DateFormatter.dateFormat(fromTemplate: "ddMMyyyy", options: 0, locale: locale) ?? "dd/MM/yyyy"
        guard let d = pattern.firstIndex(of: "d"), let m = pattern.firstIndex(of: "M") else { return true }
        return d < m
    }
}
