import Foundation

/**
 * The same paper brought in twice in different files (a scan and the emailed PDF): same amount and
 * date, and a vendor that reads the same. Papers with no amount (a prescription, a warranty): same
 * date, type and vendor, and no detail that differs. The identical file is caught earlier, by its
 * hash. Only the newer copy is flagged, so deleting the flagged one keeps the other.
 */
enum Duplicates {
    static func find(_ doc: Paper, among others: [Paper]) -> Paper? {
        guard let date = doc.date else { return nil }
        let vendor = FuzzySearch.normalize(doc.vendor)
        let candidates = others.filter { $0.id != doc.id && !$0.deleted && $0.date == date && $0.createdAt < doc.createdAt }
        if let total = doc.totalCents {
            return candidates
                .filter { $0.totalCents == total && sameVendor(vendor, FuzzySearch.normalize($0.vendor)) }
                .min { $0.createdAt < $1.createdAt }
        }
        if vendor.isEmpty { return nil }
        return candidates
            .filter { o in
                let v = FuzzySearch.normalize(o.vendor)
                return o.totalCents == nil && o.docType == doc.docType && !v.isEmpty && sameVendor(vendor, v)
            }
            .filter { sameDetails(doc.details, $0.details) }
            .min { $0.createdAt < $1.createdAt }
    }

    /** "shufersal" vs "shufersal deal", or a letter off; an empty vendor on either side counts as the same. */
    private static func sameVendor(_ a: String, _ b: String) -> Bool {
        if a.isEmpty || b.isEmpty || a == b { return true }
        if a.contains(b) || b.contains(a) { return true }
        return min(a.count, b.count) >= 5 && FuzzySearch.distance(a, b) <= 2
    }

    /** No detail with the same label and another value (one side without details: can't tell, so the same). */
    private static func sameDetails(_ a: [KeyDetail], _ b: [KeyDetail]) -> Bool {
        var values: [String: String] = [:]
        for d in b { values[FuzzySearch.normalize(d.label)] = FuzzySearch.normalize(d.value) }
        return !a.contains { d in values[FuzzySearch.normalize(d.label)].map { $0 != FuzzySearch.normalize(d.value) } ?? false }
    }
}
