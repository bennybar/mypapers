import BackgroundTasks
import Foundation
import Observation
import SwiftData
import UIKit
import UserNotifications

/**
 * Finding papers in Gmail and bringing in the ones the user picks. Scans and imports keep going for
 * a while when the app is left (a background task); the daily sweep is a background refresh. The AI
 * never runs on what's found: an import is a plain new paper.
 */
@MainActor @Observable
final class GmailImporter {
    static let sweepTaskId = "com.bennybar.mypapers.gmail"
    /** Scanned per batch: the emails fetched, then marked seen together. */
    private static let batch = 50

    /** The one the app made (for the background sweep, which starts before any screen). */
    static weak var current: GmailImporter?

    let settings: GmailSettings
    @ObservationIgnored private let store: PaperStore
    @ObservationIgnored private let api: GmailAPI
    @ObservationIgnored private var context: ModelContext { store.context }

    /** A scan is running. */
    private(set) var scanning = false
    /** Picked papers are being added. */
    private(set) var importing = false
    /** True while a scan or an import is running (not the idle daily sweep). */
    var busy: Bool { scanning || importing }
    /** The scan's or the import's progress; total 0: not known yet. */
    private(set) var progress: (done: Int, total: Int)?
    /** Why the last scan (or import) failed, if it did. */
    private(set) var scanError: String?
    /** What's waiting for a decision (and what failed, to retry, and what's being added), newest email first. */
    private(set) var candidates: [GmailCandidate] = []
    /** Found and not decided on yet. */
    var newCount: Int { candidates.filter { $0.state == GmailCandidate.stateNew }.count }

    @ObservationIgnored private var scanTask: Task<Void, Never>?
    @ObservationIgnored private var importTask: Task<Void, Never>?

    /** [token]: an access token with gmail.readonly for the connected account. */
    init(store: PaperStore, settings: GmailSettings = GmailSettings(), token: @escaping @Sendable () async throws -> String) {
        self.store = store
        self.settings = settings
        api = GmailAPI(token: token)
        refresh()
        GmailImporter.current = self
        // Picked before the app was closed: carry on adding them.
        if candidates.contains(where: { $0.state == GmailCandidate.stateQueued }) { startImport() }
    }

    /** For tests: another API (a stand-in server). */
    init(store: PaperStore, settings: GmailSettings, api: GmailAPI) {
        self.store = store
        self.settings = settings
        self.api = api
        refresh()
    }

    // MARK: Scanning

    /** Looks through Gmail now ("Scan now", "Look again"). Already scanning: nothing. */
    func scanNow() {
        guard settings.isConnected, scanTask == nil else { return }
        scanTask = Task {
            await runScan(notify: false)
            scanTask = nil
        }
    }

    /** Stops a scan; what it fetched so far is kept, and the next scan resumes from there. */
    func cancelScan() { scanTask?.cancel() }

    /** The daily sweep: a scan, and a notification when it found something. Returns whether it worked. */
    @discardableResult
    func sweep() async -> Bool {
        guard settings.isConnected else { return true }
        if let running = scanTask { await running.value; return scanError == nil }
        return await runScan(notify: true)
    }

    @discardableResult
    private func runScan(notify: Bool) async -> Bool {
        scanning = true
        scanError = nil
        let background = UIApplication.shared.beginBackgroundTask(withName: "Gmail scan")
        defer {
            scanning = false
            progress = nil
            UIApplication.shared.endBackgroundTask(background)
        }
        do {
            let found = try await scan()
            if found > 0 && notify { GmailImporter.notifyFound(found) }
            scheduleDailySweep()
            return true
        } catch is CancellationError {
            return false
        } catch {
            scanError = error.localizedDescription
            if (error as? GmailError)?.needsReconnect == true { GmailImporter.notifyReconnect() }
            return false
        }
    }

