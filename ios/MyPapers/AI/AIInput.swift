import Foundation
import PDFKit
import UIKit

/** A paper's file as Responses API content parts. */
enum AIInput {
    /** Longest side of a photo sent to the model: enough to read a receipt, far cheaper than the full photo. */
    static let maxImageSide: CGFloat = 2048
    /** PDFs up to this size go as they are (the model reads their text and pages); bigger ones as page images. */
    static let maxPdfBytes = 8 * 1024 * 1024
    static let maxPdfPagesAsImages = 5
    /** A page for writing out: decoded this big, then cut into close-ups about [tile] across. */
    static let closeUpSide: CGFloat = 3600
    static let tile: CGFloat = 1300
    static let minTileSide: CGFloat = 1000

    static func parts(fileURL: URL, mimeType: String?, fileName: String?) throws -> [AIPart] {
        let type = mimeType ?? PaperFiles.sniffMimeType(fileURL)
        var parts: [AIPart] = []
        if type == "application/pdf" {
            let size = (try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            if size <= maxPdfBytes {
                let data = try Data(contentsOf: fileURL)
                let name = fileName.flatMap { $0.lowercased().hasSuffix(".pdf") ? $0 : nil } ?? "document.pdf"
                parts = [["type": "input_file", "filename": name, "file_data": "data:application/pdf;base64," + data.base64EncodedString()]]
            } else {
                parts = pdfPages(fileURL, side: maxImageSide, limit: maxPdfPagesAsImages).map(imagePart)
            }
        } else if let image = decode(fileURL, maxSide: maxImageSide) {
            parts = [imagePart(image)]
        }
        if parts.isEmpty { throw AIError.message("Can't read this kind of file") }
        return parts
    }

    /**
     * For writing a whole paper out: each page whole, then in overlapping close-ups, so small writing gets
     * many more pixels (a whole photo is read at well under 1000px across). A PDF with its own text goes as it is.
     */
    static func closeUpParts(fileURL: URL, mimeType: String?, fileName: String?) throws -> [AIPart] {
        let type = mimeType ?? PaperFiles.sniffMimeType(fileURL)
        if type == "application/pdf" && !PdfText.read(fileURL).isEmpty { return try parts(fileURL: fileURL, mimeType: mimeType, fileName: fileName) }
        let pages: [CGImage] = type == "application/pdf"
            ? pdfPages(fileURL, side: closeUpSide, limit: maxPdfPagesAsImages)
            : decode(fileURL, maxSide: closeUpSide).map { [$0] } ?? []
        var out: [AIPart] = []
        for (p, page) in pages.enumerated() {
            out.append(text("Page \(p + 1) of \(pages.count), whole:"))
            out.append(imagePart(scaled(page, maxSide: maxImageSide)))
            for (label, t) in tiles(page) {
                out.append(text("Page \(p + 1), close-up \(label):"))
                out.append(imagePart(t))
            }
        }
        if out.isEmpty { throw AIError.message("Can't read this kind of file") }
        return out
    }

    /**
     * Overlapping pieces about [tile] across, labeled "row 1 of 3, column 2 of 2" (columns left to right).
     * Always at least 2 across and about square, even for a small photo: enlarged, its close-ups are read far better.
     */
    static func tiles(_ page: CGImage) -> [(String, CGImage)] {
        let W = CGFloat(page.width), H = CGFloat(page.height)
        let cols = max(2, Int((W / tile).rounded(.up)))
        let rows = max(Int((H / tile).rounded(.up)), Int((CGFloat(cols) * H / W).rounded()), 1)
        // Each a sixth bigger than its share, so a line cut at one edge is whole in the next.
        let w = min(page.width, page.width * 7 / (cols * 6))
        let h = min(page.height, page.height * 7 / (rows * 6))
        var out: [(String, CGImage)] = []
        for r in 0..<rows {
            for c in 0..<cols {
                let x = cols == 1 ? 0 : (page.width - w) * c / (cols - 1)
                let y = rows == 1 ? 0 : (page.height - h) * r / (rows - 1)
                guard let piece = page.cropping(to: CGRect(x: x, y: y, width: w, height: h)) else { continue }
                out.append(("row \(r + 1) of \(rows), column \(c + 1) of \(cols)", enlarged(piece)))
            }
        }
        return out
    }

    private static func text(_ s: String) -> AIPart { ["type": "input_text", "text": s] }

    /** JPEG on white (a transparent PNG would turn black, hiding dark text), looked at closely. */
    static func imagePart(_ image: CGImage) -> AIPart {
        let size = CGSize(width: image.width, height: image.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let data = UIGraphicsImageRenderer(size: size, format: format).jpegData(withCompressionQuality: 0.85) { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            UIImage(cgImage: image).draw(in: CGRect(origin: .zero, size: size))
        }
        return ["type": "input_image", "detail": "high", "image_url": "data:image/jpeg;base64," + data.base64EncodedString()]
    }

    /** Upright (EXIF orientation), at most [maxSide] on its longer side. */
    static func decode(_ url: URL, maxSide: CGFloat) -> CGImage? {
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        let scale = min(1, maxSide / max(image.size.width, image.size.height))
        let size = CGSize(width: (image.size.width * scale).rounded(), height: (image.size.height * scale).rounded())
        return render(size) { image.draw(in: CGRect(origin: .zero, size: size)) }
    }

    /** The first [limit] pages, white behind, [side] pixels on their longer side. */
    static func pdfPages(_ url: URL, side: CGFloat, limit: Int) -> [CGImage] {
        guard let doc = PDFDocument(url: url) else { return [] }
        return (0..<min(doc.pageCount, limit)).compactMap { i in
            guard let page = doc.page(at: i) else { return nil }
            let bounds = page.bounds(for: .mediaBox)
            let scale = side / max(bounds.width, bounds.height)
            let size = CGSize(width: (bounds.width * scale).rounded(), height: (bounds.height * scale).rounded())
            return render(size) {
                guard let ctx = UIGraphicsGetCurrentContext() else { return }
                UIColor.white.setFill()
                ctx.fill(CGRect(origin: .zero, size: size))
                ctx.translateBy(x: 0, y: size.height)
                ctx.scaleBy(x: scale, y: -scale)
                page.draw(with: .mediaBox, to: ctx)
            }
        }
    }

    private static func scaled(_ image: CGImage, maxSide: CGFloat) -> CGImage {
        let scale = maxSide / CGFloat(max(image.width, image.height))
        if scale >= 1 { return image }
        return resized(image, scale)
    }

    /** A close-up at least [minTileSide] on its shorter side (the model reads small pictures at their size). */
    private static func enlarged(_ image: CGImage) -> CGImage {
        let scale = minTileSide / CGFloat(min(image.width, image.height))
        return scale <= 1 ? image : resized(image, scale)
    }

    private static func resized(_ image: CGImage, _ scale: CGFloat) -> CGImage {
        let size = CGSize(width: (CGFloat(image.width) * scale).rounded(), height: (CGFloat(image.height) * scale).rounded())
        return render(size) { UIImage(cgImage: image).draw(in: CGRect(origin: .zero, size: size)) } ?? image
    }

    private static func render(_ size: CGSize, _ draw: () -> Void) -> CGImage? {
        guard size.width >= 1, size.height >= 1 else { return nil }
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in draw() }.cgImage
    }
}
