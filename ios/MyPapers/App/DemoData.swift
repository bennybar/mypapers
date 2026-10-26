#if DEBUG
import Foundation

/** Made-up papers for simulator screenshots (`-demo`). Never used on a phone with a real library. */
enum DemoData {
    @MainActor
    static func load(into store: PaperStore) {
        guard store.all().isEmpty else { return }
        func paper(_ title: String, _ vendor: String, _ type: String, _ date: String, _ cents: Int64?, _ tags: [String], details: [(String, String)] = [], daysAgo: Int64) {
            let p = Paper(createdAt: Clock.nowMs - daysAgo * 86_400_000, updatedAt: Clock.nowMs)
            p.title = title; p.vendor = vendor; p.docType = type; p.date = date
            p.totalCents = cents; p.currency = cents == nil ? nil : "ILS"; p.tags = tags
            p.details = details.map { KeyDetail(label: $0.0, value: $0.1) }
            p.aiState = aiDone; p.dirty = false; p.fileUploaded = true
            store.insert(p)
        }
        paper("Shufersal groceries", "Shufersal", "Receipt", "2026-10-12", 31240, ["Groceries"], daysAgo: 1)
        paper("019 Mobile bill, September 2026", "019 Mobile", "Invoice", "2026-10-01", 4990, ["Bills"], details: [("Account", "55120"), ("Due date", "2026-11-05")], daysAgo: 3)
        paper("Washing machine warranty", "Machsanei Hashmal", "Warranty", "2026-09-28", 249900, ["Home"], details: [("Serial", "WX-55821"), ("Valid until", "2026-12-01")], daysAgo: 0)
        paper("Car insurance 2026", "Harel", "Contract", "2026-09-15", 241000, ["Car"], details: [("Policy", "HR-2210-77"), ("Renewal date", "2027-09-15")], daysAgo: 9)
        paper("עבודה זוגית – שלב א", "", "Other", "2026-10-01", nil, ["Education"], daysAgo: 2)
        paper("Super-Pharm", "Super-Pharm", "Receipt", "2026-09-27", 8600, ["Health"], daysAgo: 12)
        paper("IKEA", "IKEA", "Receipt", "2026-09-02", 112900, ["Home"], daysAgo: 30)
        paper("Electric bill, August", "Israel Electric", "Invoice", "2026-08-30", 38820, ["Bills"], daysAgo: 40)
    }
}
#endif
