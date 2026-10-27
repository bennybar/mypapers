import SwiftUI

/** Account and backup, AI reading, Gmail, papers (trash), dates and amounts. */
struct SettingsView: View {
    @Environment(AppModel.self) private var app

    var body: some View {
        Form {
            AccountSection()
            AISection()
            GmailSection()
            Section("Papers") {
                NavigationLink(value: Route.trash) { Label("Trash", systemImage: "trash") }
            }
            RegionSection()
        }
        .navigationTitle("Settings")
    }
}

/** "just now", "5 minutes ago", "12 Oct". */
func ago(_ ms: Int64) -> String {
    let date = Date(timeIntervalSince1970: Double(ms) / 1000)
    if Date().timeIntervalSince(date) < 60 { return "just now" }
    return date.formatted(.relative(presentation: .named))
}

private struct AccountSection: View {
    @Environment(AppModel.self) private var app
    @State private var status: String?
    @State private var busy = false
    @State private var confirmSignOut = false

    var body: some View {
        Section {
            if let email = app.account.email {
                Text("Backed up to the Google Drive of \(email), in a hidden folder only My Papers can see. Synced between your devices.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Group {
                    switch app.sync.state {
                    case .uploading(let done, let total): Text("Backing up · \(done + 1) of \(total)")
                    case .syncing: Text("Syncing…")
                    case .failed(let m): Text("Sync failed: \(m)").foregroundStyle(.red)
                    case .idle: Text(app.sync.lastSyncAt > 0 ? "Everything's backed up · last sync \(ago(app.sync.lastSyncAt))" : "Not synced yet")
                    }
                }
                .font(.subheadline).foregroundStyle(.tint)
                if case .failed(let m) = app.sync.state, m == DriveAuthError.message {
                    Button("Allow Drive access") { run { try await app.allowDrive() } }
                }
                Button("Sign out", role: .destructive) { confirmSignOut = true }
            } else {
                Text("Sign in to back up your papers to your own Google Drive and sync them between your devices — with the Android app too.")
                    .font(.subheadline).foregroundStyle(.secondary)
                Button(busy ? "Signing in…" : "Sign in with Google") { run { try await app.signIn() } }.disabled(busy)
            }
            if let status { Text(status).font(.footnote).foregroundStyle(.red) }
        } header: { Text("Account") }
        .confirmationDialog("Sign out?", isPresented: $confirmSignOut, titleVisibility: .visible) {
            Button("Sign out", role: .destructive) { Task { await app.signOut() } }
        } message: {
            Text("Your papers are removed from this phone. They stay in your Google Drive and come back when you sign in again. Changes not yet synced are lost.")
        }
    }

    private func run(_ work: @escaping () async throws -> Void) {
        busy = true
        status = nil
        Task {
            do { try await work() } catch { status = error.localizedDescription }
            busy = false
        }
    }
}

private struct AISection: View {
    @Environment(AppModel.self) private var app
    @State private var key = ""
    @State private var model = ""
    @State private var server = ""
    @State private var status: String?
    @State private var busy = false
    @State private var loaded = false
    @State private var confirmReread = false
    @State private var clearAsked = false

