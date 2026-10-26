import PDFKit
import SwiftUI

extension Paper {
    /** What a paper is called in lists: its title, else its vendor, else its file name (without ".pdf"). */
    var displayTitle: String {
        if !title.isEmpty { return title }
        if !vendor.isEmpty { return vendor }
        let name = (fileName ?? "").replacingOccurrences(of: "\\.[^.]*$", with: "", options: .regularExpression)
        return name.isEmpty ? "Untitled" : name
    }

    /** "Shufersal · Receipt · 8 Oct 2026", skipping what isn't set; nothing known yet: "Added 12 Oct 2026 · PDF". */
    var subtitle: String {
        let parts = [title.isEmpty || vendor.isEmpty ? nil : vendor, docType.isEmpty ? nil : docType, Format.date(date)].compactMap { $0 }
        if !parts.isEmpty { return parts.joined(separator: " · ") }
        let kind = mimeType == "application/pdf" ? "PDF" : (mimeType?.hasPrefix("image/") == true ? "Photo" : nil)
        return (["Added \(Format.day(createdAt))"] + [kind].compactMap { $0 }).joined(separator: " · ")
    }

    var amountText: String? { totalCents.map { Format.amount($0, currency) } }

    /** The paper's kind as a symbol. */
    var kindSymbol: String {
        switch docType {
        case "Receipt": "receipt"
        case "Invoice": "doc.text"
        case "Warranty": "checkmark.shield"
        case "Contract": "signature"
        default: "doc"
        }
    }
}

/** "12 Oct", with the year when it isn't this one. */
func shortDate(_ d: Day) -> String {
    let style = d.year == Day.today.year ? Date.FormatStyle().day().month(.abbreviated) : Date.FormatStyle().day().month(.abbreviated).year()
    return d.date.formatted(style.locale(Format.locale))
}

/** Backed up to Google Drive yet (signed in only). */
struct CloudMark: View {
    let paper: Paper
    var body: some View {
        Image(systemName: paper.dirty ? "icloud.and.arrow.up" : "checkmark.icloud")
            .font(.footnote)
            .foregroundStyle(paper.dirty ? Color.secondary : Color.accentColor)
            .accessibilityLabel(paper.dirty ? "Not backed up yet" : "Backed up")
    }
}

/** "Reading with AI…", or why it failed. */
struct AIStatusLine: View {
    let paper: Paper
    var body: some View {
        if paper.aiState == aiPending {
            Label("Reading with AI…", systemImage: "sparkles").font(.footnote).foregroundStyle(.tint)
        } else if paper.aiState == aiFailed {
            Label(paper.aiError ?? "AI reading failed", systemImage: "exclamationmark.triangle").font(.footnote).foregroundStyle(.red)
        }
    }
}

/** "Expires in 12 days", when that's close (or just past); nothing otherwise. */
struct ExpiryLabel: View {
    let paper: Paper
    var body: some View {
        if let expires = Expiry.date(paper.details, dayFirst: Format.dayFirst()) {
            let left = Expiry.daysLeft(expires, today: .today)
            if left >= -30 && left <= Expiry.soonDays {
                Label(text(left), systemImage: "clock")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(left < 0 ? Color.red : left <= 7 ? Color.orange : Color.secondary)
            }
        }
    }

    private func text(_ left: Int) -> String {
        switch left {
        case ..<(-1): "Expired \(-left) days ago"
        case -1: "Expired yesterday"
        case 0: "Expires today"
        case 1: "Expires tomorrow"
        default: "Expires in \(left) days"
        }
    }
}

struct TagChips: View {
    let tags: [String]
    var body: some View {
        FlowLayout(spacing: 6) {
            ForEach(tags, id: \.self) { tag in
                Text(tag)
                    .font(.footnote)
                    .padding(.horizontal, 10).padding(.vertical, 4)
                    .background(.tint.opacity(0.12), in: Capsule())
            }
        }
    }
}

