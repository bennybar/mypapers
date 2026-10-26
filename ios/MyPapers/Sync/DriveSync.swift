import Foundation
import UIKit
import Observation

/** Where the change feed was last read, and which account this phone's papers were synced with. */
final class DriveSettings: @unchecked Sendable {
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    /** Drive's change-feed position; nil = read everything next time. */
    var pageToken: String? {
        get { defaults.string(forKey: "drive.pageToken") }
        set { defaults.set(newValue, forKey: "drive.pageToken") }
    }

    /** The account this phone's papers were last synced with. */
    var syncedEmail: String? {
        get { defaults.string(forKey: "drive.syncedEmail") }
        set { defaults.set(newValue, forKey: "drive.syncedEmail") }
    }

    /** When a sync last finished with everything sent up (epoch ms); 0 = not yet. */
    var lastSyncAt: Int64 {
        get { Int64(defaults.integer(forKey: "drive.lastSyncAt")) }
        set { defaults.set(Int(newValue), forKey: "drive.lastSyncAt") }
    }
}

enum SyncState: Equatable, Sendable {
    case idle
    case syncing
    /** Sending papers up: [done] of [total]. */
    case uploading(done: Int, total: Int)
    case failed(String)
}

/**
 * Two-way sync between the phone and the app's hidden folder in the user's own Google Drive, the same
 * way the Android app does it, so both see the same papers. Each paper is two files there: `<id>.json`
 * (its fields) and `<id>.file` (the PDF or photo). Read Drive's change feed, push local changes, then
 * fetch missing files. A conflict goes to the side edited last ([Paper.updatedAt], also kept on the
 * Drive file so our own uploads coming back aren't downloaded). A paper deleted for good has both files
 * removed; [Paper.rev] holds the id of its `.json` there.
 */
@MainActor @Observable
final class DriveSync {
    private(set) var state: SyncState = .idle
    /** When a sync last finished (epoch ms); 0 = not yet. */
    private(set) var lastSyncAt: Int64

    @ObservationIgnored private let store: PaperStore
    @ObservationIgnored private let settings: DriveSettings
    @ObservationIgnored private let fileURL: (String) -> URL
    /** nil while signed out. */
    @ObservationIgnored private let drive: () -> (any DriveStore)?
    /** The last of the queued syncs and idle-time blocks; the next one waits for it. */
    @ObservationIgnored private var tail: Task<Void, Never>?
    @ObservationIgnored private var pending: Task<Void, Never>?

    static let jsonSuffix = ".json"
    static let fileSuffix = ".file"

    init(store: PaperStore, settings: DriveSettings = DriveSettings(), fileURL: @escaping (String) -> URL = PaperFiles.url, drive: @escaping () -> (any DriveStore)?) {
        self.store = store
        self.settings = settings
        self.fileURL = fileURL
        self.drive = drive
        lastSyncAt = settings.lastSyncAt
    }

    /** Runs one full sync (a second caller waits for the running one, then syncs again). */
    func sync() async {
        // Leaving the app right after an edit still lets the upload finish (iOS gives ~30 s).
        let id = UIApplication.shared.beginBackgroundTask(withName: "Drive sync")
        defer { if id != .invalid { UIApplication.shared.endBackgroundTask(id) } }
        await whileIdle { await self.syncOnce() }
    }

    /** Runs [block] with no sync in progress (e.g. signing out, so a finishing sync can't write back); one after another, in order. */
    func whileIdle<T: Sendable>(_ block: @escaping @MainActor () async -> T) async -> T {
        let previous = tail
        let task = Task { @MainActor in
            await previous?.value
            return await block()
        }
        tail = Task { _ = await task.value }
        return await task.value
    }

