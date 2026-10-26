import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/** The library: search, chips, notices and the papers in the chosen view, with glass controls over them. */
struct LibraryView: View {
    @Environment(AppModel.self) private var app
    @State private var showFilters = false
    @State private var showFiles = false
    @State private var showScanner = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var askHidden = false
    @State private var tagPrompt = false
    @State private var newTag = ""
    @State private var typePrompt = false

    var body: some View {
        @Bindable var lib = app.library
        let _ = app.store.version
        let all = app.store.all()
        let results = lib.results(all)

        ScrollView {
            LazyVStack(alignment: .leading, spacing: lib.style == .compact || lib.style == .timeline ? 6 : 12) {
                chips(all)
                notices(all)
                if all.isEmpty {
                    EmptyLibrary(onScan: { showScanner = true }, onPick: { showFiles = true })
                } else if results.isEmpty {
                    Text("Nothing matches").foregroundStyle(.secondary).frame(maxWidth: .infinity).padding(.vertical, 48)
                } else {
                    if lib.narrowed { ResultSummary(papers: results).padding(.horizontal, 4) }
                    papers(results)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 96)
        }
        .background(Color(.systemGroupedBackground))
        .refreshable { await app.syncNow() }
        .navigationTitle(lib.selecting ? "\(lib.selection.count) selected" : "My Papers")
        .navigationSubtitle(lib.selecting ? "" : (lib.narrowed ? "\(results.count) shown" : (all.count == 1 ? "1 paper" : "\(all.count) papers")))
        .searchable(text: $lib.query, prompt: "Search papers")
        .toolbar { toolbar(results) }
        .sheet(isPresented: $showFilters) { FilterSheet(vendors: lib.vendors(all)).presentationDetents([.medium, .large]) }
        .fileImporter(isPresented: $showFiles, allowedContentTypes: [.pdf, .image], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r { app.importFiles(urls) }
        }
        .photosPicker(isPresented: $showPhotos, selection: $photoItems, matching: .images)
        .onChange(of: photoItems) { _, items in
            guard !items.isEmpty else { return }
            app.importPhotos(items)
            photoItems = []
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScanner { pdf in
                showScanner = false
                if let pdf { app.importFiles([pdf], name: "Scan \(Format.dateTime(Date())).pdf") }
            }
            .ignoresSafeArea()
        }
        .sheet(isPresented: Binding(get: { app.conversations.all != nil && !askHidden }, set: { if !$0 { app.closeAskAll() } })) {
            AskAllSheet(onOpen: { id in askHidden = true; app.open(id) })
        }
        .alert("Add tag", isPresented: $tagPrompt) {
            TextField("Tag", text: $newTag)
            Button("Add") { app.store.addTag(Array(lib.selection), newTag); newTag = ""; lib.selection = [] }
            Button("Cancel", role: .cancel) {}
        }
        .confirmationDialog("Set type", isPresented: $typePrompt) {
            ForEach(documentTypes, id: \.self) { t in Button(t) { app.store.setType(Array(lib.selection), t); lib.selection = [] } }
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private func toolbar(_ results: [Paper]) -> some ToolbarContent {
        let lib = app.library
        if lib.selecting {
            ToolbarItem(placement: .topBarLeading) {
                Button("Done") { lib.selection = [] }
            }
            ToolbarItemGroup(placement: .bottomBar) {
                Button { app.share(ids: Array(lib.selection)) } label: { Label("Share", systemImage: "square.and.arrow.up") }
                Spacer()
                Menu {
                    Button("Select all") { lib.selection = Set(results.map(\.id)) }
                    Button("Add tag…") { tagPrompt = true }
                    Button("Set type…") { typePrompt = true }
                    Button("Export (spreadsheet + files)") { app.export(ids: Array(lib.selection)) }
                    if app.ai.config.isSet { Button("Read with AI") { app.read(ids: Array(lib.selection)); lib.selection = [] } }
                } label: { Label("More", systemImage: "ellipsis") }
                Spacer()
                Button(role: .destructive) { app.moveToTrash(ids: Array(lib.selection)); lib.selection = [] } label: { Label("Delete", systemImage: "trash") }
            }
        } else {
            ToolbarItem(placement: .topBarTrailing) {
                Button { app.readUnreadTapped() } label: {
                    if app.jobs.readAllProgress != nil {
                        ProgressView()
                    } else {
                        Image(systemName: "sparkles").overlay(alignment: .topTrailing) {
                            let n = app.store.unread().count
                            if n > 0 { Text("\(n)").font(.caption2.bold()).padding(3).background(.red, in: Circle()).foregroundStyle(.white).offset(x: 8, y: -8) }
                        }
                    }
                }
                .accessibilityLabel("Read unread papers with AI")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button { app.path.append(Route.settings) } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
            }
            ToolbarItem(placement: .bottomBar) {
                // The way in for new papers, a glass button over the list.
                Menu {
                    Button { showScanner = true } label: { Label("Scan", systemImage: "doc.viewfinder") }
                    Button { showPhotos = true } label: { Label("Photos", systemImage: "photo") }
                    Button { showFiles = true } label: { Label("Choose files", systemImage: "folder") }
                } label: { Label("Add", systemImage: "plus") }
                .buttonStyle(.glassProminent)
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            DefaultToolbarItem(kind: .search, placement: .bottomBar)
        }
    }

    // MARK: Chips

    @ViewBuilder
    private func chips(_ all: [Paper]) -> some View {
        @Bindable var lib = app.library
        ScrollView(.horizontal, showsIndicators: false) {
            GlassEffectContainer(spacing: 8) {
                HStack(spacing: 8) {
                    // The view, one tap away.
                    Picker("View", selection: $lib.style) {
                        ForEach(ListStyle.allCases) { s in Image(systemName: s.symbol).accessibilityLabel(s.label).tag(s) }
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 168)
                    Menu {
                        Picker("Sort", selection: $lib.filters.sort) {
                            Text(lib.query.isEmpty ? "Recently added" : "Best match").tag(SortBy?.none)
                            ForEach(SortBy.allCases, id: \.self) { s in Text(s.label).tag(SortBy?.some(s)) }
                        }
                    } label: {
                        Label(lib.filters.sort?.label ?? (lib.query.isEmpty ? "Recently added" : "Best match"), systemImage: "arrow.up.arrow.down")
                            .font(.subheadline)
                    }
                    .buttonStyle(.glass)
                    Button { showFilters = true } label: {
                        Label(lib.filters.activeCount > 0 ? "Filters · \(lib.filters.activeCount)" : "Filters", systemImage: "line.3.horizontal.decrease")
                            .font(.subheadline)
                    }
                    .buttonStyle(lib.filters.activeCount > 0 ? AnyButtonStyle(.glassProminent) : AnyButtonStyle(.glass))
                    ForEach(lib.types(all), id: \.self) { t in
                        chip(t, on: lib.selectedTypes.contains(t), symbol: "doc") { lib.selectedTypes.formSymmetricDifference([t]) }
                    }
                    ForEach(lib.tags(all), id: \.self) { t in
                        chip(t, on: lib.selectedTags.contains(t)) { lib.selectedTags.formSymmetricDifference([t]) }
                    }
                }
                .padding(.vertical, 4)
            }
        }
        .scrollClipDisabled()
    }

    private func chip(_ text: String, on: Bool, symbol: String? = nil, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            if let symbol { Label(text, systemImage: symbol).font(.subheadline) } else { Text(text).font(.subheadline) }
        }
        .buttonStyle(on ? AnyButtonStyle(.glassProminent) : AnyButtonStyle(.glass))
    }

    // MARK: Notices

    @ViewBuilder
    private func notices(_ all: [Paper]) -> some View {
        let lib = app.library
        if let shelved = app.conversations.all?.first?.question, askHidden {
            Notice(text: "AI answer: “\(shelved)”", action: "Show", symbol: "sparkles") { askHidden = false }
        }
        let q = lib.query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty, app.ai.config.isSet, !lib.selecting, q != app.conversations.all?.first?.question {
            Notice(text: "Ask AI: “\(q)”", action: "Ask", symbol: "sparkles") { askHidden = false; app.askAll(q, followUp: false) }
        }
        if !lib.narrowed && !lib.selecting {
            if app.account.email == nil {
                Notice(text: "Sign in to back up your papers to your Google Drive.", action: "Set up", symbol: "icloud.slash") { app.path.append(Route.settings) }
            } else if case .failed(let message) = app.sync.state {
                Notice(text: "Sync failed: \(message)", action: "Settings", symbol: "exclamationmark.icloud") { app.path.append(Route.settings) }
            } else if case .uploading(let done, let total) = app.sync.state, total > 1 {
                Notice(text: "Backing up to Google Drive · \(done + 1) of \(total)", action: nil, symbol: "icloud.and.arrow.up") {}
            } else if let p = app.jobs.readAllProgress {
                Notice(text: "Reading papers with AI · \(p.done + 1) of \(p.total)", action: "Stop", symbol: "sparkles") { app.jobs.cancelReadAll() }
            } else if app.gmail.newCount > 0 {
                Notice(text: app.gmail.newCount == 1 ? "1 paper found in Gmail" : "\(app.gmail.newCount) papers found in Gmail", action: "Review", symbol: "envelope") { app.path.append(Route.gmail) }
            } else {
                let unread = app.store.unread().count
                if unread > 0 {
                    let n = unread == 1 ? "1 paper isn't" : "\(unread) papers aren't"
                    if app.ai.config.isSet {
                        Notice(text: "\(n) read by the AI yet.", action: "Read now", symbol: "sparkles") { app.readUnread() }
                    } else {
                        Notice(text: "\(n) read yet. Add your OpenAI key to fill in titles, amounts and tags.", action: "Add key", symbol: "sparkles") { app.path.append(Route.settings) }
                    }
                }
            }
        }
    }

    // MARK: Papers

    @ViewBuilder
    private func papers(_ results: [Paper]) -> some View {
        let lib = app.library
        let signedIn = app.account.email != nil
        switch lib.style {
        case .cards:
            ForEach(results) { p in row(p) { PaperCard(paper: p, signedIn: signedIn, selected: lib.selection.contains(p.id)) } }
        case .compact:
            ForEach(results) { p in row(p) { CompactRow(paper: p, signedIn: signedIn, selected: lib.selection.contains(p.id)) } }
        case .grid:
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 16) {
                ForEach(results) { p in row(p) { GridTile(paper: p, signedIn: signedIn, selected: lib.selection.contains(p.id)) } }
            }
        case .timeline:
            ForEach(Array(lib.months(results).enumerated()), id: \.offset) { _, m in
                MonthHeader(year: m.year, month: m.month, papers: m.papers)
                ForEach(m.papers) { p in
                    row(p) { CompactRow(paper: p, signedIn: signedIn, selected: lib.selection.contains(p.id), day: Filters.paperDay(p).day) }
                }
            }
        }
    }

    /** A paper: tap to open (or select), long-press to select, swipe-like actions in its menu. */
    private func row(_ p: Paper, @ViewBuilder content: () -> some View) -> some View {
        let lib = app.library
        return Button {
            if lib.selecting { lib.toggle(p.id) } else { app.open(p.id) }
        } label: { content() }
        .buttonStyle(.plain)
        .contextMenu {
            Button { lib.toggle(p.id) } label: { Label("Select", systemImage: "checkmark.circle") }
            Button { app.share(ids: [p.id]) } label: { Label("Share", systemImage: "square.and.arrow.up") }
            Button(role: .destructive) { app.moveToTrash(ids: [p.id]) } label: { Label("Move to trash", systemImage: "trash") }
        }
        .zoomSource(p.id, app.namespace)
    }
}

/** A tappable note above the list: sign in, a failed sync, progress, finds in Gmail, unread papers. */
struct Notice: View {
    let text: String
    let action: String?
    let symbol: String
    let onAction: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: symbol).foregroundStyle(.tint)
            Text(text).font(.subheadline).frame(maxWidth: .infinity, alignment: .leading)
            if let action { Button(action, action: onAction).buttonStyle(.glass) }
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
        .background(.tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

/** What's shown adds up to: count and totals per currency, when searching or filtering. */
struct ResultSummary: View {
    let papers: [Paper]
    var body: some View {
        let totals = Dictionary(grouping: papers.filter { $0.totalCents != nil }, by: { $0.currency ?? "" })
            .sorted { $0.key < $1.key }
            .map { Format.amount($0.value.reduce(0) { $0 + ($1.totalCents ?? 0) }, $0.key.isEmpty ? nil : $0.key) }
        Text(([papers.count == 1 ? "1 paper" : "\(papers.count) papers"] + totals).joined(separator: " · "))
            .font(.footnote).foregroundStyle(.secondary)
    }
}

/** No papers yet: the first one is one tap away. */
struct EmptyLibrary: View {
    let onScan: () -> Void
    let onPick: () -> Void
    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: "doc.text.fill")
                .font(.system(size: 44)).foregroundStyle(.tint)
                .frame(width: 96, height: 96)
                .glassEffect(.regular.tint(.accentColor.opacity(0.25)), in: .circle)
            Text("Your papers live here").font(.title2.weight(.semibold))
            Text("Scan a receipt, a bill or any document, or add PDFs and photos you already have.")
                .multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button(action: onScan) { Label("Scan a paper", systemImage: "doc.viewfinder") }.buttonStyle(.glassProminent)
            Button(action: onPick) { Label("Choose files", systemImage: "folder") }.buttonStyle(.glass)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 48).padding(.horizontal, 24)
    }
}

/** Either glass style, chosen at run time. */
struct AnyButtonStyle: PrimitiveButtonStyle {
    private let make: (Configuration) -> AnyView
    init<S: PrimitiveButtonStyle>(_ style: S) { make = { AnyView(style.makeBody(configuration: $0)) } }
    func makeBody(configuration: Configuration) -> some View { make(configuration) }
}
