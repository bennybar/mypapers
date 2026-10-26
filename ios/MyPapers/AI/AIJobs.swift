import BackgroundTasks
import Foundation
import Observation
import UserNotifications

/**
 * The AI's jobs, each a cancellable task (Android's WorkManager workers): reading a paper, reading
 * them all, a question about one paper or all of them, writing a paper out. A job the user starts also
 * asks iOS to keep it going in the background (BGContinuedProcessingTask: the system shows its progress
 * and a way to stop it); if iOS says no, it just runs while the app does. Retryable failures (a busy
 * OpenAI, the network) are tried again, up to three times.
 */
@MainActor @Observable
final class AIJobs {
    let service: AIService
    let conversations: Conversations
    private var store: PaperStore { service.store }

    /** The jobs running now, by key ([readKey], [askKey]…). */
    private(set) var running: Set<String> = []
    /** (done, total) while reading them all; nil when not. */
    private(set) var readAllProgress: (done: Int, total: Int)?

    @ObservationIgnored private var tasks: [String: Task<Void, Never>] = [:]
    /** Which run of a key is the current one (a new question replaces a running one). */
    @ObservationIgnored private var tokens: [String: UUID] = [:]
    @ObservationIgnored private var background: [String: BGContinuedProcessingTask] = [:]

    init(service: AIService, conversations: Conversations) {
        self.service = service
        self.conversations = conversations
    }

    static func readKey(_ id: String) -> String { "read-\(id)" }
    static let readAllKey = "readAll"
    static func askKey(_ id: String) -> String { "ask-\(id)" }
    static let askAllKey = "askAll"
    static func transcribeKey(_ id: String) -> String { "transcribe-\(id)" }

    func isRunning(_ key: String) -> Bool { running.contains(key) }

    /** Stops the job: the request is dropped (a background response cancelled at the server). */
    func cancel(_ key: String) { tasks[key]?.cancel() }

    // MARK: Jobs

    /** Reads (or re-reads) one paper. */
    func read(_ id: String) {
        store.markReading(id)
        start(Self.readKey(id), title: "Reading with AI", subtitle: store.live(id).map(paperTitle) ?? "") {
            do {
                try await self.withRetries { try await self.service.extract(id: id) }
            } catch {
                if Self.cancelled(error) { self.store.markUnread(id) } else { self.store.markFailed(id, error.localizedDescription) }
            }
        }
    }

    /** Every unread paper, or with [all] every paper, read one after another. [clearAsked]: Ask AI answers and written-out text go too. */
    func readAll(all: Bool = false, clearAsked: Bool = false) {
        guard service.settings.config.isSet else { return }
        if clearAsked {
            for p in store.all() where !p.transcript.isEmpty || p.details.contains(where: \.asked) {
                p.transcript = ""
                p.details = p.details.filter { !$0.asked }
                store.update(p)
            }
        }
        let ids = (all ? store.all().filter(\.fileLocal).sorted { $0.createdAt > $1.createdAt } : store.unread()).map(\.id)
        guard !ids.isEmpty else { return }
        start(Self.readAllKey, title: "Reading papers with AI", subtitle: "\(ids.count) papers") {
            defer { self.readAllProgress = nil }
            for (i, id) in ids.enumerated() {
                self.readAllProgress = (i, ids.count)
                self.progress(Self.readAllKey, done: i, total: ids.count)
                guard self.store.live(id) != nil else { continue }
                self.store.markReading(id)
                do {
                    try await self.withRetries { try await self.service.extract(id: id) }
                } catch {
                    if Self.cancelled(error) { self.store.markUnread(id); return }
                    if let e = error as? AIError {
                        // A bad key stops the run; a busy OpenAI (tried three times already) too, keeping what's done.
                        if e.retryable { self.store.markUnread(id) } else { self.store.markFailed(id, e.localizedDescription) }
                        return
                    }
                    if error is URLError { self.store.markUnread(id); return }
                    self.store.markFailed(id, error.localizedDescription)
                }
            }
        }
    }

    /** A question or instruction about one paper, or a follow-up to its conversation; [reworks]: the detail it reworks. */
    func ask(_ id: String, question: String, reworks: String? = nil) {
        let before = (conversations.papers[id] ?? []).filter(\.done)
        // A follow-up to a rework ("only the first rows") still replaces that detail.
        conversations.setPaper(id, before + [PaperTurn(question: question, reworks: reworks ?? before.last?.reworks)])
        start(Self.askKey(id), title: "Asking the AI", subtitle: question) {
            do {
                let turns = self.conversations.papers[id] ?? []
                let turn = try await self.withRetries { try await self.service.askPaper(id: id, turns: turns) }
                self.answerPaper(id, question) { _ in turn }
                Self.notify("Answer ready", turn.reply ?? question, paperId: id)
            } catch {
                if Self.cancelled(error) {
                    self.answerPaper(id, question) { var t = $0; t.error = "Stopped"; return t }
                } else {
                    self.answerPaper(id, question) { var t = $0; t.error = error.localizedDescription; return t }
                    Self.notify("Couldn't ask the AI", error.localizedDescription, paperId: id)
                }
            }
        }
    }

