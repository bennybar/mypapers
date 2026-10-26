import Foundation
import SwiftData
import Testing
@testable import MyPapers

/** Drive's appDataFolder in memory: files, and a change feed whose tokens are positions in it. */
actor FakeDrive: DriveStore {
    struct File { var name: String; var updatedAt: Int64?; var content: Data }
    private(set) var files: [String: File] = [:]
    private var log: [DriveChange] = []
    private(set) var reads = 0
    private var next = 0

    func startPageToken() -> String { String(log.count) }
    func listAll() -> [DriveFile] { files.map { DriveFile(id: $0.key, name: $0.value.name, updatedAt: $0.value.updatedAt) } }
    func changes(_ token: String) -> ChangePage { ChangePage(changes: Array(log.dropFirst(Int(token)!)), next: String(log.count), done: true) }
    func find(_ name: String) -> String? { files.first { $0.value.name == name }?.key }
    func read(_ id: String) -> Data { reads += 1; return files[id]!.content }
    func download(_ id: String, to: URL) throws { try files[id]!.content.write(to: to) }

    func put(_ id: String?, name: String, updatedAt: Int64?, content: DriveContent) throws -> String {
        if let id, files[id] == nil { throw DriveError(message: "gone", code: 404) }
        let fileId = id ?? "f\(next)"
        if id == nil { next += 1 }
        let bytes: Data = switch content {
        case .data(let d, _): d
        case .file(let url, _): try Data(contentsOf: url)
        }
        files[fileId] = File(name: files[fileId]?.name ?? name, updatedAt: updatedAt, content: bytes)
        log.append(DriveChange(fileId: fileId, file: DriveFile(id: fileId, name: files[fileId]!.name, updatedAt: updatedAt)))
        return fileId
    }

    func delete(_ id: String) {
        if files.removeValue(forKey: id) != nil { log.append(DriveChange(fileId: id, file: nil)) }
    }

    var names: Set<String> { Set(files.values.map(\.name)) }
}

/** A phone: its own papers, files and sync position, syncing through [drive]. */
@MainActor
final class Phone {
    let store: PaperStore
    let dir: URL
    let engine: DriveSync