    /**
     * Looks through Gmail: only mail since the last completed scan (the daily sweep, "Scan again"), or
     * the whole months-back window the first time and once more when it's widened. Emails already looked
     * at aren't fetched again, so an interrupted scan resumes where it stopped. Returns how many new things it found.
     */
    func scan() async throws -> Int {
        guard settings.isConnected else { return 0 }
        let startedAt = Clock.nowMs
        let start = ContinuousClock.now
        let monthsBack = settings.monthsBack
        let window = GmailAPI.scanWindow(lastScanAt: settings.lastScanAt, coveredMonths: settings.coveredMonths, monthsBack: monthsBack)
        progress = (0, 0)
        let seen = Set(((try? context.fetch(FetchDescriptor<GmailSeen>())) ?? []).map(\.gmailId))
        let withAttachments = try await api.search(GmailAPI.attachmentQuery(window))
        let attachmentIds = Set(withAttachments)
        let receipts = try await api.search(GmailAPI.receiptQuery(window)).filter { !attachmentIds.contains($0) }
        let all = (withAttachments.map { ($0, false) } + receipts.map { ($0, true) }).filter { !seen.contains($0.0) }
        var added = 0
        for start in stride(from: 0, to: all.count, by: GmailImporter.batch) {
            try Task.checkCancellation()
            let batch = Array(all[start..<min(start + GmailImporter.batch, all.count)])
            progress = (start, all.count)
            let emailsToo = Dictionary(batch, uniquingKeysWith: { a, _ in a })
            let messages = try await api.messages(batch.map(\.0))
            let found = messages.flatMap { m in
                GmailImporter.candidates(from: m, emailIfNoAttachments: emailsToo[m["id"] as? String ?? ""] == true, now: startedAt)
            }
            added += insertNew(found)
            // Only now: an interrupted scan picks this batch up again next time.
            for (id, _) in batch { context.insert(GmailSeen(gmailId: id, seenAt: startedAt)) }
            try? context.save()
            refresh()
        }
        progress = (all.count, all.count)
        // Only now, after the whole scan: an interrupted one runs again (skipping what it fetched).
        settings.lastScanAt = startedAt
        let took = ContinuousClock.now - start
        settings.lastScanTook = took.components.seconds * 1000 + took.components.attoseconds / 1_000_000_000_000_000
        settings.coveredMonths = max(settings.coveredMonths, monthsBack)
        return added
    }

    /** Keeps what's already known (and its state): a rescan doesn't bring back dismissed items. Returns how many are new. */
    private func insertNew(_ found: [GmailCandidate]) -> Int {
        let known = Set(((try? context.fetch(FetchDescriptor<GmailCandidate>())) ?? []).map(\.key))
        var added = 0
        var keys = known
        for c in found where keys.insert(c.key).inserted {
            context.insert(c)
            added += 1
        }
        try? context.save()
        return added
    }

    /** The email's attachments worth offering, or, when it has none, the email itself (a receipt written in it). */
    static func candidates(from m: GmailJSON, emailIfNoAttachments: Bool, now: Int64) -> [GmailCandidate] {
        guard let id = m["id"] as? String, let payload = m["payload"] as? [String: Any] else { return [] }
        let subjectHeader = (GmailAPI.header(payload, "Subject") ?? "").trimmingCharacters(in: .whitespaces)
        let subject = subjectHeader.isEmpty ? "(no subject)" : subjectHeader
        let sender = GmailAPI.senderName(GmailAPI.header(payload, "From") ?? "")
        let sentAt = Int64(m["internalDate"] as? String ?? "") ?? 0
        func candidate(_ kind: String, _ partId: String, _ name: String, _ type: String, _ size: Int64) -> GmailCandidate {
            GmailCandidate(key: "\(id):\(partId)", gmailId: id, subject: subject, sender: sender, sentAt: sentAt, kind: kind,
                           partId: partId, fileName: name, mimeType: type, size: size, foundAt: now)
        }
        let attachments = GmailAPI.paperParts(m).map { candidate(GmailCandidate.kindAttachment, $0.partId, $0.fileName, $0.mimeType, $0.size) }
        if !attachments.isEmpty { return attachments }
        guard emailIfNoAttachments else { return [] }
        let size = (m["sizeEstimate"] as? NSNumber)?.int64Value ?? Int64(m["sizeEstimate"] as? String ?? "") ?? 0
        return [candidate(GmailCandidate.kindEmail, "", safeFileName(subject) + ".pdf", "application/pdf", size)]
    }

