import Foundation
import SwiftData
import Testing
@testable import MyPapers

/** A message as the Gmail API returns it with format=full (trimmed); the same one as the Android test. */
private let messageJSON = #"""
{"id":"18f1","internalDate":"1791800000000","sizeEstimate":512000,
 "payload":{"partId":"","mimeType":"multipart/mixed","headers":[
    {"name":"From","value":"\"Shufersal\" <noreply@shufersal.co.il>"},
    {"name":"Subject","value":"חשבונית מס 4411"}],
  "parts":[
   {"partId":"0","mimeType":"multipart/related","filename":"","body":{"size":0},"parts":[
      {"partId":"0.0","mimeType":"text/html","filename":"","body":{"size":2100,"data":"PHA-VGhhbmtzPC9wPg"}},
      {"partId":"0.1","mimeType":"image/png","filename":"logo.png","headers":[{"name":"Content-ID","value":"<logo>"},{"name":"Content-Disposition","value":"inline; filename=logo.png"}],"body":{"size":8000,"attachmentId":"a1"}},
      {"partId":"0.2","mimeType":"image/jpeg","filename":"","headers":[{"name":"Content-ID","value":"<banner>"}],"body":{"size":250000,"attachmentId":"a2"}}]},
   {"partId":"1","mimeType":"application/pdf","filename":"חשבונית 4411.pdf","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":40000,"attachmentId":"a3"}},
   {"partId":"2","mimeType":"image/jpeg","filename":"receipt.jpg","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":300000,"attachmentId":"a4"}},
   {"partId":"3","mimeType":"image/jpeg","filename":"tiny.jpg","headers":[{"name":"Content-Disposition","value":"attachment"}],"body":{"size":5000,"attachmentId":"a5"}},
   {"partId":"4","mimeType":"application/octet-stream","filename":"statement.PDF","body":{"size":30000,"attachmentId":"a6"}},
   {"partId":"5","mimeType":"application/zip","filename":"archive.zip","body":{"size":50000,"attachmentId":"a7"}}]}}
