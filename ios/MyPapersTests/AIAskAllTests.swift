import Foundation
import Testing
import UIKit
@testable import MyPapers

@MainActor
struct AIAskAllTests {
    func doc(_ id: String, _ title: String, _ cents: Int64?, currency: String? = "ILS", text: String = "", tags: [String] = []) -> Paper {
        let p = Paper(id: id, createdAt: 0, updatedAt: 0)
        p.title = title
        p.totalCents = cents
        p.currency = currency
        p.fullText = text
        p.date = "2026-05-01"
        p.tags = tags
        return p
    }

    @Test func lineHasTheFacts() {
        let d = doc("a", "Washing machine", 249900, tags: ["Home"])
        d.vendor = "Machsanei Hashmal"
        d.details = [KeyDetail(label: "Warranty", value: "3 years\nparts")]
        #expect(AskAll.line(4, d) == "#4 | 2026-05-01 |  | Washing machine | Machsanei Hashmal | 2499.00 ILS | Home |  | Warranty: 3 years parts")
    }

    @Test func totalsPerCurrencySkipNoAmount() {
        let t = AskAll.totals([doc("a", "x", 1000), doc("b", "y", 250), doc("c", "z", 500, currency: "USD"), doc("d", "w", nil)])
        #expect(t == ["ILS": 1250, "USD": 500])
    }

    @Test func parsesAnswers() {
        #expect(AskAll.numbers(#"{"papers":[3,1,3],"total":true}"#) == [3, 1])
        #expect(AskAll.isTotal(#"{"papers":[],"total":true}"#))
        #expect(AskAll.answerText(#"{"answer":"At Ace","papers":[2]}"#) == "At Ace")
    }

    /** A follow-up carries the conversation so far into both steps. */
    @Test func followUpCarriesTheConversation() {
        let history = [AllTurn(question: "how much on the dentist?", answer: "₪750 at Dr. Levi.")]
        let docs = [doc("1", "Dentist", 45000)]
        for prompt in [AskAll.pick("and last year?", docs, history: history), AskAll.answer("and last year?", history: history, total: true, read: [(1, docs[0])], listed: [])] {
            #expect(prompt.contains("Q: how much on the dentist?") && prompt.contains("A: ₪750 at Dr. Levi."))
            #expect(prompt.contains(#"Now they ask: "and last year?""#))
        }
        #expect(AskAll.pick("where?", docs).contains(#"They ask: "where?""#))
    }

    /** A small photo (a chat app's copy) still gets close-ups: at least 2 across, each enlarged. */
    @Test func smallPhotoGetsEnlargedCloseUps() {
        let size = CGSize(width: 720, height: 1280)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.gray.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
        }.cgImage!
        let tiles = AIInput.tiles(image)
        #expect(tiles.count == 8)
        #expect(tiles[0].0 == "row 1 of 4, column 1 of 2")
        #expect(tiles.allSatisfy { min($0.1.width, $0.1.height) >= 1000 })
    }
}
