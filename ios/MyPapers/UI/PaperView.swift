import PDFKit
import QuickLook
import SwiftUI

/** One paper: what it is, its details and text, the file; everyday actions on a glass toolbar at the bottom. */
struct PaperView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let id: String
    @State private var confirmReset = false
    @State private var askPrompt = false
    @State private var reworking: String?
    @State private var editingText = false
    @State private var editing = false
    @State private var askHidden = false
    @State private var preview: URL?

    var body: some View {
        let _ = app.store.version
        Group {
            if let p = app.store.live(id), p.trashedAt == nil {
                content(p)
            } else {
                // Deleted (here or on another device) while open: nothing to show.
                ContentUnavailableView("This paper was deleted", systemImage: "trash")
            }
        }
        .background(Color(.systemGroupedBackground))
        .zoomDestination(id, app.namespace)
    }

    @ViewBuilder
    private func content(_ p: Paper) -> some View {
        let asking = app.jobs.isAsking(id)
        let transcribing = app.jobs.isTranscribing(id)
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(p.displayTitle).font(.largeTitle.weight(.semibold)).textSelection(.enabled)
                Text(p.subtitle).font(.title3).foregroundStyle(.secondary)
                HStack {
                    AIStatusLine(paper: p)
                    Spacer()
                    if p.aiState == aiFailed, app.ai.config.isSet { Button("Retry") { app.requestReading(id) } }
                    if p.aiState == aiPending { Button("Stop") { app.jobs.cancelRead(id) } }
                }
                if asking && askHidden || transcribing {
                    HStack(spacing: 10) {
                        ProgressView()
                        Text(transcribing ? "Extracting all text… you'll get a notification when it's done" : "Asking the AI: \(app.conversations.papers[id]?.last?.question ?? "")")
                            .font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        Button("Stop") { if transcribing { app.jobs.cancelTranscribe(id) } else { app.jobs.cancelAsk(id) } }
                    }
                }
                ExpiryLabel(paper: p)
                if let dup = p.duplicateOf, let other = app.store.live(dup) { DuplicateBanner(other: other, paperID: id) }
                if let a = p.amountText {
                    Text(a).font(.system(.largeTitle, design: .rounded).weight(.bold)).foregroundStyle(.tint)
                    if let vat = p.vatCents { Text("VAT \(Format.amount(vat, p.currency))").font(.subheadline).foregroundStyle(.secondary) }
                }
                if !p.tags.isEmpty { TagChips(tags: p.tags) }
                if !p.details.isEmpty {
                    DetailsCard(details: p.details, onRework: app.ai.config.isSet && p.fileLocal && !asking ? { reworking = $0 } : nil)
                }
                if !p.transcript.isEmpty { TextCard(markdown: p.transcript) { editingText = true } }
                if !p.notes.isEmpty { Text(p.notes).textSelection(.enabled) }
                PagesPreview(paper: p) { preview = PaperFiles.url(p.id) }
            }
            .padding(.horizontal, 24)
            // Room to scroll the end of the page up past the floating toolbar.
            .padding(.bottom, 120)
        }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button { app.requestReading(id) } label: { Label("Read with AI", systemImage: "sparkles") }
                        .disabled(!app.ai.config.isSet || !p.fileLocal || p.aiState == aiPending)
                    Button { confirmReset = true } label: { Label("Reset and read again", systemImage: "arrow.counterclockwise") }
                        .disabled(!app.ai.config.isSet || !p.fileLocal || p.aiState == aiPending)
                    Button(role: .destructive) { app.moveToTrash(ids: [id]) ; dismiss() } label: { Label("Move to trash", systemImage: "trash") }
                } label: { Image(systemName: "ellipsis") }
            }
            // The everyday actions, on glass at the bottom.
            ToolbarItemGroup(placement: .bottomBar) {
                Menu {
                    Button { app.share(ids: [id]) } label: { Label("Share", systemImage: "square.and.arrow.up") }
                    Button { preview = PaperFiles.url(id) } label: { Label("Open", systemImage: "doc.viewfinder") }.disabled(!p.fileLocal)
                    Button { app.export(ids: [id]) } label: { Label("Export (spreadsheet + file)", systemImage: "tablecells") }
                } label: { Label("Share", systemImage: "square.and.arrow.up") } primaryAction: { app.share(ids: [id]) }
                Button { editing = true } label: { Label("Edit", systemImage: "pencil") }
                Button { app.transcribe(id) } label: { Label(p.transcript.isEmpty ? "Extract all text" : "Extract all text again", systemImage: "doc.plaintext") }
                    .disabled(!app.ai.config.isSet || !p.fileLocal || transcribing)
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) {
                Button {
                    if !app.ai.config.isSet { app.path.append(Route.settings) }
                    else if !p.fileLocal { app.toast("The file isn't on this phone yet") }
                    else if asking { askHidden = false }
                    else { askPrompt = true }
                } label: { Label("Ask AI", systemImage: "sparkles") }
                .buttonStyle(.glassProminent)
            }
        }
        .toolbarTitleDisplayMode(.inline)
        .confirmationDialog("Reset this paper?", isPresented: $confirmReset, titleVisibility: .visible) {
            Button("Reset", role: .destructive) { app.reset(id) }
        } message: {
            Text("Everything read or typed in (title, amounts, tags, details, your corrections) is cleared and the AI reads it again from scratch. Your notes stay.")
        }
        .alert("Ask AI", isPresented: $askPrompt) { AskField { q in askHidden = false; app.ask(id, q) } } message: {
            Text("What should it find in this paper, or change on it? You choose what to save or apply.")
        }
        .alert(reworking.map { "Rework “\($0)”" } ?? "", isPresented: Binding(get: { reworking != nil }, set: { if !$0 { reworking = nil } })) {
            AskField(placeholder: "e.g. as a table, in English, shorter") { q in
                let label = reworking!
                askHidden = false
                app.ask(id, "Rework the \"\(label)\" detail: \(q)", reworks: label)
            }
        } message: { Text("What should the AI do with it? The result replaces this detail when you save it.") }
        .sheet(isPresented: Binding(get: { app.conversations.papers[id] != nil && !askHidden }, set: { if !$0 { if asking { askHidden = true } else { app.conversations.setPaper(id, nil) } } })) {
            AskPaperSheet(paperID: id)
        }
        .sheet(isPresented: $editingText) { TextEditorSheet(paperID: id) }
        .sheet(isPresented: $editing) { NavigationStack { EditPaperView(paperID: id) } }
        .quickLookPreview($preview)
        .onChange(of: app.conversations.papers[id]?.filter(\.done).count) { _, n in if (n ?? 0) > 0 { askHidden = false } }
    }
}