    /** A question about all the papers; [followUp]: carrying on the conversation. */
    func askAll(_ question: String, followUp: Bool = false) {
        let before = followUp ? (conversations.all ?? []).filter(\.done) : []
        conversations.setAll(before + [AllTurn(question: question)])
        start(Self.askAllKey, title: "Asking about your papers", subtitle: question) {
            do {
                let history = before.filter { $0.answer != nil }
                let a = try await self.withRetries { try await self.service.askAll(question: question, history: history) }
                self.answerAll(question, AllTurn(question: question, answer: a.answer, paperIds: a.papers.map(\.id), totals: a.totals))
                Self.notify("Answer ready", question, paperId: nil)
            } catch {
                if Self.cancelled(error) {
                    self.answerAll(question, AllTurn(question: question, error: "Stopped"))
                } else {
                    self.answerAll(question, AllTurn(question: question, error: error.localizedDescription))
                    Self.notify("Couldn't ask the AI", error.localizedDescription, paperId: nil)
                }
            }
        }
    }

    /** "Extract all text" for one paper; already running for it: left to finish (not charged twice). */
    func transcribe(_ id: String) {
        let key = Self.transcribeKey(id)
        guard !running.contains(key) else { return }
        let title = store.live(id).map(paperTitle) ?? ""
        start(key, title: "Extracting all text", subtitle: title) {
            do {
                let text = try await self.withRetries { try await self.service.transcribe(id: id) }
                Self.notify(text.isEmpty ? "No text found" : "Text extracted", title, paperId: id)
            } catch {
                if !Self.cancelled(error) { Self.notify("Couldn't extract the text", error.localizedDescription, paperId: id) }
            }
        }
    }

    // MARK: Answers

    /** The paper's conversation with its last turn answered (if it's still the one asked, still waiting). */
    private func answerPaper(_ id: String, _ question: String, _ change: (PaperTurn) -> PaperTurn) {
        guard let turns = conversations.papers[id], let last = turns.last, last.question == question, !last.done else { return }
        conversations.setPaper(id, turns.dropLast() + [change(last)])
    }

    private func answerAll(_ question: String, _ turn: AllTurn) {
        guard let turns = conversations.all, let last = turns.last, last.question == question, !last.done else { return }
        conversations.setAll(turns.dropLast() + [turn])
    }

    // MARK: Running

    private func start(_ key: String, title: String, subtitle: String, work: @escaping @MainActor () async -> Void) {
        tasks[key]?.cancel()
        let token = UUID()
        tokens[key] = token
        running.insert(key)
        tasks[key] = Task { @MainActor in
            await work()
            self.finished(key, token)
        }
        keepAlive(key, token: token, title: title, subtitle: subtitle)
    }

    private func finished(_ key: String, _ token: UUID) {
        guard tokens[key] == token else { return }
        tokens[key] = nil
        tasks[key] = nil
        running.remove(key)
        if let bg = background.removeValue(forKey: key) {
            bg.progress.completedUnitCount = bg.progress.totalUnitCount
            bg.setTaskCompleted(success: true)
        }
    }

    /** Asks iOS to keep this job going in the background, with its own progress and stop button. */
    private func keepAlive(_ key: String, token: UUID, title: String, subtitle: String) {
        let identifier = "com.bennybar.mypapers.ai.\(key).\(token.uuidString)"
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            MainActor.assumeIsolated {
                guard let self, let task = task as? BGContinuedProcessingTask, self.tokens[key] == token else {
                    task.setTaskCompleted(success: true)
                    return
                }
                task.progress.totalUnitCount = 100
                task.expirationHandler = { [weak self] in
                    Task { @MainActor in self?.cancel(key) }
                }
                self.background[key] = task
            }
        }
        guard registered else { return }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: subtitle)
        // Now or not at all: the job itself has already started.
        request.strategy = .fail
        try? BGTaskScheduler.shared.submit(request)
    }

    private func progress(_ key: String, done: Int, total: Int) {
        guard let bg = background[key], total > 0 else { return }
        bg.progress.totalUnitCount = Int64(total)
        bg.progress.completedUnitCount = Int64(done)
    }

    /** [op], again after a while when it failed in a way worth retrying, up to three times in all. */
    private func withRetries<T>(_ op: () async throws -> T) async throws -> T {
        var attempt = 0
        while true {
            do {
                return try await op()
            } catch {
                if Self.cancelled(error) { throw CancellationError() }
                attempt += 1
                let retryable = (error as? AIError)?.retryable ?? (error is URLError)
                guard attempt < 3, retryable else { throw error }
                try await Task.sleep(for: .seconds(30 * attempt))
            }
        }
    }

    private static func cancelled(_ error: Error) -> Bool {
        Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    private func paperTitle(_ p: Paper) -> String {
        !p.title.isEmpty ? p.title : !p.vendor.isEmpty ? p.vendor : p.fileName ?? "a paper"
    }

    // MARK: Notifications

    /** Asked once, when the first job starts (the job doesn't wait for the answer). */
    static func requestNotificationPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /** Done or failed; tapping opens the paper ("paperId" in userInfo), or the app. */
    static func notify(_ title: String, _ body: String?, paperId: String?) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body ?? ""
        if let paperId { content.userInfo = ["paperId": paperId] }
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}
