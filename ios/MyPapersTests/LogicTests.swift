import Foundation
import Testing
@testable import MyPapers

/** The same cases as the Android unit tests, so both apps search, filter and read dates alike. */
@MainActor
struct FuzzySearchTests {
    func doc(_ id: String, _ vendor: String, _ total: Int64?, _ tags: [String] = [], _ date: String = "2026-10-01") -> Paper {
        let p = Paper(id: id, createdAt: 0, updatedAt: 0)
        p.vendor = vendor
        p.totalCents = total
        p.tags = tags
        p.date = date
        return p
    }

    var docs: [Paper] {
        [
            doc("shuf", "Shufersal", 34290, ["Groceries"], "2026-10-08"),
            doc("wolt", "Wolt", 11800, ["Food", "Delivery"], "2026-10-06"),
            doc("iec", "Israel Electric Corp.", 61240, ["Utilities"], "2026-10-01"),
            doc("paz", "Paz", 12500, ["Car", "Fuel"], "2026-09-22"),
            doc("heb", "שופרסל", 9900, ["מזון"], "2026-09-01"),
        ]
    }

    func ids(_ q: String, _ tags: Set<String> = []) -> [String] {
        FuzzySearch.search(docs, q, tags: tags, locale: Locale(identifier: "en_US")).map(\.id)
    }

    @Test func blankQueryIsRecentlyAddedFirst() {
        let rx = Paper(id: "rx", createdAt: 99, updatedAt: 0)
        rx.date = "2026-01-12"
        #expect(FuzzySearch.search(docs + [rx], "", locale: Locale(identifier: "en_US")).first?.id == "rx")
    }

    @Test func englishKeywordFindsHebrewPaper() {
        let rx = Paper(id: "rx", createdAt: 0, updatedAt: 0)
        rx.title = "מרשם משקפיים"
        rx.keywords = ["eye prescription", "glasses", "מרשם משקפיים"]
        let us = Locale(identifier: "en_US")
        #expect(FuzzySearch.search(docs + [rx], "eye prescription", locale: us).map(\.id) == ["rx"])
        #expect(FuzzySearch.search(docs + [rx], "משקפיים", locale: us).map(\.id) == ["rx"])
    }

    @Test func typos() {
        #expect(ids("shufersl").first == "shuf")
        #expect(ids("shufesral").first == "shuf")
        #expect(ids("elec") == ["iec"])
        #expect(ids("fuel") == ["paz"])
        #expect(ids("delivry").first == "wolt")
    }

    @Test func amounts() {
        #expect(Array(ids("120").prefix(2)) == ["wolt", "paz"])
        #expect(!ids("120").contains("iec"))
        #expect(ids("₪612").first == "iec")
        #expect(ids("342.90").first == "shuf")
        #expect(ids("2000").first == "iec")
        #expect(ids("paz 125") == ["paz"])
    }

    @Test func everyWordMustMatch() {
        #expect(ids("wolt food") == ["wolt"])
        #expect(ids("wolt fuel") == [])
    }

    @Test func hebrewAndChips() {
        #expect(ids("שופרס").first == "heb")
        #expect(ids("", ["Food"]) == ["wolt"])
    }

    @Test func parseAmountByLocale() {
        #expect(FuzzySearch.parseAmount("1,249", locale: Locale(identifier: "en_US")) == 1249)
        #expect(FuzzySearch.parseAmount("118,50", locale: Locale(identifier: "de_DE")) == 118.5)
        #expect(FuzzySearch.parseAmount("INV2026", locale: Locale(identifier: "en_US")) == nil)
    }
}

struct ExpiryTests {
    func d(_ pairs: (String, String)...) -> [KeyDetail] { pairs.map { KeyDetail(label: $0.0, value: $0.1) } }

    @Test func formats() {
        #expect(Expiry.parse("2027-08-15", dayFirst: true) == Day(2027, 8, 15))
        #expect(Expiry.parse("15/08/2027", dayFirst: false) == Day(2027, 8, 15))
        #expect(Expiry.parse("03.04.2027", dayFirst: true) == Day(2027, 4, 3))
        #expect(Expiry.parse("03/04/2027", dayFirst: false) == Day(2027, 3, 4))
        #expect(Expiry.parse("Aug 15, 2027", dayFirst: true) == Day(2027, 8, 15))
        #expect(Expiry.parse("15 August 2027", dayFirst: true) == Day(2027, 8, 15))
        #expect(Expiry.parse("08/2027", dayFirst: true) == Day(2027, 8, 31))
        #expect(Expiry.parse("2 years", dayFirst: true) == nil)
    }

