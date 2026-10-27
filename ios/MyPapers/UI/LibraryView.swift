import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/**
 * The library as a native list: notices, then the papers in the chosen view, with swipe actions and
 * the system's selection. View, sort and filters live in one glass menu in the toolbar; adding papers
 * in another. In the Search tab ([searching]) it shows matches and offers to ask the AI.
 */
struct LibraryView: View {
    @Environment(AppModel.self) private var app
    var searching = false
    @State private var showFilters = false
    @State private var showFiles = false
    @State private var showScanner = false
    @State private var photoItems: [PhotosPickerItem] = []
    @State private var showPhotos = false
    @State private var askHidden = false
    @State private var tagPrompt = false
    @State private var newTag = ""
    @State private var typePrompt = false
    @State private var editMode: EditMode = .inactive

    var body: some View {
        @Bindable var lib = app.library
        let _ = app.store.version
        let all = app.store.all()
        let results = lib.results(all)

        content(all, results)
            .navigationTitle(searching ? "Search" : (editMode.isEditing ? "\(lib.selection.count) selected" : "My Papers"))
            .navigationSubtitle(searching || editMode.isEditing ? "" : (lib.narrowed ? "\(results.count) shown" : (all.count == 1 ? "1 paper" : "\(all.count) papers")))
            .toolbar { toolbar(all, results) }
            .environment(\.editMode, $editMode)
            // In Select mode the actions bar takes the tab bar's place.
            .toolbar(editMode.isEditing ? .hidden : .visible, for: .tabBar)
            .onChange(of: editMode) { _, m in if !m.isEditing { lib.selection = [] } }
            .refreshable { await app.syncNow() }
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
            .sheet(isPresented: Binding(get: { app.conversations.all != nil && !askHidden && searching == (app.tab == .search) }, set: { if !$0 { app.closeAskAll() } })) {
                AskAllSheet(onOpen: { id in askHidden = true; app.open(id) })
            }
            .alert("Add tag", isPresented: $tagPrompt) {
                TextField("Tag", text: $newTag)
                Button("Add") { app.store.addTag(Array(lib.selection), newTag); newTag = ""; editMode = .inactive }
                Button("Cancel", role: .cancel) {}
            }
            .confirmationDialog("Set type", isPresented: $typePrompt) {
                ForEach(documentTypes, id: \.self) { t in Button(t) { app.store.setType(Array(lib.selection), t); editMode = .inactive } }
            }
    }

    // MARK: Content