"""#

private func json(_ s: String) -> GmailJSON {
    GmailJSON(try! JSONSerialization.jsonObject(with: Data(s.utf8)) as! [String: Any])
}

struct GmailAPITests {
    let message = json(messageJSON)

    @Test func findsPdfsAndPhotosButNotLogos() {
        let found = GmailAPI.paperParts(message)
        #expect(found.map(\.fileName) == ["חשבונית 4411.pdf", "receipt.jpg", "statement.PDF"])
        #expect(found.map(\.partId) == ["1", "2", "4"])
        #expect(found.map(\.mimeType) == ["application/pdf", "image/jpeg", "application/pdf"])
        #expect(found.first?.size == 40000)
    }

    @Test func headersAndSender() {
        let payload = message["payload"] as! [String: Any]
        #expect(GmailAPI.header(payload, "subject") == "חשבונית מס 4411")
        #expect(GmailAPI.senderName(GmailAPI.header(payload, "From")!) == "Shufersal")
        #expect(GmailAPI.senderName("<noreply@x.com>") == "noreply@x.com")
    }

    @Test func scansOnlyWhatsNewAfterTheFirst() {
        // First scan, and a widened window: the whole window.
        #expect(GmailAPI.scanWindow(lastScanAt: 0, coveredMonths: 0, monthsBack: 6) == "newer_than:6m")
        #expect(GmailAPI.scanWindow(lastScanAt: 1_791_800_000_000, coveredMonths: 6, monthsBack: 24) == "newer_than:24m")
        // After that (daily sweep, scan again): since the last scan, an hour earlier.
        #expect(GmailAPI.scanWindow(lastScanAt: 1_791_800_000_000, coveredMonths: 24, monthsBack: 24) == "after:1791796400")
        #expect(GmailAPI.scanWindow(lastScanAt: 1_791_800_000_000, coveredMonths: 24, monthsBack: 6) == "after:1791796400")
    }

    @Test func queries() {
        #expect(GmailAPI.attachmentQuery("newer_than:6m").hasPrefix("newer_than:6m has:attachment (filename:pdf"))
        let r = GmailAPI.receiptQuery("after:1")
        #expect(r.hasPrefix("after:1 (subject:(receipt OR invoice"))
        #expect(r.contains("category:purchases"))
        #expect(r.contains("-subject:(shipped"))
    }

    @Test func partById() {
        #expect((GmailAPI.partById(message, "1")?["body"] as? [String: Any])?["attachmentId"] as? String == "a3")
        #expect(GmailAPI.partById(message, "9") == nil)
    }

    @Test func base64URLWithoutPadding() {
        #expect(GmailAPI.base64URL("PHA-VGhhbmtzPC9wPg").map { String(decoding: $0, as: UTF8.self) } == "<p>Thanks</p>")
        #expect(GmailAPI.base64URL("aGk_").map([UInt8].init) == [0x68, 0x69, 0x3F])
    }

    @Test func htmlWithoutNetwork() {
        let html = #"<p>Hi</p><img src="https://t.example/px.gif"><img src="cid:logo"><link rel="stylesheet" href="https://x/a.css"><script>x()</script>"#
        let out = HTMLToPDF.offline(html)
        #expect(out == #"<p>Hi</p><img src="cid:logo">"#)
    }
}

@MainActor
struct GmailCandidateTests {
    @Test func attachmentsOrTheEmailItself() {
        let m = json(messageJSON)
        let found = GmailImporter.candidates(from: m, emailIfNoAttachments: true, now: 5)
        #expect(found.map(\.key) == ["18f1:1", "18f1:2", "18f1:4"])
        #expect(found.first?.sender == "Shufersal")
        #expect(found.first?.subject == "חשבונית מס 4411")
        #expect(found.first?.sentAt == 1_791_800_000_000)
        #expect(found.allSatisfy { $0.kind == GmailCandidate.kindAttachment })

        let plain = json(#"{"id":"e2","internalDate":"10","sizeEstimate":900,"payload":{"mimeType":"text/html","headers":[{"name":"Subject","value":"Your order: A/B"}],"body":{"data":"PHA-"}}}"#)
        let email = GmailImporter.candidates(from: plain, emailIfNoAttachments: true, now: 5)
        #expect(email.map(\.key) == ["e2:"])
        #expect(email.first?.kind == GmailCandidate.kindEmail)
        #expect(email.first?.fileName == "Your order  A B.pdf")
        #expect(email.first?.size == 900)
        #expect(GmailImporter.candidates(from: plain, emailIfNoAttachments: false, now: 5).isEmpty)
    }

    @Test func anotherMailboxScansInFullAgain() {
        let defaults = UserDefaults(suiteName: "gmail-test-\(UUID())")!
        let s = GmailSettings(defaults: defaults)
        s.setEmail("a@x.com")
        s.lastScanAt = 100
        s.coveredMonths = 6
        s.setEmail("a@x.com")
        #expect(s.lastScanAt == 100)
        s.setEmail("b@x.com")
        #expect(s.lastScanAt == 0)
        #expect(s.coveredMonths == 0)
        s.setEmail(nil)
        #expect(!s.isConnected)
        #expect(GmailSettings(defaults: defaults).monthsBack == 6)
        #expect(GmailSettings(defaults: defaults).dailySweep)
    }
}

/** Answers Gmail API requests in tests, by path. */
final class GmailStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var routes: [String: [(Int, String)]] = [:]
    nonisolated(unsafe) static var seen: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        GmailStub.seen.append(request)
        let path = request.url!.path
        var queue = GmailStub.routes[path] ?? [(404, #"{"error":{"message":"not found"}}"#)]
        let (code, body) = queue.count > 1 ? queue.removeFirst() : queue[0]
        GmailStub.routes[path] = queue
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil, headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [GmailStub.self]
        return URLSession(configuration: c)
    }
}

/** The client against a stand-in Gmail (serialized: the stub's routes are shared). */
@MainActor @Suite(.serialized)
struct GmailNetworkTests {
    let base = "https://gmail.test/gmail/v1/users/me"

    @Test func searchFollowsPagesAndRetriesAnExpiredToken() async throws {
        GmailStub.seen = []
        GmailStub.routes = [
            "/gmail/v1/users/me/messages": [
                (401, #"{"error":{"message":"expired"}}"#),
                (200, #"{"messages":[{"id":"a"},{"id":"b"}],"nextPageToken":"p2"}"#),
                (200, #"{"messages":[{"id":"c"}]}"#),
            ],
        ]
        let api = GmailAPI(token: { "t" }, base: base, session: GmailStub.session())
        #expect(try await api.search("has:attachment") == ["a", "b", "c"])
        #expect(GmailStub.seen.last?.url?.query?.contains("pageToken=p2") == true)
        #expect(GmailStub.seen.first?.value(forHTTPHeaderField: "Authorization") == "Bearer t")
    }

    @Test func deletedEmailsAreSkipped() async throws {
        GmailStub.routes = [
            "/gmail/v1/users/me/messages/a": [(200, #"{"id":"a"}"#)],
            "/gmail/v1/users/me/messages/gone": [(404, #"{"error":{"message":"Not Found"}}"#)],
            "/gmail/v1/users/me/messages/c": [(200, #"{"id":"c"}"#)],
        ]
        let api = GmailAPI(token: { "t" }, base: base, session: GmailStub.session())
        let got = try await api.messages(["a", "gone", "c"])
        #expect(got.map { $0["id"] as? String } == ["a", "c"])
    }

    @Test func revokedAccessAsksToReconnect() async {
        GmailStub.routes = ["/gmail/v1/users/me/messages": [(401, "{}")]]
        let api = GmailAPI(token: { "t" }, base: base, session: GmailStub.session())
        do {
            _ = try await api.search("x")
            Issue.record("a revoked token went through")
        } catch let e as GmailError {
            #expect(e.needsReconnect)
        } catch {
            Issue.record("\(error)")
        }
    }

    /** A whole scan, then importing what was found: papers in the store, the AI not asked, nothing scanned twice. */
    @Test func scanThenImport() async throws {
        let container = try ModelContainer(for: Paper.self, GmailCandidate.self, GmailSeen.self,
                                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        // Kept for the whole run: its models may be let go of later than it, and a container going away
        // resets its context (an app's container lives as long as the app).
        GmailNetworkTests.containers.append(container)
        try await scanThenImport(container)
    }

    static var containers: [ModelContainer] = []

    private func scanThenImport(_ container: ModelContainer) async throws {
        let store = PaperStore(context: container.mainContext)
        let pdf = Data("%PDF-1.4 test \(UUID())".utf8).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
        GmailStub.routes = [
            "/gmail/v1/users/me/messages": [(200, #"{"messages":[{"id":"18f1"}]}"#), (200, #"{"messages":[]}"#)],
            "/gmail/v1/users/me/messages/18f1": [(200, messageJSON)],
            "/gmail/v1/users/me/messages/18f1/attachments/a3": [(200, "{\"data\":\"\(pdf)\"}")],
        ]
        let settings = GmailSettings(defaults: UserDefaults(suiteName: "gmail-test-\(UUID())")!)
        settings.setEmail("me@x.com")
        let importer = GmailImporter(store: store, settings: settings, api: GmailAPI(token: { "t" }, base: base, session: GmailStub.session()))

        #expect(try await importer.scan() == 3)
        #expect(importer.newCount == 3)
        #expect(settings.lastScanAt > 0)
        #expect(settings.coveredMonths == 6)

        // Scanned again: the email was already looked at, nothing new.
        GmailStub.routes["/gmail/v1/users/me/messages"] = [(200, #"{"messages":[{"id":"18f1"}]}"#), (200, #"{"messages":[]}"#)]
        #expect(try await importer.scan() == 0)

        let pdfCandidate = importer.candidates.first { $0.partId == "1" }!
        importer.dismiss(importer.candidates.filter { $0.partId != "1" }.map(\.key))
        importer.queue([pdfCandidate.key])
        try await importer.importQueued()
        #expect(pdfCandidate.state == GmailCandidate.stateImported)
        let paper = try #require(store.live(pdfCandidate.documentId ?? ""))
        #expect(paper.fileName == "חשבונית 4411.pdf")
        #expect(paper.aiState == "")
        #expect(importer.candidates.isEmpty)
    }
}
