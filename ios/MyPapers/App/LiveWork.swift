import BackgroundTasks
import UIKit

/**
 * Long work the user started (an AI job, a Gmail scan or import, a big Drive sync), kept going after
 * leaving the app and shown by iOS as a live activity with its progress and a stop button: in the
 * Dynamic Island and on the Lock Screen (BGContinuedProcessingTask, iOS 26).
 *
 * iOS ends such a task when its progress stops moving, so the progress always creeps forward within
 * the current step, and jumps as real steps complete. Ids start with the bundle id under the
 * `com.bennybar.mypapers.live.*` wildcard in Info.plist, as iOS 26 requires. The work itself never
 * waits for iOS: a refused task only means no live activity (and the usual ~30 seconds of background
 * time, which every piece of work also takes).
 */
@MainActor
final class LiveWork {
    static let shared = LiveWork()

    private var tokens: [String: UUID] = [:]
    private var tasks: [String: BGContinuedProcessingTask] = [:]
    private var short: [String: UIBackgroundTaskIdentifier] = [:]
    private var tickers: [String: Task<Void, Never>] = [:]
    /** Real progress so far, per key: (done, total) steps. */
    private var steps: [String: (done: Int, total: Int)] = [:]
    /** What iOS said the last time (for diagnostics). */
    private(set) var lastResult = ""

    /** Starts showing [key] as live work; [onExpire]: iOS (or the user, from the live activity) stopped it. */
    func begin(_ key: String, title: String, subtitle: String, onExpire: @escaping @MainActor () -> Void) {
        end(key)
        let token = UUID()
        tokens[key] = token
        steps[key] = (0, 1)
        short[key] = UIApplication.shared.beginBackgroundTask(withName: key) { [weak self] in
            MainActor.assumeIsolated {
                if let id = self?.short.removeValue(forKey: key) { UIApplication.shared.endBackgroundTask(id) }
            }
        }
        let identifier = "com.bennybar.mypapers.live.\(Self.safe(key)).\(token.uuidString)"
        let registered = BGTaskScheduler.shared.register(forTaskWithIdentifier: identifier, using: .main) { [weak self] task in
            MainActor.assumeIsolated {
                guard let self, let task = task as? BGContinuedProcessingTask, self.tokens[key] == token else {
                    task.setTaskCompleted(success: true)
                    return
                }
                self.trace("\(key): live activity started")
                task.progress.totalUnitCount = 100
                task.expirationHandler = { [weak self] in
                    MainActor.assumeIsolated {
                        self?.trace("\(key): stopped by iOS or from the live activity")
                        self?.end(key)
                        onExpire()
                    }
                }
                self.tasks[key] = task
                self.tick(key, task)
            }
        }
        guard registered else { return result("\(key): couldn't register the task") }
        let request = BGContinuedProcessingTaskRequest(identifier: identifier, title: title, subtitle: subtitle)
        // Now or not at all: the work itself has already started.
        request.strategy = .fail
        do {
            try BGTaskScheduler.shared.submit(request)
            result("\(key): allowed")
        } catch {
            result("\(key): refused (\(error.localizedDescription))")
        }
    }

    /** Real progress: [done] of [total] steps (the bar creeps on within the current step). */
    func progress(_ key: String, done: Int, total: Int, subtitle: String? = nil) {
        guard tokens[key] != nil, total > 0 else { return }
        steps[key] = (min(done, total), total)
        guard let task = tasks[key] else { return }
        let floor = Int64(min(done, total)) * 100
        task.progress.totalUnitCount = Int64(total) * 100
        if task.progress.completedUnitCount < floor { task.progress.completedUnitCount = floor }
        if let subtitle { task.updateTitle(task.title, subtitle: subtitle) }
    }

    /** Done (or stopped): the live activity closes and the background time is given back. */
    func end(_ key: String) {
        tokens[key] = nil
        steps[key] = nil
        tickers.removeValue(forKey: key)?.cancel()
        if let task = tasks.removeValue(forKey: key) {
            task.progress.completedUnitCount = task.progress.totalUnitCount
            task.setTaskCompleted(success: true)
            trace("\(key): done")
        }
        if let id = short.removeValue(forKey: key) { UIApplication.shared.endBackgroundTask(id) }
    }

    /** Creeps toward the end of the current step (never reaching it), so iOS sees the work alive. */
    private func tick(_ key: String, _ task: BGContinuedProcessingTask) {
        tickers[key]?.cancel()
        tickers[key] = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                if let s = self?.steps[key] {
                    let ceiling = Int64(min(s.done, s.total)) * 100 + 95
                    let now = task.progress.completedUnitCount
                    if now < ceiling { task.progress.completedUnitCount = min(ceiling, now + max(1, (ceiling - now) / 12)) }
                }
                try? await Task.sleep(for: .seconds(3))
            }
        }
    }

    /** Only letters, digits, dots and dashes in task ids. */
    private static func safe(_ key: String) -> String {
        String(key.map { $0.isLetter || $0.isNumber || $0 == "-" ? $0 : "-" })
    }

    private func result(_ text: String) {
        lastResult = text
        trace(text)
    }

    private func trace(_ text: String) {
        backgroundLog.info("\(text, privacy: .public)")
        backgroundTrace(text)
    }
}