/** The library's card: title and amount, what it is, tags, AI state. */
struct PaperCard: View {
    let paper: Paper
    let signedIn: Bool
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 10) {
                if selected { Image(systemName: "checkmark.circle.fill").foregroundStyle(.tint) }
                Text(paper.displayTitle).font(.headline).lineLimit(1)
                Spacer(minLength: 8)
                if let a = paper.amountText { Text(a).font(.headline) }
                if signedIn { CloudMark(paper: paper) }
            }
            Text(paper.subtitle).font(.subheadline).foregroundStyle(.secondary)
            ExpiryLabel(paper: paper)
            if !paper.tags.isEmpty { TagChips(tags: paper.tags).padding(.top, 4) }
            AIStatusLine(paper: paper)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(selected ? Color.accentColor.opacity(0.15) : Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/** One line per paper: its kind (or, in the timeline, the day of the month), title, date, amount. */
struct CompactRow: View {
    let paper: Paper
    let signedIn: Bool
    let selected: Bool
    var day: Int? = nil

    var body: some View {
        HStack(spacing: 12) {
            if selected {
                Image(systemName: "checkmark.circle.fill").font(.title2).foregroundStyle(.tint).frame(width: 34)
            } else if let day {
                Text("\(day)").font(.headline).foregroundStyle(.secondary).frame(width: 34)
            } else {
                Image(systemName: paper.kindSymbol)
                    .font(.subheadline)
                    .frame(width: 34, height: 34)
                    .background(.tint.opacity(0.14), in: KindShape(kind: paper.docType))
            }
            Text(paper.displayTitle).font(.body.weight(.medium)).lineLimit(1)
            Spacer(minLength: 6)
            if day == nil { Text(shortDate(Filters.paperDay(paper))).font(.footnote).foregroundStyle(.secondary) }
            if let a = paper.amountText { Text(a).font(.body.weight(.semibold)) }
            if signedIn { CloudMark(paper: paper) }
        }
        .padding(.horizontal, 14).padding(.vertical, 10)
        .background(selected ? Color.accentColor.opacity(0.15) : Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/** Each kind in its own shape, so a list reads at a glance. */
struct KindShape: Shape {
    let kind: String
    func path(in rect: CGRect) -> Path {
        switch kind {
        case "Receipt": return Path(roundedRect: rect, cornerRadius: rect.width * 0.32, style: .continuous)
        case "Invoice": return Circle().path(in: rect)
        case "Warranty": return Diamond().path(in: rect.insetBy(dx: -2, dy: -2))
        case "Contract": return Capsule().path(in: rect.insetBy(dx: 0, dy: rect.height * 0.08))
        default: return Path(roundedRect: rect, cornerRadius: 8, style: .continuous)
        }
    }

    private struct Diamond: Shape {
        func path(in r: CGRect) -> Path {
            var p = Path()
            let c = 0.18 * r.width
            p.move(to: CGPoint(x: r.midX, y: r.minY))
            p.addQuadCurve(to: CGPoint(x: r.maxX, y: r.midY), control: CGPoint(x: r.maxX - c, y: r.minY + c))
            p.addQuadCurve(to: CGPoint(x: r.midX, y: r.maxY), control: CGPoint(x: r.maxX - c, y: r.maxY - c))
            p.addQuadCurve(to: CGPoint(x: r.minX, y: r.midY), control: CGPoint(x: r.minX + c, y: r.maxY - c))
            p.addQuadCurve(to: CGPoint(x: r.midX, y: r.minY), control: CGPoint(x: r.minX + c, y: r.minY + c))
            return p
        }
    }
}

/** A page thumbnail with the title and "₪312 · 12 Oct" under it. */
struct GridTile: View {
    let paper: Paper
    let signedIn: Bool
    let selected: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomTrailing) {
                Thumbnail(paper: paper)
                    .frame(maxWidth: .infinity)
                    .aspectRatio(0.78, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .overlay {
                        if selected { RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Color.accentColor, lineWidth: 3) }
                    }
                if signedIn {
                    CloudMark(paper: paper).padding(6).glassEffect(.regular, in: Circle()).padding(8)
                }
            }
            Text(paper.displayTitle).font(.subheadline.weight(.medium)).lineLimit(1).padding(.horizontal, 4)
            Text([paper.amountText, shortDate(Filters.paperDay(paper))].compactMap { $0 }.joined(separator: " · "))
                .font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
        }
    }
}

/** The first page: drawn once per size and kept while the app runs. [pixels]: its longer side. */
struct Thumbnail: View {
    let paper: Paper
    var pixels: CGFloat = 520
    @State private var image: UIImage?

    var body: some View {
        // The picture fills whatever box it's given, never the other way round.
        Rectangle()
            .fill(Color(.tertiarySystemFill))
            .overlay(alignment: .top) {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    Image(systemName: paper.kindSymbol).font(.largeTitle).foregroundStyle(.tertiary).frame(maxHeight: .infinity)
                }
            }
            .clipped()
            .task(id: "\(paper.id):\(paper.fileHash ?? ""):\(paper.fileLocal):\(pixels)") {
                guard paper.fileLocal else { return }
                image = await Thumbnails.shared.image(for: paper.id, hash: paper.fileHash ?? "", pdf: PaperFiles.isPDF(paper), pixels: pixels)
            }
    }
}

