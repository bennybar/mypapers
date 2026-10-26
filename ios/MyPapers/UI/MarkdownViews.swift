import SwiftUI

/**
 * The Markdown the AI writes a paper out in: headings, lists, **bold**, tables and paragraphs, all in
 * the direction of the whole text (a Hebrew page right to left, its lines of numbers or [?] included).
 */
struct MarkdownView: View {
    let text: String

    var body: some View {
        let rtl = isRtl(text)
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(MarkdownBlocks.parse(text).enumerated()), id: \.offset) { _, block in
                switch block {
                case .heading(let level, let s): Text(inline(s)).font(level == 1 ? .title2.bold() : .title3.weight(.semibold)).padding(.top, 6)
                case .bullet(let s): Text(inline("•  " + s))
                case .table(let rows): DetailTable(rows: rows)
                case .paragraph(let s): Text(inline(s))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
        .environment(\.layoutDirection, rtl ? .rightToLeft : .leftToRight)
    }

    /** **bold** and links, via the system's inline Markdown. */
    private func inline(_ s: String) -> AttributedString {
        let a = (try? AttributedString(markdown: s, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(s)
        return a.characters.isEmpty ? AttributedString(s) : a
    }
}

enum MarkdownBlocks {
    enum Block { case heading(Int, String), bullet(String), table([[String]]), paragraph(String) }

    static func parse(_ text: String) -> [Block] {
        let lines = text.components(separatedBy: "\n")
        var out: [Block] = []
        var i = 0
        while i < lines.count {
            let line = lines[i].trimmingCharacters(in: .whitespaces)
            if line.isEmpty { i += 1; continue }
            if line.hasPrefix("|") {
                let start = i
                while i < lines.count, lines[i].trimmingCharacters(in: .whitespaces).hasPrefix("|") { i += 1 }
                let chunk = lines[start..<i].joined(separator: "\n")
                out.append(tableRows(chunk).map(Block.table) ?? .paragraph(chunk))
                continue
            }
            if line.hasPrefix("#") {
                let level = line.prefix { $0 == "#" }.count
                out.append(.heading(level, line.dropFirst(level).trimmingCharacters(in: .whitespaces)))
            } else if line.hasPrefix("- ") || line.hasPrefix("* ") {
                out.append(.bullet(String(line.dropFirst(2))))
            } else {
                out.append(.paragraph(line))
            }
            i += 1
        }
        return out
    }
}

/**
 * Markdown ↔ formatted text for the editor: headings as bigger bold text, **bold** as bold, bullets as
 * "• " lines, numbered lines as they are, and table rows as lines with "│" between cells (the editor knows
 * no tables; they come back as a Markdown table).
 */
enum RichMarkdown {
    static let bar = "│"
    static let h1 = Font.title2.bold()
    static let h2 = Font.title3.bold()

    static func toRich(_ markdown: String) -> AttributedString {
        var out = AttributedString()
        let lines = markdown.components(separatedBy: "\n").filter { line in
            let t = line.trimmingCharacters(in: .whitespaces)
            return !(t.hasPrefix("|") && t.contains("-") && t.allSatisfy { "|-: ".contains($0) })
        }
        for (n, raw) in lines.enumerated() {
            let line = raw.trimmingCharacters(in: .whitespaces)
            var para: AttributedString
            if line.hasPrefix("|") {
                para = AttributedString(line.replacingOccurrences(of: "|", with: bar))
            } else if line.hasPrefix("#") {
                let level = line.prefix { $0 == "#" }.count
                para = inline(line.dropFirst(level).trimmingCharacters(in: .whitespaces))
                para.font = level == 1 ? h1 : h2
            } else if line.hasPrefix("- ") || line.hasPrefix("* ") {
                para = AttributedString("• ") + inline(String(line.dropFirst(2)))
            } else {
                para = inline(line)
            }
            out += para
            if n < lines.count - 1 { out += AttributedString("\n") }
        }
        return out
    }

    /** **bold** as bold (the editor's own bold), the rest plain. */
    private static func inline(_ s: String) -> AttributedString {
        var out = AttributedString()
        for (i, part) in s.components(separatedBy: "**").enumerated() {
            var a = AttributedString(part)
            if i % 2 == 1 { a.inlinePresentationIntent = .stronglyEmphasized }
            out += a
        }
        return out
    }

    static func toMarkdown(_ rich: AttributedString) -> String {
        // Line by line, from the runs: a heading's font, bold runs, bullets, table rows.
        var lines: [String] = []
        var line = ""
        var heading = 0
        func flush() {
            var l = line
            if heading > 0 { l = String(repeating: "#", count: heading) + " " + l.replacingOccurrences(of: "**", with: "") }
            lines.append(l)
            line = ""
            heading = 0
        }
        for run in rich.runs {
            let text = String(rich[run.range].characters)
            let bold = run.inlinePresentationIntent?.contains(.stronglyEmphasized) == true || isBoldFont(run.font)
            if run.font == h1 { heading = 1 } else if run.font == h2, heading == 0 { heading = 2 }
            let parts = text.components(separatedBy: "\n")
            for (k, part) in parts.enumerated() {
                if k > 0 { flush() }
                if part.isEmpty { continue }
                line += bold && heading == 0 && !part.trimmingCharacters(in: .whitespaces).isEmpty ? "**\(part)**" : part
            }
        }
        flush()
        // Bullets, and table rows back together under a header line.
        var out: [String] = []
        var inTable = false
        for l in lines {
            let t = l.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix(bar) {
                let cells = t.replacingOccurrences(of: bar, with: "|")
                if !inTable {
                    out.append(cells)
                    out.append("|" + String(repeating: "---|", count: max(1, cells.filter { $0 == "|" }.count - 1)))
                } else {
                    out.append(cells)
                }
                inTable = true
                continue
            }
            if inTable && !t.isEmpty { out.append("") }
            inTable = false
            out.append(t.hasPrefix("• ") ? "- " + t.dropFirst(2) : l)
        }
        return out.joined(separator: "\n").replacingOccurrences(of: "\\n{3,}", with: "\n\n", options: .regularExpression)
            .replacingOccurrences(of: "****", with: "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func isBoldFont(_ f: Font?) -> Bool { false }
}

/** The paper's text, edited as it looks: bold, a title, headings and bullets from the toolbar. */
struct TextEditorSheet: View {
    @Environment(AppModel.self) private var app
    @Environment(\.dismiss) private var dismiss
    let paperID: String
    @State private var text = AttributedString()
    @State private var selection = AttributedTextSelection()
    @State private var confirmDelete = false

    var body: some View {
        NavigationStack {
            TextEditor(text: $text, selection: $selection)
                .padding(.horizontal, 12)
                .environment(\.layoutDirection, isRtl(String(text.characters)) ? .rightToLeft : .leftToRight)
                .navigationTitle("Edit text")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Save") { app.store.saveTranscript(paperID, RichMarkdown.toMarkdown(text)); dismiss() }
                    }
                    ToolbarItemGroup(placement: .bottomBar) {
                        Button { toggleBold() } label: { Image(systemName: "bold") }.accessibilityLabel("Bold")
                        Button { setHeading(RichMarkdown.h1) } label: { Image(systemName: "textformat.size.larger") }.accessibilityLabel("Title")
                        Button { setHeading(RichMarkdown.h2) } label: { Image(systemName: "textformat.size") }.accessibilityLabel("Heading")
                        Button { toggleBullet() } label: { Image(systemName: "list.bullet") }.accessibilityLabel("Bulleted list")
                        Spacer()
                        Button(role: .destructive) { confirmDelete = true } label: { Image(systemName: "trash") }.accessibilityLabel("Delete text")
                    }
                }
                .confirmationDialog("Delete the text?", isPresented: $confirmDelete, titleVisibility: .visible) {
                    Button("Delete", role: .destructive) { app.store.saveTranscript(paperID, ""); dismiss() }
                } message: { Text("You can extract it again from the paper's toolbar.") }
        }
        .onAppear { text = RichMarkdown.toRich(app.store.live(paperID)?.transcript ?? "") }
    }

    private func toggleBold() {
        text.transformAttributes(in: &selection) { c in
            c.inlinePresentationIntent = c.inlinePresentationIntent?.contains(.stronglyEmphasized) == true ? nil : .stronglyEmphasized
        }
    }

    private func setHeading(_ font: Font) {
        text.transformAttributes(in: &selection) { c in c.font = c.font == font ? nil : font }
    }

    /** "• " at the start of each selected line, or off again. */
    private func toggleBullet() {
        let s = String(text.characters)
        guard case .ranges(let ranges) = selection.indices(in: text), let r = ranges.ranges.first else { return }
        let lower = s.index(s.startIndex, offsetBy: text.characters.distance(from: text.startIndex, to: r.lowerBound))
        let lineStart = s[..<lower].lastIndex(of: "\n").map { s.index(after: $0) } ?? s.startIndex
        let offset = s.distance(from: s.startIndex, to: lineStart)
        let at = text.characters.index(text.startIndex, offsetBy: offset)
        if s[lineStart...].hasPrefix("• ") {
            text.removeSubrange(at..<text.characters.index(at, offsetBy: 2))
        } else {
            text.insert(AttributedString("• "), at: at)
        }
    }
}