/** A text field and Ask/Cancel for an alert. */
struct AskField: View {
    var placeholder = "e.g. extract the URL"
    let onAsk: (String) -> Void
    @State private var text = ""
    var body: some View {
        TextField(placeholder, text: $text)
        Button("Ask") { let q = text.trimmingCharacters(in: .whitespacesAndNewlines); if !q.isEmpty { onAsk(q) } }
        Button("Cancel", role: .cancel) {}
    }
}

/** "Looks like a duplicate of …": open the other one, keep both, or delete this one. */
struct DuplicateBanner: View {
    @Environment(AppModel.self) private var app
    let other: Paper
    let paperID: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Looks like a duplicate of “\(other.displayTitle)”", systemImage: "doc.on.doc").font(.subheadline)
            HStack {
                Button("Open it") { app.open(other.id) }
                Button("Keep both") { app.store.keepBoth(paperID) }
                Button("Delete this", role: .destructive) { app.moveToTrash(ids: [paperID]) }
            }
            .buttonStyle(.glass)
        }
        .padding(14)
        .background(.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/** The AI's key details: copy all, rework one with the AI, tables as tables, links open. */
struct DetailsCard: View {
    let details: [KeyDetail]
    let onRework: ((String) -> Void)?

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Details").font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
                Spacer()
                Button {
                    UIPasteboard.general.string = details.map { $0.value.contains("\n") ? "\($0.label):\n\($0.value)" : "\($0.label): \(shownDate($0.value))" }.joined(separator: "\n")
                } label: { Image(systemName: "doc.on.doc") }.accessibilityLabel("Copy details")
            }
            DetailList(details: details, onRework: onRework)
        }
        .padding(18)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/** Label over value, tables as tables. Any part can be selected; web addresses open. */
