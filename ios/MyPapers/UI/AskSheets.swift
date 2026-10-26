import SwiftUI

/**
 * Ask AI about one paper, as a conversation: each question with the reply, what it found (save to
 * details) and what it would change (apply), then a field for a follow-up.
 */
struct AskPaperSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let paperID: String

    var body: some View {
        let turns = app.conversations.papers[paperID] ?? []
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(turns.enumerated()), id: \.offset) { n, t in
                        if n > 0 { Divider() }
                        Text(t.question).font(.title3.weight(.semibold))
                        if !t.done {
                            HStack { Spacer(); ProgressView(); Spacer() }
                            HStack { Spacer(); Button("Stop") { app.jobs.cancelAsk(paperID) }; Spacer() }
                        } else if let e = t.error {
                            Text("Couldn't ask: \(e)").foregroundStyle(.red)
                        } else {
                            if let r = t.reply, !r.isEmpty { Text(r).textSelection(.enabled) }
                            if let d = t.details, !d.isEmpty {
                                DetailList(details: d)
                                Button(t.saved ? "Saved to details" : "Save to details") {
                                    mark(n) { $0.saved = true }
                                    app.store.saveDetails(paperID, d, replacing: t.reworks)
                                }
                                .buttonStyle(.glass).disabled(t.saved)
                            }
                            if let c = t.changes, let p = app.store.live(paperID) { ChangesCard(paper: p, changes: c, applied: t.applied) { mark(n) { $0.applied = true }; app.store.applyChanges(paperID, c) } }
                        }
                    }
                    if turns.last?.done == true {
                        FollowUpField(placeholder: "Follow up, or tell it what to change") { app.ask(paperID, $0) }
                    }
                }
                .padding(20)
            }
            .navigationTitle(app.store.live(paperID)?.displayTitle ?? "Ask AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { app.conversations.setPaper(paperID, nil); dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private func mark(_ n: Int, _ change: (inout PaperTurn) -> Void) {
        var turns = app.conversations.papers[paperID] ?? []
        guard n < turns.count else { return }
        change(&turns[n])
        app.conversations.setPaper(paperID, turns)
    }
}

/** What the AI would change on the paper, before → after, with the button that does it. */
struct ChangesCard: View {
    let paper: Paper
    let changes: PaperChanges
    let applied: Bool
    let onApply: () -> Void

    var body: some View {
        let c = changes
        VStack(alignment: .leading, spacing: 6) {
            Text("Changes to this paper").font(.subheadline.weight(.semibold))
            if let v = c.title { Text("Title: \(paper.title.isEmpty ? "—" : paper.title) → \(v)") }
            if let v = c.vendor { Text("Vendor: \(paper.vendor.isEmpty ? "—" : paper.vendor) → \(v)") }
            if let v = c.docType { Text("Type: \(paper.docType.isEmpty ? "—" : paper.docType) → \(v)") }
            if let v = c.date { Text("Date: \(Format.date(paper.date) ?? "—") → \(Format.date(v) ?? v)") }
            if let t = c.total { Text("Amount: \(paper.amountText ?? "—") → \(Format.amount(Int64((t * 100).rounded()), c.currency ?? paper.currency))") }
            if c.total == nil, let v = c.currency { Text("Currency: \(paper.currency ?? "—") → \(v)") }
            if !c.addTags.isEmpty { Text("Add tags: \(c.addTags.joined(separator: ", "))") }
            if !c.removeTags.isEmpty { Text("Remove tags: \(c.removeTags.joined(separator: ", "))") }
            if !c.removeDetails.isEmpty { Text("Remove details: \(c.removeDetails.joined(separator: ", "))") }
            Button(applied ? "Applied" : "Apply changes", action: onApply).buttonStyle(.glassProminent).disabled(applied).padding(.top, 4)
        }
        .font(.subheadline)
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.tint.opacity(0.10), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }
}

/** A conversation about all the papers: each answer with its papers to open, and a follow-up field. */
struct AskAllSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let onOpen: (String) -> Void

    var body: some View {
        let turns = app.conversations.all ?? []
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(turns.enumerated()), id: \.offset) { n, t in
                        if n > 0 { Divider() }
                        Text(t.question).font(.title3.weight(.semibold))
                        if !t.done {
                            HStack { Spacer(); ProgressView(); Spacer() }
                            HStack { Spacer(); Button("Stop") { app.jobs.cancelAskAll() }; Spacer() }
                        } else if let e = t.error {
                            Text("Couldn't ask: \(e)").foregroundStyle(.red)
                        } else {
                            Text(t.answer ?? "").textSelection(.enabled)
                            // Added up here, not by the AI.
                            if let totals = t.totals, !totals.isEmpty {
                                let papers = t.paperIds.compactMap { app.store.live($0) }
                                Text("Total: " + totals.sorted { $0.key < $1.key }.map { Format.amount($0.value, $0.key.isEmpty ? nil : $0.key) }.joined(separator: " · "))
                                    .font(.headline).foregroundStyle(.tint)
                                let counted = papers.filter { $0.totalCents != nil }.count
                                Text(counted == 1 ? "From 1 paper" : "From \(counted) papers").font(.caption).foregroundStyle(.secondary)
                            }
                            let papers = t.paperIds.compactMap { app.store.live($0) }
                            if !papers.isEmpty {
                                Text("From your papers").font(.subheadline.weight(.semibold)).foregroundStyle(.tint).padding(.top, 8)
                                ForEach(papers) { p in
                                    Button { onOpen(p.id) } label: {
                                        HStack {
                                            VStack(alignment: .leading) {
                                                Text(p.displayTitle).foregroundStyle(.primary)
                                                Text(p.subtitle).font(.caption).foregroundStyle(.secondary)
                                            }
                                            Spacer()
                                            if let a = p.amountText { Text(a).foregroundStyle(.primary) }
                                        }
                                    }
                                    Divider()
                                }
                            }
                        }
                    }
                    if turns.last?.done == true { FollowUpField(placeholder: "Ask a follow-up") { app.askAll($0, followUp: true) } }
                    Text("Answers can be wrong — check the papers.").font(.caption).foregroundStyle(.secondary)
                }
                .padding(20)
            }
            .navigationTitle("Your papers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { app.closeAskAll(); dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/** A line to type the next question in, sent with the button or the keyboard. */
struct FollowUpField: View {
    let placeholder: String
    let onSend: (String) -> Void
    @State private var text = ""

    var body: some View {
        HStack {
            TextField(placeholder, text: $text, axis: .vertical).onSubmit(send)
            Button(action: send) { Image(systemName: "arrow.up.circle.fill").font(.title2) }
                .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                .accessibilityLabel("Send")
        }
        .padding(.leading, 16).padding(.trailing, 8).padding(.vertical, 8)
        .glassEffect(.regular.interactive(), in: .capsule)
    }

    private func send() {
        let q = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        onSend(q)
        text = ""
    }
}