    var body: some View {
        let saved = app.ai.config
        let config = AIConfig(apiKey: key.trimmingCharacters(in: .whitespaces), model: model.trimmingCharacters(in: .whitespaces).nilIfEmpty ?? AIConfig.defaultModel,
                              autoRead: saved.autoRead, baseUrl: server.trimmingCharacters(in: .whitespaces))
        let serverBad = !server.isEmpty && !server.lowercased().hasPrefix("https://")
        Section {
            Text("Your own OpenAI key (or your own proxy server) reads your papers: a clear title, the vendor, amounts, date, type and tags. The key stays on this phone.")
                .font(.subheadline).foregroundStyle(.secondary)
            SecureField(server.isEmpty ? "OpenAI API key" : "API key", text: $key)
            TextField("Model", text: $model).textInputAutocapitalization(.never).autocorrectionDisabled()
            VStack(alignment: .leading) {
                TextField("Proxy server (optional)", text: $server, prompt: Text("https://litellm.example.com/v1"))
                    .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                Text(serverBad ? "Starts with https:// (your key goes with each request, so it must be encrypted)" : "Leave empty for OpenAI. For LiteLLM or another OpenAI-compatible proxy: its address. It needs the Responses API.")
                    .font(.caption).foregroundStyle(serverBad ? .red : .secondary)
            }
            if let status { Text(status).font(.footnote) }
            HStack {
                Button("Test") {
                    busy = true
                    Task {
                        do { try await OpenAIClient().check(config); status = "Key and model work" } catch { status = error.localizedDescription }
                        busy = false
                    }
                }
                .disabled(!config.isSet || busy || serverBad)
                Spacer()
                Button("Save") { app.ai.save(config); status = "Saved" }.disabled(busy || config == saved || serverBad)
            }
            .buttonStyle(.glass)
            if saved.isSet {
                Toggle(isOn: Binding(get: { app.ai.config.autoRead }, set: { var c = app.ai.config; c.autoRead = $0; app.ai.save(c) })) {
                    VStack(alignment: .leading) {
                        Text("Read new papers automatically")
                        Text("In the background as each one comes in. Off: only when you ask.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                let unread = app.store.unread().count
                if unread > 0 { Button(unread == 1 ? "Read 1 unread" : "Read \(unread) unread") { app.readUnread() } }
                let readable = app.store.all().filter(\.fileLocal).count
                if readable > 0 { Button("Re-read all (\(readable))") { confirmReread = true } }
            }
        } header: { Text("AI reading") }
        .onAppear {
            guard !loaded else { return }
            loaded = true
            key = saved.apiKey; model = saved.model; server = saved.baseUrl
        }
        .sheet(isPresented: $confirmReread) {
            NavigationStack {
                Form {
                    Text("The AI reads each paper again and refreshes what it read: title, vendor, amounts, date, type, tags and details. What you corrected by hand stays. One AI request per paper, in the background.")
                    Toggle("Also remove Ask AI answers and extracted text", isOn: $clearAsked)
                }
                .navigationTitle("Re-read all?")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { confirmReread = false } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Re-read") { confirmReread = false; app.jobs.readAll(all: true, clearAsked: clearAsked) }
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }
}

private struct GmailSection: View {
    @Environment(AppModel.self) private var app
    @State private var status: String?

    var body: some View {
        let g = app.gmail
        let s = g.settings
        Section {
            Text(s.isConnected ? "Connected to \(s.email ?? "") (read-only). Finds receipts and documents — attachments, and receipts written in the email — for you to pick from."
                 : "Finds receipts and documents in your Gmail — attachments, and receipts written in the email — for you to pick from. Read-only: My Papers can't change, send or delete anything.")
                .font(.subheadline).foregroundStyle(.secondary)
            if s.isConnected {
                Picker("First scan goes back", selection: Binding(get: { s.monthsBack }, set: { s.setMonthsBack($0) })) {
                    ForEach([3, 6, 12, 24], id: \.self) { Text("\($0) mo").tag($0) }
                }
                .pickerStyle(.segmented)
                Toggle(isOn: Binding(get: { s.dailySweep }, set: { s.setDailySweep($0); g.scheduleDailySweep() })) {
                    VStack(alignment: .leading) {
                        Text("Check daily for new papers")
                        Text("A notification when there's something new").font(.caption).foregroundStyle(.secondary)
                    }
                }
                if s.lastScanAt > 0 {
                    let took = s.lastScanTook / 1000
                    Text("Last sync \(ago(s.lastScanAt))" + (s.lastScanTook == 0 ? "" : took < 60 ? " · took \(took)s" : " · took \(took / 60) min \(took % 60)s"))
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
            if let e = status ?? g.scanError { Text(e).font(.footnote).foregroundStyle(.red) }
            HStack {
                if !s.isConnected {
                    Button("Connect Gmail") { connect() }.disabled(app.account.email == nil)
                } else {
                    Button(g.busy ? "Scanning…" : (s.lastScanAt == 0 ? "Scan now" : "Scan again")) { g.scanNow(); app.tab = .gmail }.disabled(g.busy)
                    Spacer()
                    Button("Disconnect", role: .destructive) { g.disconnect() }
                }
            }
            .buttonStyle(.glass)
            if app.account.email == nil { Text("Sign in first (above) — Gmail uses the same Google account.").font(.caption) }
            if g.newCount > 0 { Button("Review \(g.newCount) found in Gmail") { app.tab = .gmail } }
        } header: { Text("Gmail") }
    }

    private func connect() {
        status = nil
        Task { do { try await app.connectGmail() } catch { status = error.localizedDescription } }
    }
}

private struct RegionSection: View {
    @State private var region = Format.regionTag ?? ""
    private static let regions = ["he-IL", "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "ru-RU", "ar-IL", "en-CA", "en-AU", "nl-NL", "pt-BR", "ja-JP"]

    var body: some View {
        Section {
            Picker("Region", selection: $region) {
                Text("Phone setting").tag("")
                ForEach(Self.regions, id: \.self) { tag in Text(Locale(identifier: tag).localizedString(forIdentifier: tag) ?? tag).tag(tag) }
            }
            .onChange(of: region) { _, tag in
                Format.regionTag = tag.isEmpty ? nil : tag
                UserDefaults.standard.set(tag.isEmpty ? nil : tag, forKey: "region")
            }
            Text("\(Format.date(Day.today.iso) ?? "") · \(Format.amount(124950, Format.defaultCurrency().nilIfEmpty))").id(region)
        } header: { Text("Dates and amounts") } footer: {
            Text("How dates and amounts are shown, the currency new papers start with, and how a date like 03/04 is read when the paper gives no clue.")
        }
    }
}

/**
 * What Gmail scans found: pick what to add (the AI never reads these on its own). One by one (swipe,
 * long-press), or Select → Select All (or some) → Add / Not Papers for all of them at once.
 */
struct GmailView: View {
    @Environment(AppModel.self) private var app
    @State private var editMode: EditMode = .inactive
    @State private var selection: Set<String> = []

    var body: some View {
        let g = app.gmail
        let keys = g.candidates.map(\.key)
        List(selection: Binding(get: { editMode.isEditing ? selection : [] }, set: { if editMode.isEditing { selection = $0 } })) {
            if g.busy {
                HStack {
                    ProgressView()
                    Text(scanText(g)).font(.subheadline)
                    Spacer()
                    if g.scanning { Button("Stop") { g.cancelScan() }.buttonStyle(.glass) }
                }
            }
            if let e = g.scanError { Text(e).foregroundStyle(.red) }
            if g.candidates.isEmpty && !g.busy {
                ContentUnavailableView("Nothing new in Gmail", systemImage: "envelope.open",
                                       description: Text(g.settings.isConnected ? "New receipts and documents show up here after a scan." : "Connect Gmail in Settings to find receipts and documents in your mail."))
            }
            Section {
                ForEach(g.candidates, id: \.key) { c in
                    VStack(alignment: .leading, spacing: 4) {
                        Label(c.fileName, systemImage: c.kind == GmailCandidate.kindEmail ? "envelope" : (c.mimeType == "application/pdf" ? "doc.richtext" : "photo"))
                            .font(.body.weight(.medium))
                        Text("\(c.sender) · \(c.subject)").font(.caption).foregroundStyle(.secondary).lineLimit(2)
                        Text(Format.day(c.sentAt)).font(.caption2).foregroundStyle(.secondary)
                        if c.state == GmailCandidate.stateQueued { Label("Adding…", systemImage: "arrow.down.circle").font(.caption).foregroundStyle(.tint) }
                        if c.state == GmailCandidate.stateFailed { Text(c.error ?? "Couldn't add it").font(.caption).foregroundStyle(.red) }
                    }
                    .tag(c.key)
                    .swipeActions(edge: .leading) { Button("Add") { g.importCandidate(c) }.tint(.accentColor) }
                    .swipeActions(edge: .trailing) { Button("Not a Paper", role: .destructive) { g.dismiss(c) } }
                    .contextMenu {
                        Button("Add to My Papers", systemImage: "plus") { g.importCandidate(c) }
                        Button("Not a Paper", systemImage: "xmark", role: .destructive) { g.dismiss(c) }
                    }
                }
            } footer: {
                if !g.candidates.isEmpty && !editMode.isEditing { Text("Swipe right to add, left if it isn't a paper — or Select to do many at once.") }
            }
        }
        .environment(\.editMode, $editMode)
        // In Select mode the actions bar takes the tab bar's place.
        .toolbar(editMode.isEditing ? .hidden : .visible, for: .tabBar)
        .navigationTitle(editMode.isEditing ? "\(selection.count) selected" : "Found in Gmail")
        .refreshable { g.scanNow() }
        .onChange(of: editMode) { _, m in if !m.isEditing { selection = [] } }
        .onChange(of: keys) { _, now in selection.formIntersection(now) }
        .toolbar {
            if editMode.isEditing {
                ToolbarItem(placement: .topBarLeading) { Button("Done") { editMode = .inactive } }
                ToolbarItem(placement: .topBarTrailing) {
                    Button(selection.count == keys.count ? "Deselect All" : "Select All") {
                        selection = selection.count == keys.count ? [] : Set(keys)
                    }
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("Not Papers", role: .destructive) { g.dismiss(Array(selection)); editMode = .inactive }
                        .disabled(selection.isEmpty)
                    Spacer()
                    Button(selection.isEmpty ? "Add" : "Add \(selection.count)") { g.queue(Array(selection)); editMode = .inactive }
                        .buttonStyle(.glassProminent)
                        .disabled(selection.isEmpty)
                }
            } else {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Select") { editMode = .active }.disabled(g.candidates.isEmpty)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button { g.scanNow() } label: { Image(systemName: "arrow.clockwise") }
                        .disabled(g.busy || !g.settings.isConnected).accessibilityLabel("Scan again")
                }
            }
        }
        .onAppear { g.refresh() }
    }

    private func scanText(_ g: GmailImporter) -> String {
        let p = g.progress
        if g.scanning { return p.map { $0.total > 0 ? "Looking through Gmail · \($0.done) of \($0.total)" : "Looking through Gmail…" } ?? "Looking through Gmail…" }
        return p.map { $0.total > 0 ? "Adding · \($0.done + 1) of \($0.total)" : "Adding…" } ?? "Adding…"
    }
}