    init(_ drive: FakeDrive) throws {
        let container = try ModelContainer(for: Paper.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        store = PaperStore(context: ModelContext(container))
        dir = FileManager.default.temporaryDirectory.appending(path: "phone-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let dir = self.dir
        engine = DriveSync(
            store: store,
            settings: DriveSettings(defaults: UserDefaults(suiteName: "test-\(UUID().uuidString)")!),
            fileURL: { dir.appending(path: $0) },
            drive: { drive },
        )
    }

    func file(_ id: String) -> URL { dir.appending(path: id) }

    func sync() async throws {
        await engine.sync()
        if case .failed(let m) = engine.state { throw DriveError(message: m) }
    }

    func get(_ id: String) -> Paper? { store.get(id) }

    func edit(_ id: String, at: Int64, _ change: (Paper) -> Void) {
        let p = get(id)!
        change(p)
        p.updatedAt = at
        p.dirty = true
        store.save()
    }

    func add(_ id: String, at: Int64, _ fill: (Paper) -> Void) {
        let p = Paper(id: id, createdAt: 1, updatedAt: at)
        fill(p)
        store.insert(p)
    }
}

/** Two phones syncing through the stand-in Drive: the Android DriveSyncTest's cases. */
@MainActor
struct DriveSyncTests {
    @Test func twoPhonesStayInSync() async throws {
        let drive = FakeDrive()
        let a = try Phone(drive), b = try Phone(drive)

        // A adds a paper with a file.
        let bytes = Data((0..<300_000).map { UInt8($0 % 251) })
        try bytes.write(to: a.file("d1"))
        a.add("d1", at: 1000) { p in
            p.vendor = "Wolt"; p.totalCents = 11800; p.currency = "ILS"; p.tags = ["Food"]
            p.fileHash = "h1"; p.mimeType = "application/pdf"; p.fileLocal = true
        }
        try await a.sync()
        #expect(a.get("d1")?.dirty == false)
        #expect(a.get("d1")?.fileUploaded == true)
        #expect(await drive.names == ["d1.json", "d1.file"])

        // A's own upload coming back isn't downloaded again.
        let reads = await drive.reads
        try await a.sync()
        #expect(await drive.reads == reads)

        // B gets it, file included.
        try await b.sync()
        #expect(b.get("d1")?.vendor == "Wolt")
        #expect(b.get("d1")?.tags == ["Food"])
        #expect(b.get("d1")?.fileLocal == true)
        #expect(try Data(contentsOf: b.file("d1")) == bytes)

        // B edits; A sees it and keeps its file.
        b.edit("d1", at: 2000) { $0.vendor = "Wolt Market" }
        try await b.sync()
        try await a.sync()
        #expect(a.get("d1")?.vendor == "Wolt Market")
        #expect(a.get("d1")?.fileLocal == true)
        #expect(await drive.files.count == 2)

        // Both edit offline; the later edit wins on both phones.
        a.edit("d1", at: 3000) { $0.notes = "from A" }
        b.edit("d1", at: 4000) { $0.notes = "from B" }
        try await a.sync(); try await b.sync(); try await a.sync()
        #expect(a.get("d1")?.notes == "from B")
        #expect(b.get("d1")?.notes == "from B")
        // The older edit arriving late doesn't win either.
        a.edit("d1", at: 3500) { $0.notes = "late A" }
        b.edit("d1", at: 4500) { $0.notes = "B again" }
        try await b.sync(); try await a.sync(); try await b.sync()
        #expect(a.get("d1")?.notes == "B again")
        #expect(b.get("d1")?.notes == "B again")

        // A new phone gets everything.
        let c = try Phone(drive)
        try await c.sync()
        #expect(c.get("d1")?.notes == "B again")
        #expect(try Data(contentsOf: c.file("d1")) == bytes)

        // A deletes for good; B loses it, file too, and Drive is empty.
        a.edit("d1", at: 5000) { $0.deleted = true }
        try await a.sync()
        #expect(a.get("d1") == nil)
        try await b.sync()
        #expect(b.get("d1") == nil)
        #expect(!FileManager.default.fileExists(atPath: b.file("d1").path))
        #expect(await drive.files.isEmpty)
    }

    /** Signing in with papers already on the phone: they all go up. */
    @Test func papersOnThePhoneGoUpOnFirstSignIn() async throws {
        let drive = FakeDrive()
        let a = try Phone(drive)
        for i in 1...3 {
            try Data([UInt8(i)]).write(to: a.file("p\(i)"))
            a.add("p\(i)", at: 100 + Int64(i)) { p in
                p.vendor = "V\(i)"; p.fileHash = "h\(i)"; p.fileLocal = true
                p.rev = "old"; p.dirty = false; p.fileUploaded = true
            }
        }
        await a.engine.connect(email: "me@example.com")
        try await a.sync()
        #expect(await drive.files.count == 6)
        let b = try Phone(drive)
        try await b.sync()
        for i in 1...3 {
            #expect(b.get("p\(i)")?.vendor == "V\(i)")
            #expect(try Data(contentsOf: b.file("p\(i)")) == Data([UInt8(i)]))
        }
    }

    /** A paper deleted on one phone while edited later on another comes back. */
    @Test func editAfterDeleteBringsItBack() async throws {
        let drive = FakeDrive()
        let a = try Phone(drive), b = try Phone(drive)
        a.add("d", at: 1000) { $0.vendor = "X" }
        try await a.sync()
        try await b.sync()
        a.edit("d", at: 2000) { $0.deleted = true }
        b.edit("d", at: 3000) { $0.notes = "keep me" }
        try await a.sync(); try await b.sync(); try await a.sync()
        #expect(a.get("d")?.notes == "keep me")
        #expect(b.get("d")?.notes == "keep me")
    }
}

/** Answers queued per test; requests recorded. */
final class MockDrive: URLProtocol, @unchecked Sendable {
    struct Reply { var code = 200; var body = Data(); var headers: [String: String] = [:] }
    nonisolated(unsafe) static var replies: [Reply] = []
    nonisolated(unsafe) static var requests: [(URLRequest, Data)] = []

    static func reply(_ json: String, code: Int = 200, headers: [String: String] = [:]) {
        replies.append(Reply(code: code, body: Data(json.utf8), headers: headers))
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buf = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let n = stream.read(&buf, maxLength: buf.count)
                if n <= 0 { break }
                body.append(buf, count: n)
            }
            stream.close()
        }
        MockDrive.requests.append((request, body))
        let r = MockDrive.replies.isEmpty ? Reply(code: 500) : MockDrive.replies.removeFirst()
        let response = HTTPURLResponse(url: request.url!, statusCode: r.code, httpVersion: "HTTP/1.1", headerFields: r.headers)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: r.body)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/** Token handing for the client: "t1" first, "t2" once t1 is dropped. */
actor Tokens {
    var tokens = ["t1", "t2"]
    var cleared: [String] = []
    func current() -> String { tokens.first! }
    func clear(_ t: String) { cleared.append(t); tokens.removeFirst() }
}

/** The Drive REST calls, against a stand-in server (the Android DriveClientTest's cases). */
@Suite(.serialized)
struct DriveClientTests {
    let tokens = Tokens()
    let client: DriveClient

