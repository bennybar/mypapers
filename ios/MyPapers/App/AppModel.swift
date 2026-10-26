import PhotosUI
import SwiftData
import SwiftUI
import UserNotifications

/** Where the navigation stack can go. */
enum Route: Hashable {
    case paper(String)
    case settings
    case gmail
    case trash
}

/**
 * The app's parts wired together (like Android's PapersApp): the store, Google account and Drive
 * sync, the AI and its jobs, Gmail, the library's state and navigation.
 */
@MainActor @Observable
final class AppModel {
    static let appGroup = "group.com.bennybar.mypapers"
    static let gmailScope = "https://www.googleapis.com/auth/gmail.readonly"

    let container: ModelContainer
    let store: PaperStore
    let conversations = Conversations()
    let ai = AISettings()
    let service: AIService
    let jobs: AIJobs
    let account = GoogleAccount()
    let sync: DriveSync
    let gmail: GmailImporter
    let library = LibraryModel()

    var path: [Route] = []
    var namespace: Namespace.ID?
    /** A short message at the bottom ("Added", "Already in My Papers"). */
    var toastText: String?
    /** Files on their way to the share sheet. */
    var sharing: [URL]?

    init() {
        do {
            container = try ModelContainer(for: Paper.self, GmailCandidate.self, GmailSeen.self)
        } catch {
            fatalError("Can't open the papers' store: \(error)")
        }
        store = PaperStore(context: container.mainContext)
        service = AIService(store: store, settings: ai, client: OpenAIClient())
        jobs = AIJobs(service: service, conversations: conversations)
        let account = account
        let client = DriveClient(token: { try await account.driveToken() })
        sync = DriveSync(store: store, drive: { account.email == nil ? nil : client })
        gmail = GmailImporter(store: store, token: { try await account.accessToken(adding: [AppModel.gmailScope]) })
        store.onChanged = { [weak self] in
            self?.sync.requestSync()
            self?.scheduleReminders()
        }
        store.onNeedsReading = { [weak self] id in self?.jobs.read(id) }
    }

    /** At launch and each time the app comes to the front. */
    func onForeground() async {
        importInbox()
        store.purgeTrash()
        store.fillFullText()
        scheduleReminders()
        if account.email != nil { sync.requestSync(after: 0) }
        // With automatic reading on, read what came in while there was no key or from another device.
        if ai.config.isSet && ai.config.autoRead && !store.unread().isEmpty { jobs.readAll() }
    }

    func syncNow() async {
        guard account.email != nil else { return }
        await sync.sync()
    }

    // MARK: Account

    /** Signs in with Google and moves this phone's papers into that Drive. */
    func signIn() async throws {
        guard let vc = Self.topViewController() else { return }
        try await account.signIn(presenting: vc)
        if let email = account.email {
            await sync.connect(email: email)
            AIJobs.requestNotificationPermission()
        }
    }

    /** Signs out: the papers leave this phone (they stay in Drive). Gmail goes too. */
    func signOut() async {
        gmail.disconnect()
        account.signOut()
        await sync.signedOut()
    }

    /** Asks for Drive access again after Google stopped accepting it. */
    func allowDrive() async throws {
        guard let vc = Self.topViewController() else { return }
        _ = try await account.accessToken(adding: [GoogleAccount.driveScope], presenting: vc)
        sync.requestSync(after: 0)
    }

    func connectGmail() async throws {
        guard let vc = Self.topViewController(), let email = account.email else { return }
        _ = try await account.accessToken(adding: [AppModel.gmailScope], presenting: vc)
        gmail.settings.setEmail(email)
        gmail.scheduleDailySweep()
        AIJobs.requestNotificationPermission()
    }

    // MARK: Papers

    func open(_ id: String) {
        if case .paper(let open)? = path.last, open == id { return }
        path.append(.paper(id))
    }

    func importFiles(_ urls: [URL], name: String? = nil) {
        var added: [String] = [], duplicates: [String] = [], failed = 0
        for url in urls {
            do {
                switch try store.importFile(url, name: name, autoRead: ai.config.isSet && ai.config.autoRead) {
                case .added(let id): added.append(id)
                case .duplicate(let id): duplicates.append(id)
                }
            } catch { failed += 1 }
        }
        // Expiry reminders and the AI's notifications need permission; asked once, with the first paper.
        if !added.isEmpty { AIJobs.requestNotificationPermission() }
        if failed > 0 {
            toast(urls.count == 1 ? "Couldn't read this file" : "Added \(added.count), \(failed) couldn't be read")
        } else if let id = duplicates.first, duplicates.count == 1, added.isEmpty {
            toast("Already in My Papers: \(store.live(id)?.displayTitle ?? "")")
        } else if !duplicates.isEmpty {
            toast("Added \(added.count), \(duplicates.count) already here")
        } else if !added.isEmpty {
            toast(added.count == 1 ? "Added" : "Added \(added.count) papers")
        }
    }