    @Test func fromDetails() {
        #expect(Expiry.date(d(("PD", "62 mm"), ("Valid for", "2 years"), ("Renewal date", "15/08/2027")), dayFirst: true) == Day(2027, 8, 15))
        #expect(Expiry.date(d(("בתוקף עד", "31.01.2027")), dayFirst: true) == Day(2027, 1, 31))
        #expect(Expiry.date(d(("Prescription date", "2026-08-15")), dayFirst: true) == nil)
    }

    @Test func nearestUpcomingWins() {
        let today = Day(2026, 10, 12)!
        let bill = d(("Due date", "2026-10-19"), ("Valid until", "2027-10-01"))
        #expect(Expiry.date(bill, dayFirst: true, today: today) == Day(2026, 10, 19))
        #expect(Expiry.isSoon(bill, today: today, dayFirst: true))
        #expect(Expiry.date(bill, dayFirst: true, today: Day(2026, 10, 25)!) == Day(2027, 10, 1))
        #expect(Expiry.date(bill, dayFirst: true, today: Day(2028, 1, 1)!) == Day(2027, 10, 1))
        #expect(!Expiry.isSoon(d(("Expires", "2026-10-01")), today: today, dayFirst: true))
    }
}

struct RulesTests {
    @Test func tables() {
        #expect(tableRows("Item | Qty\nMilk | 2") == [["Item", "Qty"], ["Milk", "2"]])
        #expect(tableRows("| A | B |\n|---|---|\n| 1 | 2 |") == [["A", "B"], ["1", "2"]])
        #expect(tableRows("just text") == nil)
    }

    @Test func hebrewFinals() {
        #expect(ExtractionRules.fixHebrewFinals("לוקרימ") == "לוקרים")
        #expect(ExtractionRules.fixHebrewFinals("צ'יפס") == "צ'יפס")
    }

    @Test func futureDateSwapped() {
        #expect(ExtractionRules.fixFutureDate("2026-12-10", today: Day(2026, 10, 15)!) == "2026-10-12")
        #expect(ExtractionRules.fixFutureDate("2026-09-10", today: Day(2026, 10, 15)!) == "2026-09-10")
    }

    @Test func reworkReplacesInPlace() {
        let before = [KeyDetail(label: "Policy", value: "P-1"), KeyDetail(label: "Items", value: "a, b"), KeyDetail(label: "URL", value: "u")]
        let new = KeyDetail(label: "Item list", value: "Item | Qty\na | 1")
        #expect(ExtractionRules.replaceDetail(before, label: "items", [new]) == [before[0], new, before[2]])
    }

    @MainActor @Test func rereadKeepsAskedDetails() {
        let p = Paper(id: "d", createdAt: 0, updatedAt: 0)
        p.details = [KeyDetail(label: "Policy", value: "old"), KeyDetail(label: "URL", value: "u", asked: true), KeyDetail(label: "Plan", value: "asked plan", asked: true)]
        var e = Extraction()
        e.vendor = "V"
        e.docType = "Contract"
        e.details = [KeyDetail(label: "Policy", value: "new"), KeyDetail(label: "Plan", value: "read plan")]
        ExtractionRules.apply(e, to: p, existingTags: [])
        #expect(p.details == [KeyDetail(label: "Policy", value: "new"), KeyDetail(label: "URL", value: "u", asked: true), KeyDetail(label: "Plan", value: "asked plan", asked: true)])
    }
}

/** A paper written by the Android app reads the same here, and back. */
@MainActor
struct DriveJSONTests {
    let android = #"""
    {"_id":"8c1f","type":"document","title":"Washing machine","vendor":"Machsanei Hashmal","docType":"Warranty","date":"2026-05-01","totalCents":249900,"currency":"ILS","vatCents":null,"tags":["Home"],"keywords":["washer"],"notes":"","extractedText":"x","fullText":"","transcript":"","details":[{"label":"Serial","value":"WX-1","asked":true}],"fileHash":"ab","fileName":"w.pdf","mimeType":"application/pdf","createdAt":1,"updatedAt":2,"aiState":"done","aiError":null,"duplicateOf":null,"trashedAt":null,"edited":["title"],"_attachments":{"file":{"stub":true}}}
    """#

    @Test func readsAndWritesAndroidJSON() throws {
        let p = Paper(id: "8c1f")
        try PaperJSON.apply(Data(android.utf8), to: p)
        #expect(p.title == "Washing machine")
        #expect(p.totalCents == 249900)
        #expect(p.vatCents == nil)
        #expect(p.details == [KeyDetail(label: "Serial", value: "WX-1", asked: true)])
        #expect(p.edited == ["title"])
        #expect(p.fileUploaded)
        let back = try JSONSerialization.jsonObject(with: PaperJSON.encode(p, keepFile: true)) as! [String: Any]
        let original = try JSONSerialization.jsonObject(with: Data(android.utf8)) as! [String: Any]
        #expect(NSDictionary(dictionary: back).isEqual(to: original))
    }
}
