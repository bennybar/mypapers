import Foundation
import Observation
import PDFKit
import SwiftData

enum ImportResult: Sendable {
    case added(String)
    /** The same file is already here. */
    case duplicate(String)
}

/** How long a paper stays in the trash before it's deleted for good. */
let trashDays = 30

/**
 * Every change to the papers goes through here (like Android's DocumentRepository): import, edits,
 * the trash, AI bookkeeping. Each local change is marked for sync and announced via [onChanged].
 */
@MainActor @Observable
final class PaperStore {
    let context: ModelContext
    /** After every local change: schedules a sync. */
    @ObservationIgnored var onChanged: () -> Void = {}
    /** A paper to be read by the AI in the background (automatic reading on). */
    @ObservationIgnored var onNeedsReading: (String) -> Void = { _ in }
    /** Bumped on every change, so views showing derived lists refresh. */
    private(set) var version = 0

    init(context: ModelContext) {
        self.context = context
    }

    // MARK: Reading

    /** Not deleted and not in the trash. */
    func all() -> [Paper] {
        (try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { !$0.deleted && $0.trashedAt == nil }))) ?? []
    }

    func trash() -> [Paper] {
        ((try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { !$0.deleted && $0.trashedAt != nil }))) ?? [])
            .sorted { ($0.trashedAt ?? 0) > ($1.trashedAt ?? 0) }
    }

    /** Any row, deleted ones too. */
    func get(_ id: String) -> Paper? {
        try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { $0.id == id })).first
    }

    /** The live (not deleted) one. */
    func live(_ id: String) -> Paper? { get(id).flatMap { $0.deleted ? nil : $0 } }

    func byHash(_ hash: String) -> Paper? {
        try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { !$0.deleted && $0.fileHash == hash })).first
    }

    func byRev(_ rev: String) -> Paper? {
        try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { $0.rev == rev })).first
    }

    func dirty() -> [Paper] {
        (try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { $0.dirty }))) ?? []
    }

    /** In Drive, not on this phone yet. */
    func missingFiles() -> [Paper] {
        (try? context.fetch(FetchDescriptor<Paper>(predicate: #Predicate { !$0.deleted && $0.fileUploaded && !$0.fileLocal }))) ?? []
    }

    // MARK: Changing

    /** Saves a local change to [p]: newer than anything elsewhere, and to be pushed. */
    func update(_ p: Paper) {
        p.updatedAt = Clock.nowMs
        p.dirty = true
        save()
        onChanged()
    }

    /** Saves without marking for sync (sync's own bookkeeping). */
    func save() {
        try? context.save()
        version += 1
    }

    func insert(_ p: Paper) {
        context.insert(p)
        save()
    }

    /** The row itself gone (after its deletion reached Drive). */
    func remove(_ p: Paper) {
        context.delete(p)
        save()
    }

    /** Copies a picked, scanned or shared file in; the same file twice is a duplicate. */
    func importFile(_ source: URL, name: String? = nil, autoRead: Bool) throws -> ImportResult {
        let access = source.startAccessingSecurityScopedResource()
        defer { if access { source.stopAccessingSecurityScopedResource() } }
        let temp = PaperFiles.temp()
        try FileManager.default.copyItem(at: source, to: temp)
        let hash = try PaperFiles.sha256(temp)
        if let existing = byHash(hash) {
            try? FileManager.default.removeItem(at: temp)
            // The same file, in the trash: brought back rather than added twice.
            if existing.trashedAt != nil {
                existing.trashedAt = nil
                update(existing)
                return .added(existing.id)
            }
            return .duplicate(existing.id)
        }
        let p = Paper()
        try FileManager.default.moveItem(at: temp, to: PaperFiles.url(p.id))
        p.fileHash = hash
        p.fileName = name ?? source.lastPathComponent
        p.mimeType = PaperFiles.sniffMimeType(PaperFiles.url(p.id))
        p.fullText = p.mimeType == "application/pdf" ? PdfText.read(PaperFiles.url(p.id)) : ""
        p.fileLocal = true
        p.aiState = autoRead ? aiPending : ""
        insert(p)
        onChanged()
        if autoRead { onNeedsReading(p.id) }
        return .added(p.id)
    }

    /** To the trash: hidden everywhere, restorable for [trashDays] days. */
    func moveToTrash(_ id: String) {
        guard let p = live(id), p.trashedAt == nil else { return }
        p.trashedAt = Clock.nowMs
        update(p)
    }

    func restore(_ id: String) {
        guard let p = live(id) else { return }
        p.trashedAt = nil
        update(p)
    }

    /** Deletes for good, here and in Drive (kept as a deleted row until the sync pushes it). */
    func delete(_ id: String) {
        guard let p = get(id) else { return }
        p.deleted = true
        update(p)
        PaperFiles.delete(id)
    }

    func emptyTrash() { trash().forEach { delete($0.id) } }

    /** What has been in the trash longer than [trashDays] days goes for good. */
    func purgeTrash() {
        let cutoff = Clock.nowMs - Int64(trashDays) * 24 * 60 * 60 * 1000
        trash().filter { ($0.trashedAt ?? 0) < cutoff }.forEach { delete($0.id) }
    }

    /** Papers from before the full text was kept: a PDF's own text, read on the phone (no AI). */
    func fillFullText() {
        for p in all() where p.fullText.isEmpty && p.fileLocal && PaperFiles.isPDF(p) {
            let text = PdfText.read(PaperFiles.url(p.id))
            if !text.isEmpty { p.fullText = text; update(p) }
        }
    }

    // MARK: AI bookkeeping

    /** Never read (or the reading failed) and nothing filled in by hand. */
    func isUnread(_ p: Paper) -> Bool {
        (p.aiState.isEmpty || p.aiState == aiFailed) && p.fileLocal && p.title.isEmpty && p.vendor.isEmpty && p.totalCents == nil
    }

    func unread() -> [Paper] { all().filter(isUnread).sorted { $0.createdAt > $1.createdAt } }

    func markReading(_ id: String) {
        guard let p = live(id) else { return }
        p.aiState = aiPending
        p.aiError = nil
        update(p)
    }

    /** Back to unread (the job stopped before reading it). */
    func markUnread(_ id: String) {
        guard let p = live(id), p.aiState == aiPending else { return }
        p.aiState = ""
        update(p)
    }

    func markFailed(_ id: String, _ message: String) {
        guard let p = live(id) else { return }
        p.aiState = aiFailed
        p.aiError = message
        update(p)
    }

    /** Back to how it was when added (everything read or typed cleared, corrections forgotten) and read again. Notes and the file stay. */
    func reset(_ id: String) {
        guard let p = live(id) else { return }
        p.title = ""; p.vendor = ""; p.docType = ""; p.date = nil
        p.totalCents = nil; p.currency = nil; p.vatCents = nil
        p.tags = []; p.extractedText = ""; p.transcript = ""; p.details = []; p.keywords = []
        p.duplicateOf = nil; p.edited = []; p.aiState = aiPending; p.aiError = nil
        update(p)
        onNeedsReading(id)
    }

    /** Adds [found] to the paper's key details, marked as asked; [replacing]: the detail they rework, replaced in its place. */
    func saveDetails(_ id: String, _ found: [KeyDetail], replacing: String? = nil) {
        guard let p = live(id) else { return }
        let asked = found.map { var d = $0; d.asked = true; return d }
        p.details = replacing.map { ExtractionRules.replaceDetail(p.details, label: $0, asked) } ?? ExtractionRules.mergeDetails(p.details, asked)
        update(p)
    }

    /** What the AI proposed, applied; changed fields count as the user's (a re-read leaves them). */
    func applyChanges(_ id: String, _ c: PaperChanges) {
        guard let p = live(id) else { return }
        var seen = Set<String>()
        let tags = (p.tags.filter { t in !c.removeTags.contains { $0.caseInsensitiveCompare(t) == .orderedSame } } + c.addTags)
            .filter { seen.insert($0.lowercased()).inserted }
        let details = p.details.filter { d in !c.removeDetails.contains { $0.caseInsensitiveCompare(d.label) == .orderedSame } }
        var edited: [String] = []
        if let v = c.title { p.title = v; edited.append("title") }
        if let v = c.vendor { p.vendor = v; edited.append("vendor") }
        if let v = c.docType { p.docType = v; edited.append("docType") }
        if let v = c.date { p.date = v; edited.append("date") }
        if let v = c.total { p.totalCents = Int64((v * 100).rounded()); edited.append("total") }
        if let v = c.currency { p.currency = v; edited.append("currency") }
        if tags != p.tags { p.tags = tags; edited.append("tags") }
        if details != p.details { p.details = details; edited.append("details") }
        p.edited = Array(Set(p.edited + edited)).sorted()
        update(p)
    }

    /** The paper's written-out text as edited ("" deletes it). */
    func saveTranscript(_ id: String, _ text: String) {
        guard let p = live(id) else { return }
        p.transcript = text.trimmingCharacters(in: .whitespacesAndNewlines)
        update(p)
    }

    /** Adds [tag] to these papers (kept once, whatever its case). */
    func addTag(_ ids: [String], _ tag: String) {
        let t = tag.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        for id in ids {
            guard let p = live(id), !p.tags.contains(where: { $0.caseInsensitiveCompare(t) == .orderedSame }) else { continue }
            p.tags.append(t)
            update(p)
        }
    }

    func setType(_ ids: [String], _ type: String) {
        for id in ids {
            guard let p = live(id), p.docType != type else { continue }
            p.docType = type
            update(p)
        }
    }

    /** Not a duplicate after all. */
    func keepBoth(_ id: String) {
        guard let p = live(id) else { return }
        p.duplicateOf = nil
        update(p)
    }
}

/** A PDF's own text, page after page, read on the phone: nothing sent anywhere. */
enum PdfText {
    /** Enough for a long contract; a paper's text is kept and synced with it. */
    static let maxChars = 30_000

    /** "" for a scan with no text in it, or a file that isn't a readable PDF. */
    static func read(_ url: URL) -> String {
        guard let doc = PDFDocument(url: url) else { return "" }
        var out = ""
        for i in 0..<doc.pageCount {
            if out.count >= maxChars { break }
            if let s = doc.page(at: i)?.string { out += s + "\n" }
        }
        return String(out.trimmingCharacters(in: .whitespacesAndNewlines).prefix(maxChars))
    }
}