    func importPhotos(_ items: [PhotosPickerItem]) {
        Task {
            var urls: [URL] = []
            for item in items {
                guard let data = try? await item.loadTransferable(type: Data.self) else { continue }
                let ext = item.supportedContentTypes.first?.preferredFilenameExtension ?? "jpg"
                let url = FileManager.default.temporaryDirectory.appending(path: "Photo \(Format.dateTime(Date())).\(ext)".replacingOccurrences(of: "/", with: "-"))
                if (try? data.write(to: url)) != nil { urls.append(url) }
            }
            importFiles(urls)
            urls.forEach { try? FileManager.default.removeItem(at: $0) }
        }
    }

    /** What "Share → My Papers" left in the app group's inbox. */
    func importInbox() {
        guard let inbox = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: Self.appGroup)?.appending(path: "Inbox"),
              let files = try? FileManager.default.contentsOfDirectory(at: inbox, includingPropertiesForKeys: nil), !files.isEmpty else { return }
        for f in files {
            // The extension put an 8-character prefix before the real name.
            let name = String(f.lastPathComponent.drop { $0 != "-" }.dropFirst())
            _ = try? store.importFile(f, name: name.isEmpty ? f.lastPathComponent : name, autoRead: ai.config.isSet && ai.config.autoRead)
            try? FileManager.default.removeItem(at: f)
        }
        toast(files.count == 1 ? "Added from Share" : "Added \(files.count) papers from Share")
    }

    func moveToTrash(ids: [String]) {
        ids.forEach(store.moveToTrash)
        if path.last.map({ if case .paper(let id) = $0 { ids.contains(id) } else { false } }) == true { path.removeLast() }
        toast(ids.count == 1 ? "Moved to trash" : "\(ids.count) moved to trash")
    }

    // MARK: AI

    func requestReading(_ id: String) { jobs.read(id) }

    func read(ids: [String]) { ids.forEach(jobs.read) }

    func readUnread() { jobs.readAll() }

    /** The library's sparkle: read what's unread, or say why not. */
    func readUnreadTapped() {
        if !ai.config.isSet { path.append(.settings) }
        else if store.unread().isEmpty { toast("Every paper is read") }
        else { readUnread() }
    }

    func reset(_ id: String) { store.reset(id) }

    func ask(_ id: String, _ question: String, reworks: String? = nil) {
        AIJobs.requestNotificationPermission()
        jobs.ask(id, question: question, reworks: reworks)
    }

    func askAll(_ question: String, followUp: Bool) {
        AIJobs.requestNotificationPermission()
        jobs.askAll(question, followUp: followUp)
    }

    func closeAskAll() {
        jobs.cancel(AIJobs.askAllKey)
        conversations.setAll(nil)
    }

    func transcribe(_ id: String) {
        AIJobs.requestNotificationPermission()
        jobs.transcribe(id)
    }

    // MARK: Sharing

    /** The papers' files under their titles, to the share sheet. */
    func share(ids: [String]) {
        let papers = ids.compactMap(store.live).filter(\.fileLocal)
        guard !papers.isEmpty else { return toast("The file isn't on this phone yet") }
        let dir = Self.shareDir()
        sharing = Self.named(papers).compactMap { p, name in
            let url = dir.appending(path: name)
            return (try? FileManager.default.copyItem(at: PaperFiles.url(p.id), to: url)) != nil ? url : nil
        }
    }

    /** One ZIP with papers.csv and the files (with a .md of the text where extracted), to the share sheet. */
    func export(ids: [String]) {
        let papers = ids.compactMap(store.live).sorted { Filters.paperDay($0) < Filters.paperDay($1) }
        let dir = Self.shareDir()
        let folder = dir.appending(path: "My Papers \(Day.today.iso)")
        let files = folder.appending(path: "files")
        try? FileManager.default.createDirectory(at: files, withIntermediateDirectories: true)
        let names = Self.named(papers)
        var paths: [String: String] = [:]
        for (p, name) in names {
            if p.fileLocal, (try? FileManager.default.copyItem(at: PaperFiles.url(p.id), to: files.appending(path: name))) != nil { paths[p.id] = "files/\(name)" }
            if !p.transcript.isEmpty {
                try? p.transcript.write(to: files.appending(path: (name as NSString).deletingPathExtension + ".md"), atomically: true, encoding: .utf8)
            }
        }
        try? ExportCSV.csv(papers, fileNames: paths).write(to: folder.appending(path: "papers.csv"), atomically: true, encoding: .utf8)
        // A folder read "for uploading" comes out as a ZIP.
        var zip: URL?
        NSFileCoordinator().coordinate(readingItemAt: folder, options: [.forUploading], error: nil) { tmp in
            let out = dir.appending(path: folder.lastPathComponent + ".zip")
            if (try? FileManager.default.copyItem(at: tmp, to: out)) != nil { zip = out }
        }
        if let zip { sharing = [zip] } else { toast("Couldn't make the export") }
    }

    /** A new folder per share (the app shared to may read later); older ones go. */
    private static func shareDir() -> URL {
        let root = FileManager.default.temporaryDirectory.appending(path: "share")
        for d in (try? FileManager.default.contentsOfDirectory(at: root, includingPropertiesForKeys: [.creationDateKey])) ?? [] {
            if let c = try? d.resourceValues(forKeys: [.creationDateKey]).creationDate, c < Date().addingTimeInterval(-86400) { try? FileManager.default.removeItem(at: d) }
        }
        let dir = root.appending(path: UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /** Each paper's file named by its title ("Shufersal receipt.pdf"), unique within the batch. */
    private static func named(_ papers: [Paper]) -> [(Paper, String)] {
        var used = Set<String>()
        return papers.map { p in
            let base = String(p.displayTitle.replacingOccurrences(of: "[\\\\/:*?\"<>|]", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces).prefix(80))
            let ext = Self.ext(p)
            var name = "\(base.isEmpty ? "Paper" : base).\(ext)"
            var n = 2
            while !used.insert(name).inserted { name = "\(base) (\(n)).\(ext)"; n += 1 }
            return (p, name)
        }
    }

    private static func ext(_ p: Paper) -> String {
        switch PaperFiles.mimeType(p) {
        case "application/pdf": "pdf"
        case "image/png": "png"
        case "image/heic": "heic"
        case "image/webp": "webp"
        case let t? where t.hasPrefix("image/"): "jpg"
        default: (p.fileName as NSString?)?.pathExtension.nilIfEmpty ?? "bin"
        }
    }

    // MARK: Small things

    func toast(_ text: String) {
        toastText = text
        Task { try? await Task.sleep(for: .seconds(3)); if toastText == text { toastText = nil } }
    }

    private var reminderTask: Task<Void, Never>?

    /** Expiry reminders follow the papers (a moment after the last change). */
    func scheduleReminders() {
        reminderTask?.cancel()
        let items = store.all().map { ExpiryReminders.Item(id: $0.id, title: $0.displayTitle, details: $0.details) }
        let dayFirst = Format.dayFirst()
        reminderTask = Task {
            try? await Task.sleep(for: .seconds(2))
            guard !Task.isCancelled else { return }
            await ExpiryReminders.schedule(items, dayFirst: dayFirst)
        }
    }

    /** A tapped notification: the paper, the Gmail finds or Settings. */
    func handleNotification(_ info: [AnyHashable: Any]) {
        if let id = info["paperId"] as? String { path = [.paper(id)] }
        else if (info["open"] as? String) == "gmail" { path = [.gmail] }
        else if (info["open"] as? String) == "settings" { path = [.settings] }
    }

    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
            ?? UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var vc = scene?.keyWindow?.rootViewController
        while let next = vc?.presentedViewController { vc = next }
        return vc
    }
}