    static func safeFileName(_ s: String) -> String {
        let t = s.replacingOccurrences(of: "[\\\\/:*?\"<>|]", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        let cut = String(t.prefix(80))
        return cut.trimmingCharacters(in: .whitespaces).isEmpty ? "Email" : cut
    }

    // MARK: Deciding and importing

    func importCandidate(_ c: GmailCandidate) { queue([c.key]) }

    /** Everything waiting (new and failed). */
    func importAll() { queue(candidates.filter { $0.state != GmailCandidate.stateQueued }.map(\.key)) }

    /** Adds these (by key), one by one. Already adding: they're picked up before it ends. */
    func queue(_ keys: [String]) {
        setStates(keys, GmailCandidate.stateQueued)
        startImport()
    }

    /** "Not papers": not offered again. */
    func dismiss(_ c: GmailCandidate) { dismiss([c.key]) }

    func dismiss(_ keys: [String]) { setStates(keys, GmailCandidate.stateDismissed) }

    private func setStates(_ keys: [String], _ state: String) {
        let set = Set(keys)
        for c in (try? context.fetch(FetchDescriptor<GmailCandidate>())) ?? [] where set.contains(c.key) {
            c.state = state
            if state == GmailCandidate.stateQueued { c.error = nil }
        }
        try? context.save()
        refresh()
    }

    private func startImport() {
        guard importTask == nil else { return }
        importTask = Task {
            importing = true
            let background = UIApplication.shared.beginBackgroundTask(withName: "Gmail import")
            do {
                try await importQueued()
            } catch {
                scanError = error.localizedDescription
                if (error as? GmailError)?.needsReconnect == true { GmailImporter.notifyReconnect() }
            }
            UIApplication.shared.endBackgroundTask(background)
            importing = false
            progress = nil
            importTask = nil
        }
    }

    /** Brings in what was picked, one by one, until none is left (papers picked meanwhile too). */
    func importQueued() async throws {
        while true {
            let queued = ((try? context.fetch(FetchDescriptor<GmailCandidate>())) ?? [])
                .filter { $0.state == GmailCandidate.stateQueued }
                .sorted { $0.sentAt < $1.sentAt }
            if queued.isEmpty { return }
            for (i, c) in queued.enumerated() {
                try Task.checkCancellation()
                progress = (i, queued.count)
                let file = FileManager.default.temporaryDirectory.appending(path: "gmail-import-\(UUID().uuidString)")
                defer { try? FileManager.default.removeItem(at: file) }
                do {
                    let message = try await api.message(c.gmailId)
                    if c.kind == GmailCandidate.kindEmail {
                        try await HTMLToPDF.render(try await api.emailHTML(gmailId: c.gmailId, message: message), to: file)
                    } else {
                        guard let part = GmailAPI.partById(message, c.partId) else { throw GmailError(message: "The attachment is no longer in this email") }
                        try await api.savePart(gmailId: c.gmailId, part: part, to: file)
                    }
                    // A plain new paper: the AI never runs on Gmail finds.
                    switch try store.importFile(file, name: c.fileName, autoRead: false) {
                    case .added(let id), .duplicate(let id): c.documentId = id
                    }
                    c.state = GmailCandidate.stateImported
                    c.error = nil
                } catch let e as GmailError where e.needsReconnect {
                    throw e
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    c.state = GmailCandidate.stateFailed
                    c.error = error.localizedDescription
                }
                try? context.save()
                refresh()
            }
            progress = (queued.count, queued.count)
        }
    }

    // MARK: Connection

    /** Another mailbox or signed out: forget what the old one found. */
    func reset() {
        try? context.delete(model: GmailCandidate.self)
        try? context.delete(model: GmailSeen.self)
        try? context.save()
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: GmailImporter.sweepTaskId)
        refresh()
    }

    /** Disconnects Gmail: no more scans, sweeps or imports, and nothing found is kept. */
    func disconnect() {
        scanTask?.cancel()
        importTask?.cancel()
        reset()
        settings.setEmail(nil)
        scanError = nil
    }

    /** Re-reads the waiting list. */
    func refresh() {
        let pending: Set<String> = [GmailCandidate.stateNew, GmailCandidate.stateFailed, GmailCandidate.stateQueued]
        candidates = ((try? context.fetch(FetchDescriptor<GmailCandidate>())) ?? [])
            .filter { pending.contains($0.state) }
            .sorted { $0.sentAt != $1.sentAt ? $0.sentAt > $1.sentAt : $0.key < $1.key }
    }

    // MARK: The daily sweep

    /** The daily look for new papers, when on and after the first scan (a day after the last one). */
    func scheduleDailySweep() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: GmailImporter.sweepTaskId)
        guard settings.isConnected, settings.dailySweep, settings.lastScanAt > 0 else { return }
        let request = BGAppRefreshTaskRequest(identifier: GmailImporter.sweepTaskId)
        let next = Date(timeIntervalSince1970: Double(settings.lastScanAt) / 1000).addingTimeInterval(24 * 60 * 60)
        request.earliestBeginDate = max(next, Date().addingTimeInterval(60 * 60))
        try? BGTaskScheduler.shared.submit(request)
    }

    /** To call before the app finishes launching (iOS requires it then). */
    static func registerBackgroundTask() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: sweepTaskId, using: nil) { task in
            nonisolated(unsafe) let task = task
            let work = Task { @MainActor in
                let ok = await GmailImporter.current?.sweep() ?? true
                // A sweep that didn't run still asks for the next one.
                GmailImporter.current?.scheduleDailySweep()
                task.setTaskCompleted(success: ok)
            }
            task.expirationHandler = { work.cancel() }
        }
    }

    // MARK: Notifications

    /** "3 new papers in Gmail": tap to choose what to add (userInfo open = gmail). */
    static func notifyFound(_ count: Int) {
        let content = UNMutableNotificationContent()
        content.title = count == 1 ? "1 new paper in Gmail" : "\(count) new papers in Gmail"
        content.body = "Tap to choose what to add"
        content.userInfo = ["open": "gmail"]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "gmail.found", content: content, trigger: nil))
    }

    /** Google stopped accepting the app's Gmail access: connect again in Settings (userInfo open = settings). */
    static func notifyReconnect() {
        let content = UNMutableNotificationContent()
        content.title = "Reconnect Gmail"
        content.body = "My Papers can't read Gmail anymore. Tap to connect again."
        content.userInfo = ["open": "settings"]
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "gmail.reconnect", content: content, trigger: nil))
    }
}