actor Thumbnails {
    static let shared = Thumbnails()
    private let cache = NSCache<NSString, UIImage>()

    func image(for id: String, hash: String, pdf: Bool, pixels: CGFloat) -> UIImage? {
        let key = "\(id):\(hash):\(pixels)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let url = PaperFiles.url(id)
        let size = CGSize(width: pixels * 0.77, height: pixels)
        let img: UIImage? = pdf
            ? PDFDocument(url: url)?.page(at: 0)?.thumbnail(of: size, for: .mediaBox)
            : UIImage(contentsOfFile: url.path)?.preparingThumbnail(of: size)
        if let img { cache.setObject(img, forKey: key) }
        return img
    }
}

/** "SEPTEMBER 2026", then how many and what they add up to (per currency). */
struct MonthHeader: View {
    let year: Int
    let month: Int
    let papers: [Paper]

    var body: some View {
        let totals = Dictionary(grouping: papers.filter { $0.totalCents != nil }, by: { $0.currency ?? "" })
            .sorted { $0.key < $1.key }
            .map { Format.amount($0.value.reduce(0) { $0 + ($1.totalCents ?? 0) }, $0.key.isEmpty ? nil : $0.key) }
        HStack {
            Text(Day(year, month, 1)!.date.formatted(Date.FormatStyle().month(.wide).year().locale(Format.locale)).uppercased())
                .font(.footnote.weight(.semibold)).foregroundStyle(.tint)
            Spacer()
            Text(papers.count == 1 ? "1 paper" : "\(papers.count) papers").font(.caption).foregroundStyle(.secondary)
            if !totals.isEmpty { Text(totals.joined(separator: " · ")).font(.subheadline.weight(.semibold)) }
        }
        .padding(.horizontal, 4).padding(.top, 8)
    }
}

/** Wraps its children onto as many lines as needed (tags, chips). */
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, line: CGFloat = 0, widest: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > 0 && x + size.width > width { x = 0; y += line + spacing; line = 0 }
            x += size.width + spacing
            widest = max(widest, x - spacing)
            line = max(line, size.height)
        }
        return CGSize(width: min(widest, width), height: y + line)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, line: CGFloat = 0
        for s in subviews {
            let size = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + size.width > bounds.maxX { x = bounds.minX; y += line + spacing; line = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            line = max(line, size.height)
        }
    }
}

extension View {
    /** Where a paper grows from when opened (once the namespace is known). */
    @ViewBuilder
    func zoomSource(_ id: String, _ ns: Namespace.ID?) -> some View {
        if let ns { matchedTransitionSource(id: id, in: ns) } else { self }
    }

    @ViewBuilder
    func zoomDestination(_ id: String, _ ns: Namespace.ID?) -> some View {
        if let ns { navigationTransition(.zoom(sourceID: id, in: ns)) } else { self }
    }
}