extension String {
    var nilIfEmpty: String? { isEmpty ? nil : self }
}

/** Papers as a spreadsheet (for an accountant, or a tax refund claim): CSV with a BOM, so Excel shows Hebrew right. */
enum ExportCSV {
    static func csv(_ papers: [Paper], fileNames: [String: String]) -> String {
        func plain(_ cents: Int64?) -> String { cents.map { "\(Decimal($0) / 100)" } ?? "" }
        let rows = [["Date", "Title", "Vendor", "Type", "Total", "VAT", "Currency", "Tags", "Details", "Notes", "File"]] + papers.map { d in
            [d.date ?? "", d.title, d.vendor, d.docType, plain(d.totalCents), plain(d.vatCents), d.currency ?? "", d.tags.joined(separator: ", "),
             d.details.map { "\($0.label): \($0.value)" }.joined(separator: "; "), d.notes, fileNames[d.id] ?? ""]
        }
        return "\u{FEFF}" + rows.map { $0.map(cell).joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
    }

    private static func cell(_ s: String) -> String {
        s.contains(where: { ",\"\n\r".contains($0) }) ? "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\"" : s
    }
}

extension AIJobs {
    func isAsking(_ id: String) -> Bool { isRunning(AIJobs.askKey(id)) }
    func isTranscribing(_ id: String) -> Bool { isRunning(AIJobs.transcribeKey(id)) }
    func cancelRead(_ id: String) { cancel(AIJobs.readKey(id)) }
    func cancelAsk(_ id: String) { cancel(AIJobs.askKey(id)) }
    func cancelAskAll() { cancel(AIJobs.askAllKey) }
    func cancelTranscribe(_ id: String) { cancel(AIJobs.transcribeKey(id)) }
    func cancelReadAll() { cancel(AIJobs.readAllKey) }
}