    /** Syncs shortly after the last of a burst of local changes. */
    func requestSync(after seconds: Double = 2) {
        pending?.cancel()
        pending = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
            if Task.isCancelled { return }
            // Its own task: a newer request cancels only the wait, never a running sync.
            Task { @MainActor in await self.sync() }
        }
    }

    /**
     * Signed in, with Drive access allowed. An account new to this phone: read all of its Drive, and
     * upload what's here (papers added before signing in).
     */
    func connect(email: String) async {
        await whileIdle {
            if self.settings.syncedEmail != email {
                self.forget()
                for p in self.allRows() {
                    p.rev = nil
                    p.dirty = true
                    p.fileUploaded = false
                }
                self.store.save()
                self.settings.syncedEmail = email
            }
        }
        requestSync(after: 0)
    }

    /** Signed out: this account's papers leave the phone (they stay in its Drive). */
    func signedOut() async {
        await whileIdle {
            for p in self.allRows() { self.store.context.delete(p) }
            self.store.save()
            PaperFiles.deleteAll()
            self.forget()
            self.settings.syncedEmail = nil
        }
    }

    /** Forgets where the change feed was (another account, or signed out). */
    func reset() {
        forget()
        settings.syncedEmail = nil
    }

    private func forget() {
        settings.pageToken = nil
        settings.lastSyncAt = 0
        lastSyncAt = 0
        state = .idle
    }

    private func allRows() -> [Paper] { (try? store.context.fetch(.init(predicate: #Predicate<Paper> { _ in true }))) ?? [] }

    private func syncOnce() async {
        guard let drive = drive() else { return }
        state = .syncing
        do {
            try await pull(drive)
            try await push(drive)
            try await downloadFiles(drive)
            settings.lastSyncAt = Clock.nowMs
            lastSyncAt = settings.lastSyncAt
            state = .idle
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    private func pull(_ drive: any DriveStore) async throws {
        var token = settings.pageToken
        if token == nil {
            // First time with this account: everything there, then changes from before the listing on.
            let start = try await drive.startPageToken()
            for f in try await drive.listAll() where f.name.hasSuffix(DriveSync.jsonSuffix) { try await applyRemote(drive, f) }
            settings.pageToken = start
            token = start
        }
        while true {
            let page = try await drive.changes(token!)
            for c in page.changes {
                if let f = c.file {
                    if f.name.hasSuffix(DriveSync.jsonSuffix) { try await applyRemote(drive, f) }
                } else {
                    removedRemotely(c.fileId)
                }
            }
            token = page.next
            settings.pageToken = token
            if page.done { break }
        }
    }

    private func applyRemote(_ drive: any DriveStore, _ f: DriveFile) async throws {
        let id = String(f.name.dropLast(DriveSync.jsonSuffix.count))
        // Already have this edit (our own upload coming back), or a newer one of ours that the push sends.
        if let local = store.get(id), let remoteAt = f.updatedAt, local.updatedAt == remoteAt || (local.dirty && local.updatedAt > remoteAt) {
            if local.rev != f.id { local.rev = f.id; store.save() }
            return
        }
        let data = try await drive.read(f.id)
        let local = store.get(id)
        if let local, local.dirty, local.updatedAt >= PaperJSON.updatedAt(data) {
            local.rev = f.id
            store.save()
            return
        }
        // Our file never got there (another edit won the race): keep it, and upload it on the next push.
        if let local, local.fileLocal, !local.fileUploaded, !PaperJSON.hasFile(data) {
            let hash = local.fileHash, type = local.mimeType
            try PaperJSON.apply(data, to: local)
            local.fileHash = hash
            local.mimeType = type
            local.rev = f.id
            local.deleted = false
            local.fileLocal = true
            local.dirty = true
            store.save()
            return
        }
        // Keep the file we have if it's still the same one.
        let sameFile = local.map { $0.fileLocal && $0.fileHash == PaperJSON.fileHash(data) } ?? false
        if local != nil, !sameFile { try? FileManager.default.removeItem(at: fileURL(id)) }
        let p = local ?? Paper(id: id)
        try PaperJSON.apply(data, to: p)
        p.rev = f.id
        p.dirty = false
        p.deleted = false
        p.fileLocal = sameFile
        if local == nil { store.context.insert(p) }
        store.save()
    }

    /** A paper deleted for good on another device; an edit here since then brings it back. */
    private func removedRemotely(_ fileId: String) {
        guard let local = store.byRev(fileId) else { return }
        if local.dirty && !local.deleted {
            local.rev = nil
            store.save()
            return
        }
        try? FileManager.default.removeItem(at: fileURL(local.id))
        store.remove(local)
    }

    private func push(_ drive: any DriveStore) async throws {
        let ids = store.dirty().map(\.id)
        for (i, id) in ids.enumerated() {
            state = .uploading(done: i, total: ids.count)
            try await pushOne(drive, id)
        }
        state = .syncing
    }

    private func pushOne(_ drive: any DriveStore, _ id: String) async throws {
        guard let doc = store.get(id), doc.dirty else { return }
        let metaName = id + DriveSync.jsonSuffix
        let fileName = id + DriveSync.fileSuffix
        if doc.deleted {
            let rev = doc.rev
            let meta: String? = if let rev { rev } else { try await drive.find(metaName) }
            if let meta { try await drive.delete(meta) }
            if let file = try await drive.find(fileName) { try await drive.delete(file) }
            if let row = store.get(id) { store.remove(row) }
            return
        }
        var uploaded = doc.fileUploaded
        // The file first, so another device that sees the paper can fetch it. Gone if deleted meanwhile.
        let file = fileURL(id)
        if !uploaded, doc.fileLocal, FileManager.default.fileExists(atPath: file.path) {
            let type = doc.mimeType ?? "application/octet-stream"
            let existing = try await drive.find(fileName)
            _ = try await drive.put(existing, name: fileName, updatedAt: nil, content: .file(file, mimeType: type))
            uploaded = true
        }
        guard let doc = store.get(id), !doc.deleted else { return }
        let pushedAt = doc.updatedAt
        let json = DriveContent.data(PaperJSON.encode(doc, keepFile: uploaded), mimeType: DriveClient.json)
        let rev = doc.rev
        let metaId: String
        do {
            let target: String? = if let rev { rev } else { try await drive.find(metaName) }
            metaId = try await drive.put(target, name: metaName, updatedAt: pushedAt, content: json)
        } catch let e as DriveError where e.code == 404 {
            // Removed there meanwhile: write it anew.
            metaId = try await drive.put(nil, name: metaName, updatedAt: pushedAt, content: json)
        }
        // Edited meanwhile: stays to be pushed again.
        guard let after = store.get(id) else { return }
        after.rev = metaId
        after.fileUploaded = uploaded
        if after.updatedAt == pushedAt { after.dirty = false }
        store.save()
    }

    private func downloadFiles(_ drive: any DriveStore) async throws {
        for id in store.missingFiles().map(\.id) {
            guard let fileId = try await drive.find(id + DriveSync.fileSuffix) else { continue }
            let temp = fileURL(id).deletingLastPathComponent().appending(path: ".tmp-\(UUID().uuidString)")
            defer { try? FileManager.default.removeItem(at: temp) }
            try await drive.download(fileId, to: temp)
            let target = fileURL(id)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: temp, to: target)
            if let p = store.get(id) {
                p.fileLocal = true
                store.save()
            }
        }
    }
}
