import SwiftUI

/** Details as they read in the app, on a white page: the paper's title, then each label and value. */
struct DetailsPage: View {
    let title: String
    let subtitle: String
    let details: [KeyDetail]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.title2.weight(.bold))
                if !subtitle.isEmpty { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
            }
            ForEach(details.indices, id: \.self) { i in
                if i > 0 { Divider() }
                VStack(alignment: .leading, spacing: 4) {
                    Text(details[i].label).font(.subheadline).foregroundStyle(.secondary)
                    if let rows = tableRows(details[i].value) { DetailTable(rows: rows, scrolls: false) } else { Text(linked(shownDate(details[i].value))) }
                }
            }
        }
        .padding(36)
        .frame(width: DetailsPage.width, alignment: .leading)
        .background(.white)
        .environment(\.colorScheme, .light)
    }

    /** US Letter's width in points. */
    static let width: CGFloat = 612

    /** Writes this page to [url]: a PDF (one page as tall as the details; vector, so the text stays sharp and selectable) or a JPG. */
    func write(to url: URL, pdf: Bool) -> Bool {
        let renderer = ImageRenderer(content: self)
        renderer.proposedSize = ProposedViewSize(width: Self.width, height: nil)
        guard pdf else {
            renderer.scale = 3
            guard let data = renderer.uiImage?.jpegData(compressionQuality: 0.9) else { return false }
            return (try? data.write(to: url)) != nil
        }
        var made = false
        renderer.render { size, draw in
            var box = CGRect(origin: .zero, size: size)
            guard let context = CGContext(url as CFURL, mediaBox: &box, nil) else { return }
            context.beginPDFPage(nil)
            draw(context)
            context.endPDFPage()
            context.closePDF()
            made = true
        }
        return made
    }
}

extension AppModel {
    /** The paper's [details] as a PDF or a JPG, to the share sheet. */
    func exportDetails(_ id: String, _ details: [KeyDetail], pdf: Bool) {
        guard let p = store.live(id), !details.isEmpty else { return }
        let base = details.count == 1 ? "\(p.displayTitle) – \(details[0].label)" : "\(p.displayTitle) – details"
        let name = String(base.replacingOccurrences(of: "[\\\\/:*?\"<>|]", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces).prefix(80))
        let url = Self.shareDir().appending(path: "\(name).\(pdf ? "pdf" : "jpg")")
        if DetailsPage(title: p.displayTitle, subtitle: p.subtitle, details: details).write(to: url, pdf: pdf) { sharing = [url] } else { toast("Couldn't make the file") }
    }
}
