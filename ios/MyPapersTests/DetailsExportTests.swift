import Foundation
import PDFKit
import Testing
import UIKit
@testable import MyPapers

/** Details drawn to a PDF and a JPG: the text is there and the page fits the content. */
@MainActor
struct DetailsExportTests {
    let page = DetailsPage(title: "iPhone 18 Pro", subtitle: "iDigital · Invoice", details: [
        KeyDetail(label: "Warranty", value: "Valid until 2027-11-01"),
        KeyDetail(label: "פריטים", value: "| פריט | מחיר |\n|---|---|\n| מגן מסך | 99 |\n| כיסוי | 149 |"),
    ])

    /** Where to look at the files (TEST_RUNNER_SHOTS_DIR), else a temporary folder. */
    func out(_ name: String) -> URL {
        let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"].map { URL(filePath: $0) } ?? FileManager.default.temporaryDirectory
        return dir.appending(path: name)
    }

    @Test func pdf() throws {
        let url = out("details.pdf")
        #expect(page.write(to: url, pdf: true))
        let doc = try #require(PDFDocument(url: url))
        #expect(doc.pageCount == 1)
        let text = doc.string ?? ""
        #expect(text.contains("Warranty"))
        #expect(text.contains("iPhone 18 Pro"))
        #expect(text.contains("מגן מסך"))
        #expect(doc.page(at: 0)!.bounds(for: .mediaBox).width == DetailsPage.width)
    }

    @Test func jpg() throws {
        let url = out("details.jpg")
        #expect(page.write(to: url, pdf: false))
        let image = try #require(UIImage(contentsOfFile: url.path))
        #expect(image.size.width == DetailsPage.width * 3)  // pixels: drawn at 3x
        #expect(image.size.height > 450)
    }
}
