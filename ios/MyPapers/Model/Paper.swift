import Foundation
import SwiftData

/** One labeled fact on a paper: "Right eye" → "SPH −1.25 · CYL −0.50 · AXIS 90". [asked]: found by Ask AI and saved; a re-read keeps it. */
struct KeyDetail: Codable, Hashable, Sendable {
    var label: String
    var value: String
    var asked: Bool = false
}

let aiPending = "pending"
let aiDone = "done"
let aiFailed = "failed"

/** The paper types the AI picks from. */
let documentTypes = ["Receipt", "Invoice", "Warranty", "Contract", "Other"]

/**
 * A receipt or document, as on Android (same fields, same JSON in Google Drive); the rest is local
 * sync bookkeeping. Kept with SwiftData; the file itself is in [PaperFiles].
 */
@Model
final class Paper {
    @Attribute(.unique) var id: String
    /** A short, clear name ("019 Mobile bill, September 2026"), from the AI or typed; "" = none. */
    var title: String = ""
    var vendor: String = ""
    /** Receipt, Invoice, Warranty, Contract, Other; blank until set. */
    var docType: String = ""
    /** ISO date, yyyy-MM-dd. */
    var date: String?
    /** Amounts in hundredths of the currency unit (₪12.50 = 1250). */
    var totalCents: Int64?
    /** ISO 4217 code, e.g. ILS. */
    var currency: String?
    var vatCents: Int64?
    var tags: [String] = []
    var notes: String = ""
    /** The document's text as read by the AI, for search (the first 1500 characters). */
    var extractedText: String = ""
    /** All of its text (a PDF's own, else the AI's reading), for questions across papers; not searched. */
    var fullText: String = ""
    /** The whole paper written out by the AI on request ("Extract all text"), as Markdown. */
    var transcript: String = ""
    var details: [KeyDetail] = []
    /** A few search words from the AI, in English and in the paper's language. */
    var keywords: [String] = []
    /** SHA-256 of the file, for duplicate detection. */
    var fileHash: String?
    var fileName: String?
    var mimeType: String?
    var createdAt: Int64
    /** Last edit (epoch ms): the newer side wins a sync conflict. */
    var updatedAt: Int64
    /** AI reading: "" (not asked), pending, done or failed. */
    var aiState: String = ""
    var aiError: String?
    /** Another paper this one seems to repeat, until kept or deleted. */
    var duplicateOf: String?
    /** In the trash since (epoch ms); null = not. */
    var trashedAt: Int64?
    /** Fields corrected by hand ("title", "total", "details"…): the AI leaves them as they are. */
    var edited: [String] = []

    // Sync bookkeeping (local only).
    /** The Drive id of its `.json` file; null = never pushed. */
    var rev: String?
    /** Has local changes not yet in Drive. */
    var dirty: Bool = true
    /** Deleted for good here, waiting to push the deletion. */
    var deleted: Bool = false
    /** Drive has this paper's current file. */
    var fileUploaded: Bool = false
    /** The file is on this phone. */
    var fileLocal: Bool = false

    init(id: String = UUID().uuidString.lowercased(), createdAt: Int64 = Clock.nowMs, updatedAt: Int64 = Clock.nowMs) {
        self.id = id
        self.createdAt = createdAt
        self.updatedAt = updatedAt
    }

    /** Shown in the library: not deleted, not in the trash. */
    var isLive: Bool { !deleted && trashedAt == nil }
}

enum Clock {
    static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }
}