    init() {
        MockDrive.replies = []
        MockDrive.requests = []
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [MockDrive.self]
        let tokens = self.tokens
        client = DriveClient(token: { await tokens.current() }, clearToken: { await tokens.clear($0) }, base: "https://drive.test", session: URLSession(configuration: config))
    }

    @Test func createUploadsDetailsThenContent() async throws {
        MockDrive.reply("", headers: ["Location": "https://drive.test/upload-session"])
        MockDrive.reply(#"{"id":"F1"}"#)
        let id = try await client.put(nil, name: "d1.json", updatedAt: 1234, content: .data(Data("{}".utf8), mimeType: DriveClient.json))
        #expect(id == "F1")
        let (start, meta) = MockDrive.requests[0]
        #expect(start.httpMethod == "POST")
        #expect(start.url!.absoluteString.contains("/upload/drive/v3/files?uploadType=resumable"))
        let m = String(decoding: meta, as: UTF8.self)
        #expect(m.contains(#""name":"d1.json""#) && m.contains("appDataFolder") && m.contains(#""updatedAt":"1234""#))
        #expect(start.value(forHTTPHeaderField: "Authorization") == "Bearer t1")
        let (upload, content) = MockDrive.requests[1]
        #expect(upload.httpMethod == "PUT")
        #expect(content == Data("{}".utf8))
    }

    @Test func uploadsAFileFromThePhone() async throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "up-\(UUID().uuidString)")
        let bytes = Data((0..<10_000).map { UInt8($0 % 256) })
        try bytes.write(to: url)
        MockDrive.reply("", headers: ["Location": "https://drive.test/s"])
        MockDrive.reply(#"{"id":"F2"}"#)
        #expect(try await client.put(nil, name: "d1.file", updatedAt: nil, content: .file(url, mimeType: "application/pdf")) == "F2")
        #expect(MockDrive.requests[1].1 == bytes)
    }

    @Test func updateKeepsTheNameAndGone404() async throws {
        MockDrive.reply(#"{"error":{"message":"File not found"}}"#, code: 404)
        do {
            _ = try await client.put("F9", name: "d1.json", updatedAt: 1, content: .data(Data("{}".utf8), mimeType: DriveClient.json))
            Issue.record("a gone file was written")
        } catch let e as DriveError {
            #expect(e.code == 404)
        }
        let (r, body) = MockDrive.requests[0]
        #expect(r.httpMethod == "PATCH")
        #expect(!String(decoding: body, as: UTF8.self).contains(#""name""#))
    }

    @Test func changesPagesAndRemovals() async throws {
        MockDrive.reply(#"{"nextPageToken":"p2","changes":[{"fileId":"A","removed":false,"file":{"id":"A","name":"d1.json","appProperties":{"updatedAt":"5"}}},{"fileId":"B","removed":true}]}"#)
        let page = try await client.changes("p1")
        #expect(page.next == "p2")
        #expect(!page.done)
        #expect(page.changes[0].file == DriveFile(id: "A", name: "d1.json", updatedAt: 5))
        #expect(page.changes[1].file == nil)
        MockDrive.reply(#"{"newStartPageToken":"p3","changes":[{"fileId":"C","file":{"id":"C","name":"d2.json","trashed":true}}]}"#)
        let last = try await client.changes("p2")
        #expect(last.done)
        #expect(last.next == "p3")
        #expect(last.changes[0].file == nil)
    }

    @Test func aTurnedDownTokenIsReplacedOnce() async throws {
        MockDrive.reply(#"{"error":{"message":"Invalid Credentials"}}"#, code: 401)
        MockDrive.reply(#"{"startPageToken":"42"}"#)
        #expect(try await client.startPageToken() == "42")
        #expect(await tokens.cleared == ["t1"])
        #expect(MockDrive.requests[1].0.value(forHTTPHeaderField: "Authorization") == "Bearer t2")
    }

    @Test func findQueriesByName() async throws {
        MockDrive.reply(#"{"files":[{"id":"X"}]}"#)
        #expect(try await client.find("d1.file") == "X")
        let q = URLComponents(url: MockDrive.requests[0].0.url!, resolvingAgainstBaseURL: false)!.queryItems!.first { $0.name == "q" }?.value
        #expect(q == "name = 'd1.file' and trashed = false")
    }
}
