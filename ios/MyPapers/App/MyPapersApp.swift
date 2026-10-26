import BackgroundTasks
import SwiftUI
import UserNotifications

@main
struct MyPapersApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var app = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(app)
                .onOpenURL { _ = GoogleAccount.handle(url: $0) }
                .task {
                    delegate.app = app
                    await app.account.restore()
                    await app.onForeground()
                }
                .onChange(of: phase) { _, p in
                    if p == .active { Task { await app.onForeground() } }
                    if p == .background { AppDelegate.scheduleSync() }
                }
        }
        .modelContainer(app.container)
    }
}

/** Notification taps and banners while the app is open; the Gmail sweep's background task. */
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    @MainActor var app: AppModel?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        UNUserNotificationCenter.current().delegate = self
        // Before launch finishes, as iOS requires.
        GmailImporter.registerBackgroundTask()
        BGTaskScheduler.shared.register(forTaskWithIdentifier: AppDelegate.syncTaskId, using: .main) { task in
            // Now and then while the app isn't open: pick up papers from other devices, send up what's left.
            AppDelegate.scheduleSync()
            let work = Task { @MainActor in await self.app?.syncNow() }
            task.expirationHandler = { work.cancel() }
            Task { @MainActor in
                await work.value
                task.setTaskCompleted(success: true)
            }
        }
        return true
    }

    static let syncTaskId = "com.bennybar.mypapers.sync"

    /** The next background sync, in an hour or more (iOS picks the moment). */
    static func scheduleSync() {
        let request = BGAppRefreshTaskRequest(identifier: syncTaskId)
        request.earliestBeginDate = Date().addingTimeInterval(60 * 60)
        do { try BGTaskScheduler.shared.submit(request) } catch {
            backgroundLog.error("sync refresh refused: \(error.localizedDescription, privacy: .public)")
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        let sendable = Dictionary(uniqueKeysWithValues: info.compactMap { k, v in (k as? String).flatMap { key in (v as? String).map { (key, $0) } } })
        await MainActor.run { app?.handleNotification(sendable) }
    }
}

/** The navigation stack over the library, with the share sheet and short messages on top. */
struct RootView: View {
    @Environment(AppModel.self) private var app
    @Namespace private var namespace

    var body: some View {
        @Bindable var app = app
        NavigationStack(path: $app.path) {
            LibraryView()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case .paper(let id): PaperView(id: id)
                    case .settings: SettingsView()
                    case .gmail: GmailView()
                    case .trash: TrashView()
                    }
                }
        }
        .onAppear {
            app.namespace = namespace
            #if DEBUG
            // For screenshots: `-open paper|settings|gmail|trash` and `-style cards|compact|grid|timeline`.
            let args = ProcessInfo.processInfo.arguments
            if args.contains("-debugJob") { app.jobs.debugJob() }
            if let i = args.firstIndex(of: "-style"), i + 1 < args.count, let s = ListStyle(rawValue: args[i + 1]) { app.library.style = s }
            if let i = args.firstIndex(of: "-open"), i + 1 < args.count {
                switch args[i + 1] {
                case "paper": if let p = app.store.all().first { app.path = [.paper(p.id)] }
                case "settings": app.path = [.settings]
                case "gmail": app.path = [.gmail]
                case "trash": app.path = [.trash]
                default: break
                }
            }
            #endif
        }
        .sheet(isPresented: Binding(get: { app.sharing != nil }, set: { if !$0 { app.sharing = nil } })) {
            ShareSheet(items: app.sharing ?? []).presentationDetents([.medium, .large])
        }
        .overlay(alignment: .bottom) {
            if let text = app.toastText {
                Text(text)
                    .font(.subheadline)
                    .padding(.horizontal, 18).padding(.vertical, 12)
                    .glassEffect(.regular, in: .capsule)
                    .padding(.bottom, 80)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring, value: app.toastText)
    }
}

/** The system share sheet for files. */
struct ShareSheet: UIViewControllerRepresentable {
    let items: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