    @ViewBuilder
    private func content(_ all: [Paper], _ results: [Paper]) -> some View {
        @Bindable var lib = app.library
        if all.isEmpty && !searching {
            EmptyLibrary(onScan: { showScanner = true }, onPick: { showFiles = true })
                .frame(maxHeight: .infinity)
                .background(Color(.systemGroupedBackground))
        } else if lib.style == .grid && !searching {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    noticeCards(all)
                    activeFilters
                    LazyVGrid(columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)], spacing: 18) {
                        ForEach(results) { p in
                            Button { tap(p) } label: { GridTile(paper: p, signedIn: app.account.email != nil, selected: lib.selection.contains(p.id)) }
                                .buttonStyle(.plain)
                                .contextMenu { menu(p) }
                                .zoomSource(p.id, app.namespace)
                        }
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 24)
            }
            .background(Color(.systemGroupedBackground))
        } else {
            // Selection only in Select mode: otherwise iOS would take a tap as "select" and never open the paper.
            List(selection: Binding(get: { editMode.isEditing ? lib.selection : [] }, set: { if editMode.isEditing { lib.selection = $0 } })) {
                if !searching { notices(all) }
                if searching { askRow }
                if lib.narrowed && !results.isEmpty {
                    Section { } footer: { ResultSummary(papers: results) }
                }
                if !searching { activeFilters.listRowBackground(Color.clear).listRowInsets(EdgeInsets()) }
                if results.isEmpty {
                    ContentUnavailableView.search(text: lib.query).listRowBackground(Color.clear)
                } else if lib.style == .timeline && !searching {
                    ForEach(Array(lib.months(results).enumerated()), id: \.offset) { _, m in
                        Section {
                            ForEach(m.papers) { p in row(p) { CompactRow(paper: p, signedIn: app.account.email != nil, day: Filters.paperDay(p).day) } }
                        } header: { MonthHeader(year: m.year, month: m.month, papers: m.papers) }
                    }
                } else {
                    Section {
                        ForEach(results) { p in
                            if lib.style == .compact || searching {
                                row(p) { CompactRow(paper: p, signedIn: app.account.email != nil) }
                            } else {
                                row(p) { PaperRow(paper: p, signedIn: app.account.email != nil) }
                            }
                        }
                    }
                }
            }
            .listStyle(.insetGrouped)
        }
    }

    /** A paper in the list: tap to open, swipe to share or trash, long-press for more. */
    private func row(_ p: Paper, @ViewBuilder content: () -> some View) -> some View {
        Button { tap(p) } label: { content() }
            .tint(.primary)
            .tag(p.id)
            .swipeActions(edge: .leading) {
                Button { app.share(ids: [p.id]) } label: { Label("Share", systemImage: "square.and.arrow.up") }.tint(.blue)
            }
            .swipeActions(edge: .trailing) {
                Button(role: .destructive) { app.moveToTrash(ids: [p.id]) } label: { Label("Trash", systemImage: "trash") }
            }
            .contextMenu { menu(p) }
            .zoomSource(p.id, app.namespace)
    }

    private func tap(_ p: Paper) {
        if editMode.isEditing { app.library.toggle(p.id) } else { app.open(p.id) }
    }

    @ViewBuilder
    private func menu(_ p: Paper) -> some View {
        Button { app.share(ids: [p.id]) } label: { Label("Share", systemImage: "square.and.arrow.up") }
        if app.ai.config.isSet { Button { app.requestReading(p.id) } label: { Label("Read with AI", systemImage: "sparkles") } }
        Button(role: .destructive) { app.moveToTrash(ids: [p.id]) } label: { Label("Move to Trash", systemImage: "trash") }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private func toolbar(_ all: [Paper], _ results: [Paper]) -> some ToolbarContent {
        let lib = app.library
        if editMode.isEditing {
            ToolbarItem(placement: .topBarLeading) { Button("Done") { editMode = .inactive } }
            ToolbarItem(placement: .topBarTrailing) { Button("Select All") { lib.selection = Set(results.map(\.id)) } }
            ToolbarItemGroup(placement: .bottomBar) {
                Button { app.share(ids: Array(lib.selection)) } label: { Label("Share", systemImage: "square.and.arrow.up") }
                Spacer()
                Menu {
                    Button("Add Tag…", systemImage: "tag") { tagPrompt = true }
                    Button("Set Type…", systemImage: "doc") { typePrompt = true }
                    Button("Export", systemImage: "tablecells") { app.export(ids: Array(lib.selection)) }
                    if app.ai.config.isSet { Button("Read with AI", systemImage: "sparkles") { app.read(ids: Array(lib.selection)); editMode = .inactive } }
                } label: { Label("More", systemImage: "ellipsis") }
                Spacer()
                Button(role: .destructive) { app.moveToTrash(ids: Array(lib.selection)); editMode = .inactive } label: { Label("Trash", systemImage: "trash") }
            }
        } else if !searching {
            ToolbarItem(placement: .topBarLeading) {
                Button("Select") { editMode = .active }.disabled(all.isEmpty)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                filterMenu(all)
                Menu {
                    Button { showScanner = true } label: { Label("Scan", systemImage: "doc.viewfinder") }
                    Button { showPhotos = true } label: { Label("Photos", systemImage: "photo.on.rectangle") }
                    Button { showFiles = true } label: { Label("Files", systemImage: "folder") }
                } label: { Label("Add", systemImage: "plus") }
            }
        }
    }

    /** View, sort, types and tags in one menu; the rest in the filter sheet. */
    private func filterMenu(_ all: [Paper]) -> some View {
        @Bindable var lib = app.library
        return Menu {
            Picker("View", selection: $lib.style) {
                ForEach(ListStyle.allCases) { s in Label(s.label, systemImage: s.symbol).tag(s) }
            }
            .pickerStyle(.palette)
            Picker(selection: $lib.filters.sort) {
                Text("Recently Added").tag(SortBy?.none)
                ForEach(SortBy.allCases, id: \.self) { s in Text(s.label).tag(SortBy?.some(s)) }
            } label: { Label("Sort", systemImage: "arrow.up.arrow.down") }
            .pickerStyle(.menu)
            let types = lib.types(all)
            if !types.isEmpty {
                Section("Type") {
                    ForEach(types, id: \.self) { t in
                        Toggle(t, isOn: Binding(get: { lib.selectedTypes.contains(t) }, set: { _ in lib.selectedTypes.formSymmetricDifference([t]) }))
                    }
                }
            }
            let tags = lib.tags(all)
            if !tags.isEmpty {
                Menu {
                    ForEach(tags, id: \.self) { t in
                        Toggle(t, isOn: Binding(get: { lib.selectedTags.contains(t) }, set: { _ in lib.selectedTags.formSymmetricDifference([t]) }))
                    }
                } label: { Label("Tags", systemImage: "tag") }
            }
            Button { showFilters = true } label: { Label("More Filters…", systemImage: "slider.horizontal.3") }
        } label: {
            Label("Filter", systemImage: lib.narrowed ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle")
        }
    }

    /** What's narrowing the list, each removable, and Clear. */
    @ViewBuilder
    private var activeFilters: some View {
        let lib = app.library
        let chips = Array(lib.selectedTypes).sorted() + Array(lib.selectedTags).sorted()
        if !chips.isEmpty || lib.filters.activeCount > 0 {
            ScrollView(.horizontal, showsIndicators: false) {
                GlassEffectContainer(spacing: 6) {
                    HStack(spacing: 6) {
                        ForEach(chips, id: \.self) { c in
                            Button { lib.selectedTypes.remove(c); lib.selectedTags.remove(c) } label: { Label(c, systemImage: "xmark").font(.subheadline) }
                                .buttonStyle(.glass)
                        }
                        if lib.filters.activeCount > 0 {
                            Button { showFilters = true } label: { Label("\(lib.filters.activeCount) more", systemImage: "slider.horizontal.3").font(.subheadline) }
                                .buttonStyle(.glass)
                        }
                        Button("Clear") {
                            lib.selectedTypes = []; lib.selectedTags = []
                            lib.filters = Filters(sort: lib.filters.sort)
                        }
                        .buttonStyle(.glass)
                    }
                    .padding(.horizontal, 16).padding(.vertical, 4)
                }
            }
        }
    }

    // MARK: Notices

    private struct Note: Identifiable {
        let id: String
        let text: String
        let action: String?
        let symbol: String
        let run: () -> Void
    }

    /** One note at a time, the most important: sign in, a failed sync, progress, Gmail finds, unread papers. */
    private func note(_ all: [Paper]) -> Note? {
        let lib = app.library
        if let shelved = app.conversations.all?.first?.question, askHidden {
            return Note(id: "answer", text: "AI answer: “\(shelved)”", action: "Show", symbol: "sparkles") { askHidden = false }
        }
        guard !lib.narrowed, !editMode.isEditing else { return nil }
        if app.account.email == nil {
            return Note(id: "account", text: "Sign in to back up your papers to your Google Drive.", action: "Set Up", symbol: "icloud.slash") { app.tab = .settings }
        }
        if case .failed(let message) = app.sync.state {
            return Note(id: "sync", text: "Sync failed: \(message)", action: "Settings", symbol: "exclamationmark.icloud") { app.tab = .settings }
        }
        if case .uploading(let done, let total) = app.sync.state, total > 1 {
            return Note(id: "upload", text: "Backing up to Google Drive · \(done + 1) of \(total)", action: nil, symbol: "icloud.and.arrow.up") {}
        }
        if let p = app.jobs.readAllProgress {
            return Note(id: "reading", text: "Reading papers with AI · \(p.done + 1) of \(p.total)", action: "Stop", symbol: "sparkles") { app.jobs.cancelReadAll() }
        }
        if app.gmail.newCount > 0 {
            return Note(id: "gmail", text: app.gmail.newCount == 1 ? "1 paper found in Gmail" : "\(app.gmail.newCount) papers found in Gmail", action: "Review", symbol: "envelope") { app.tab = .gmail }
        }
        let unread = app.store.unread().count
        if unread > 0 {
            let n = unread == 1 ? "1 paper isn't" : "\(unread) papers aren't"
            return app.ai.config.isSet
                ? Note(id: "unread", text: "\(n) read by the AI yet.", action: "Read Now", symbol: "sparkles") { app.readUnread() }
                : Note(id: "unread", text: "\(n) read yet. Add your OpenAI key to fill in titles, amounts and tags.", action: "Add Key", symbol: "sparkles") { app.tab = .settings }
        }
        return nil
    }

    @ViewBuilder
    private func notices(_ all: [Paper]) -> some View {
        if let n = note(all) {
            Section {
                HStack(spacing: 12) {
                    Image(systemName: n.symbol).foregroundStyle(.tint).font(.title3)
                    Text(n.text).font(.subheadline)
                    Spacer(minLength: 4)
                    if let a = n.action { Button(a, action: n.run).buttonStyle(.glassProminent).controlSize(.small) }
                }
            }
        }
    }

    @ViewBuilder
    private func noticeCards(_ all: [Paper]) -> some View {
        if let n = note(all) {
            HStack(spacing: 12) {
                Image(systemName: n.symbol).foregroundStyle(.tint).font(.title3)
                Text(n.text).font(.subheadline)
                Spacer(minLength: 4)
                if let a = n.action { Button(a, action: n.run).buttonStyle(.glassProminent).controlSize(.small) }
            }
            .padding(14)
            .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        }
    }

    /** In Search: the typed words as a question for the AI about all the papers. */
    @ViewBuilder
    private var askRow: some View {
        let q = app.library.query.trimmingCharacters(in: .whitespaces)
        if !q.isEmpty && app.ai.config.isSet {
            Section {
                Button { askHidden = false; app.askAll(q, followUp: false) } label: {
                    Label { Text("Ask AI: “\(q)”").foregroundStyle(.primary) } icon: { Image(systemName: "sparkles") }
                }
            } footer: { Text("Answers from all your papers, with the ones it used.") }
        }
    }
}

/** A paper in the cards view: title and amount, what it is, tags, expiry and AI state. */
struct PaperRow: View {
    let paper: Paper
    let signedIn: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(paper.displayTitle).font(.headline).lineLimit(1)
                Spacer(minLength: 8)
                if let a = paper.amountText { Text(a).font(.headline).monospacedDigit() }
            }
            HStack(spacing: 6) {
                Text(paper.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                Spacer(minLength: 4)
                if signedIn { CloudMark(paper: paper) }
            }
            ExpiryLabel(paper: paper)
            if !paper.tags.isEmpty { TagChips(tags: paper.tags).padding(.top, 2) }
            AIStatusLine(paper: paper)
        }
        .padding(.vertical, 4)
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
    }
}

/** No papers yet: the first one is one tap away. */
struct EmptyLibrary: View {
    let onScan: () -> Void
    let onPick: () -> Void
    var body: some View {
        ContentUnavailableView {
            Label("Your papers live here", systemImage: "doc.text.fill")
        } description: {
            Text("Scan a receipt, a bill or any document, or add PDFs and photos you already have.")
        } actions: {
            Button(action: onScan) { Label("Scan a Paper", systemImage: "doc.viewfinder") }.buttonStyle(.glassProminent)
            Button(action: onPick) { Label("Choose Files", systemImage: "folder") }.buttonStyle(.glass)
        }
    }
}
