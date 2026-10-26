import UIKit

/**
 * An email's HTML as an A4 PDF, paginated by the system's HTML print formatter. Nothing is loaded
 * from the network (remote images, styles and scripts are removed first, so no tracking pixels):
 * the receipt's text and layout are what matters.
 */
@MainActor
enum HTMLToPDF {
    private static let a4 = CGRect(x: 0, y: 0, width: 595.2, height: 841.8)

    static func render(_ html: String, to url: URL) async throws {
        let formatter = UIMarkupTextPrintFormatter(markupText: offline(html))
        let renderer = UIPrintPageRenderer()
        renderer.addPrintFormatter(formatter, startingAtPageAt: 0)
        renderer.setValue(a4, forKey: "paperRect")
        renderer.setValue(a4.insetBy(dx: 24, dy: 24), forKey: "printableRect")
        // The formatter lays the HTML out in the background: give it a moment if it hasn't yet.
        var tries = 0
        while renderer.numberOfPages == 0 && tries < 20 {
            try await Task.sleep(for: .milliseconds(150))
            tries += 1
        }
        let pages = max(renderer.numberOfPages, 1)
        let data = NSMutableData()
        UIGraphicsBeginPDFContextToData(data, a4, nil)
        renderer.prepare(forDrawingPages: NSRange(location: 0, length: pages))
        for i in 0..<pages {
            UIGraphicsBeginPDFPage()
            renderer.drawPage(at: i, in: UIGraphicsGetPDFContextBounds())
        }
        UIGraphicsEndPDFContext()
        try (data as Data).write(to: url)
    }

    /** Without anything that would load from the network. */
    nonisolated static func offline(_ html: String) -> String {
        var s = html
        for pattern in [
            "(?is)<script\\b.*?</script>",
            "(?is)<link\\b[^>]*>",
            "(?is)<img\\b[^>]*\\bsrc\\s*=\\s*[\"']?(?:https?:)?//[^>]*>",
            "(?is)url\\(\\s*[\"']?(?:https?:)?//[^)]*\\)",
        ] {
            s = s.replacingOccurrences(of: pattern, with: "", options: .regularExpression)
        }
        return s
    }
}