struct DetailList: View {
    let details: [KeyDetail]
    var onRework: ((String) -> Void)? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(details, id: \.self) { d in
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(d.label).font(.caption).foregroundStyle(.secondary)
                        if let onRework {
                            Button { onRework(d.label) } label: { Image(systemName: "sparkles").font(.caption) }
                                .accessibilityLabel("Rework \(d.label) with AI")
                        }
                    }
                    if let rows = tableRows(d.value) { DetailTable(rows: rows) } else { Text(linked(shownDate(d.value))).textSelection(.enabled) }
                }
            }
        }
    }
}

/** A date the AI wrote as 2027-08-15, in the region's format. */
func shownDate(_ value: String) -> String {
    Day(iso: value) != nil && value.count == 10 ? (Format.date(value) ?? value) : value
}

/** Web addresses in [text] as links. */
func linked(_ text: String) -> AttributedString {
    var s = AttributedString(text)
    guard let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) else { return s }
    for m in detector.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
        guard let url = m.url, let r = Range(m.range, in: text), let ar = Range(r, in: s) else { continue }
        s[ar].link = url
    }
    return s
}

/** A table detail: header row bold, lines between rows, scrolls sideways; a Hebrew table runs right to left. */
struct DetailTable: View {
    let rows: [[String]]
    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            Grid(alignment: .leading, horizontalSpacing: 14, verticalSpacing: 6) {
                ForEach(rows.indices, id: \.self) { r in
                    if r > 0 { Divider() }
                    GridRow {
                        ForEach(rows[r].indices, id: \.self) { c in
                            Text(linked(rows[r][c])).font(r == 0 ? .subheadline.weight(.semibold) : .subheadline)
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        }
        .environment(\.layoutDirection, isRtl(rows.flatMap { $0 }.joined()) ? .rightToLeft : .leftToRight)
    }
}

/** The paper written out ("Extract all text"): the start of it, or all of it; copy; edit. */
struct TextCard: View {
    let markdown: String
    let onEdit: () -> Void
    @State private var all = false

    var body: some View {
        let lines = markdown.split(separator: "\n", omittingEmptySubsequences: false)
        let long = lines.count > 12
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Text").font(.subheadline.weight(.semibold)).foregroundStyle(.tint)
                Spacer()
                Button(action: onEdit) { Image(systemName: "pencil") }.accessibilityLabel("Edit text")
                Button { UIPasteboard.general.string = markdown } label: { Image(systemName: "doc.on.doc") }.accessibilityLabel("Copy text")
            }
            MarkdownView(text: long && !all ? lines.prefix(12).joined(separator: "\n") : markdown)
            if long { Button(all ? "Show less" : "Show all") { withAnimation { all.toggle() } } }
        }
        .padding(18)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/** The file's pages, tap to open full screen (Quick Look: pinch, swipe through pages). */
struct PagesPreview: View {
    let paper: Paper
    let onOpen: () -> Void

    var body: some View {
        if !paper.fileLocal {
            Label("Downloading…", systemImage: "icloud.and.arrow.down")
                .frame(maxWidth: .infinity, minHeight: 240)
                .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        } else {
            Button(action: onOpen) { PageImage(paper: paper) }
            .buttonStyle(.plain)
            .accessibilityLabel("Open the file full screen")
        }
    }
}

/** The first page whole, at its own shape, on white like paper. */
struct PageImage: View {
    let paper: Paper
    @State private var image: UIImage?

    var body: some View {
        Group {
            if let image {
                Image(uiImage: image).resizable().scaledToFit().background(.white)
            } else {
                Color(.tertiarySystemFill).frame(height: 320).overlay { ProgressView() }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .task(id: "\(paper.id):\(paper.fileHash ?? "")") {
            image = await Thumbnails.shared.image(for: paper.id, hash: paper.fileHash ?? "", pdf: PaperFiles.isPDF(paper), pixels: 1600)
        }
    }
}
