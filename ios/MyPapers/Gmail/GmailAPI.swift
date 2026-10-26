import Foundation

/** A failure talking to Gmail. [needsReconnect]: Google no longer accepts the app's access (connect Gmail again). */
struct GmailError: LocalizedError, Sendable {
    let message: String
    var needsReconnect = false
    /** The HTTP status of an error answer, 0 if none. */
    var code = 0

    var errorDescription: String? { message }
}

/** A JSON object from the Gmail API (a message, a part), passed between tasks as is. */
struct GmailJSON: @unchecked Sendable {
    let object: [String: Any]
    init(_ object: [String: Any]) { self.object = object }
    subscript(key: String) -> Any? { object[key] }
}

/** An attachment worth offering: its part id, and what it is. */
struct PaperPart: Equatable, Sendable {
    let partId: String
    let fileName: String
    let mimeType: String
    let size: Int64
}

/**
 * The Gmail API, read-only (gmail.readonly). [token] gives an access token (fresh when the old one
 * expired); it's asked again once if Google says the current one isn't valid.
 */
final class GmailAPI: Sendable {
    private let token: @Sendable () async throws -> String
    private let base: String
    private let session: URLSession
    private let pacer = Pacer()

    init(token: @escaping @Sendable () async throws -> String,
         base: String = "https://gmail.googleapis.com/gmail/v1/users/me",
         session: URLSession = .shared) {
        self.token = token
        self.base = base
        self.session = session
    }

    /** Ids of the emails matching a Gmail search (same syntax as the Gmail search box). */
    func search(_ query: String) async throws -> [String] {
        var ids: [String] = []
        var pageToken: String?
        repeat {
            var c = URLComponents(string: "\(base)/messages")!
            c.queryItems = [URLQueryItem(name: "q", value: query), URLQueryItem(name: "maxResults", value: "500")]
            if let pageToken { c.queryItems!.append(URLQueryItem(name: "pageToken", value: pageToken)) }
            let page = try await get(c.url!)
            for m in page["messages"] as? [[String: Any]] ?? [] { if let id = m["id"] as? String { ids.append(id) } }
            pageToken = page["nextPageToken"] as? String
        } while pageToken != nil
        return ids
    }

    /** The full message (headers and part tree; small bodies inline, attachments by id). */
    func message(_ id: String) async throws -> GmailJSON {
        var c = URLComponents(string: "\(base)/messages/\(id)")!
        c.queryItems = [URLQueryItem(name: "format", value: "full")]
        return GmailJSON(try await get(c.url!))
    }

    /**
     * Several messages, a few requests at a time ([get] waits and retries when Gmail says to slow
     * down). An email deleted since the search is skipped; any other failure fails the whole batch
     * rather than losing emails.
     */
    func messages(_ ids: [String]) async throws -> [GmailJSON] {
        try await withThrowingTaskGroup(of: (Int, GmailJSON?).self) { group in
            var results = [GmailJSON?](repeating: nil, count: ids.count)
            var next = 0
            func add() {
                let i = next, id = ids[i]
                next += 1
                group.addTask {
                    do { return (i, try await self.message(id)) } catch let e as GmailError where e.code == 404 { return (i, nil) }
                }
            }
            for _ in 0..<min(5, ids.count) { add() }
            while let (i, m) = try await group.next() {
                results[i] = m
                if next < ids.count { add() }
            }
            return results.compactMap { $0 }
        }
    }

    /** Saves a part's content (an attachment, or a body too big to come inline) into [to]. */
    func savePart(gmailId: String, part: GmailJSON, to url: URL) async throws {
        guard let data = try await partBytes(gmailId: gmailId, part: part.object), !data.isEmpty else { throw GmailError(message: "The attachment is empty") }
        try data.write(to: url)
    }

    /** The email's HTML (plain-text emails as preformatted text), with its sender and subject on top. */
    func emailHTML(gmailId: String, message: GmailJSON) async throws -> String {
        guard let payload = message["payload"] as? [String: Any] else { throw GmailError(message: "The email has no content") }
        var html: String?
        if let p = GmailAPI.findBody(payload, type: "text/html"), let d = try await partBytes(gmailId: gmailId, part: p) { html = String(decoding: d, as: UTF8.self) }
        var text: String?
        if html == nil, let p = GmailAPI.findBody(payload, type: "text/plain"), let d = try await partBytes(gmailId: gmailId, part: p) { text = String(decoding: d, as: UTF8.self) }
        let header = "<p style=\"font-family:sans-serif;color:#555;font-size:12px\">" +
            "\(GmailAPI.escape(GmailAPI.header(payload, "From") ?? "")) · \(GmailAPI.escape(GmailAPI.header(payload, "Subject") ?? ""))</p>"
        if let html { return header + html }
        if let text { return "\(header)<pre style=\"white-space:pre-wrap;font-family:sans-serif\">\(GmailAPI.escape(text))</pre>" }
        throw GmailError(message: "The email has no text")
    }

    private func partBytes(gmailId: String, part: [String: Any]) async throws -> Data? {
        guard let body = part["body"] as? [String: Any] else { return nil }
        var data = body["data"] as? String
        if data == nil, let attachmentId = body["attachmentId"] as? String {
            data = try await get(URL(string: "\(base)/messages/\(gmailId)/attachments/\(attachmentId)")!)["data"] as? String
        }
        return data.flatMap(GmailAPI.base64URL)
    }

    private func get(_ url: URL, refreshed: Bool = false, attempt: Int = 0) async throws -> [String: Any] {
        try await pacer.waitTurn()
        var request = URLRequest(url: url, timeoutInterval: 120)
        request.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let text = String(decoding: data, as: UTF8.self)
        // Too fast: Gmail's per-second and per-minute quotas.
        let rateLimited = code == 429 || (code == 403 && (text.contains("rateLimitExceeded") || text.contains("Quota exceeded") || text.contains("quotaExceeded")))
        switch true {
        case (200...299).contains(code):
            guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw GmailError(message: "Gmail sent something unexpected") }
            return o
        case (rateLimited || code >= 500) && attempt < 7:
            // Wait (2, 4, 8, 16, 32, then 60 s: a per-minute quota refills within a minute) and try again.
            try await Task.sleep(for: .milliseconds(min(2000 << attempt, 60_000)))
            return try await get(url, refreshed: refreshed, attempt: attempt + 1)
        case code == 401 && !refreshed:
            return try await get(url, refreshed: true, attempt: attempt) // expired: once more with a fresh token
        case code == 401:
            throw GmailError(message: "Gmail access was revoked. Connect Gmail again.", needsReconnect: true, code: code)
        case code == 403 && text.contains("accessNotConfigured"):
            throw GmailError(message: "The Gmail API isn't enabled in the Google Cloud project", code: code)
        case code == 403 && text.range(of: "insufficient", options: .caseInsensitive) != nil:
            throw GmailError(message: "Connect Gmail again in Settings", needsReconnect: true, code: code)
        default:
            throw GmailError(message: "Gmail (\(code)): \(GmailAPI.errorMessage(text))", code: code)
        }
    }

    /** At most one request every 33 ms across all parallel calls (Gmail allows ~50/s per user). */
    private actor Pacer {
        private var last = ContinuousClock.now - .seconds(1)

        func waitTurn() async throws {
            let wait = last + .milliseconds(33) - ContinuousClock.now
            last = max(ContinuousClock.now, last + .milliseconds(33))
            if wait > .zero { try await Task.sleep(for: wait) }
        }
    }

    // MARK: Queries and messages (no network)

    /** Images smaller than this are logos, signatures and tracking pixels, not photographed receipts. */
    static let minImageBytes: Int64 = 30_000

    /**
     * Which mail a scan looks at: since the last completed scan (an hour's overlap, so nothing at the
     * edge is missed), or the whole window for the first scan and when the window was widened since.
     */
    static func scanWindow(lastScanAt: Int64, coveredMonths: Int, monthsBack: Int) -> String {
        lastScanAt == 0 || monthsBack > coveredMonths ? "newer_than:\(monthsBack)m" : "after:\((lastScanAt - 3_600_000) / 1000)"
    }

    static func attachmentQuery(_ window: String) -> String {
        "\(window) has:attachment (filename:pdf OR filename:jpg OR filename:jpeg OR filename:png OR filename:heic)"
    }

    /** Receipts written in the email: by subject, or Gmail's Purchases category when the email talks money, minus shipping updates. */
    static func receiptQuery(_ window: String) -> String {
        "\(window) (subject:(receipt OR invoice OR \"tax invoice\" OR \"order confirmation\" OR \"payment confirmation\" OR \"payment received\" OR \"your order\" OR קבלה OR חשבונית OR \"אישור הזמנה\" OR \"אישור תשלום\") " +
            "OR (category:purchases (receipt OR invoice OR total OR קבלה OR חשבונית))) " +
            "-subject:(shipped OR delivered OR \"out for delivery\" OR \"at customs\" OR tracking OR package OR shipment OR משלוח OR נשלח OR \"נמסר\")"
    }

    /** The PDFs and photos attached to an email, skipping logos and pictures shown inside it. */
    static func paperParts(_ message: GmailJSON) -> [PaperPart] {
        var found: [PaperPart] = []
        if let payload = message["payload"] as? [String: Any] { walk(payload) { if let p = paperPart($0) { found.append(p) } } }
        return found
    }

    static func header(_ payload: [String: Any], _ name: String) -> String? {
        (payload["headers"] as? [[String: Any]])?
            .first { ($0["name"] as? String)?.caseInsensitiveCompare(name) == .orderedSame }?["value"] as? String
    }

    /** "Shufersal <noreply@shufersal.co.il>" → "Shufersal". */
    static func senderName(_ from: String) -> String {
        let name = String(from.split(separator: "<", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
            .trimmingCharacters(in: .whitespaces).trimmingCharacters(in: CharacterSet(charactersIn: "\""))
        if !name.isEmpty { return name }
        guard let open = from.firstIndex(of: "<") else { return from }
        let rest = from[from.index(after: open)...]
        return String(rest.split(separator: ">", maxSplits: 1, omittingEmptySubsequences: false).first ?? "")
    }

    static func partById(_ message: GmailJSON, _ partId: String) -> GmailJSON? {
        var found: [String: Any]?
        if let payload = message["payload"] as? [String: Any] { walk(payload) { if $0["partId"] as? String == partId { found = $0 } } }
        return found.map(GmailJSON.init)
    }

    /** Gmail's base64url (no padding) → bytes. */
    static func base64URL(_ s: String) -> Data? {
        var b = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        b = b.filter { !$0.isWhitespace }
        while b.count % 4 != 0 { b += "=" }
        return Data(base64Encoded: b)
    }

    private static func walk(_ part: [String: Any], _ visit: ([String: Any]) -> Void) {
        if let children = part["parts"] as? [[String: Any]], !children.isEmpty { children.forEach { walk($0, visit) } } else { visit(part) }
    }

    static func findBody(_ payload: [String: Any], type: String) -> [String: Any]? {
        var found: [String: Any]?
        walk(payload) { p in
            let attachment = header(p, "Content-Disposition")?.lowercased().hasPrefix("attachment") == true
            if found == nil, !attachment, (p["mimeType"] as? String)?.caseInsensitiveCompare(type) == .orderedSame { found = p }
        }
        return found
    }

    private static func paperPart(_ part: [String: Any]) -> PaperPart? {
        let name = part["filename"] as? String ?? ""
        let type = (part["mimeType"] as? String ?? "").lowercased()
        let ext = name.contains(".") ? String(name.split(separator: ".").last ?? "").lowercased() : ""
        let isPdf = type == "application/pdf" || ext == "pdf"
        let isImage = type.hasPrefix("image/") || ["jpg", "jpeg", "png", "heic"].contains(ext)
        guard isPdf || isImage else { return nil }
        let size = ((part["body"] as? [String: Any])?["size"] as? NSNumber)?.int64Value ?? 0
        if isImage {
            if size < minImageBytes { return nil }
            // Pictures shown inside the email (logos, banners) rather than attached.
            let disposition = (header(part, "Content-Disposition") ?? "").lowercased()
            let inline = disposition.hasPrefix("inline") || (header(part, "Content-ID") != nil && name.isEmpty)
            if inline && !disposition.hasPrefix("attachment") { return nil }
        }
        let fileName = name.isEmpty ? (isPdf ? "attachment.pdf" : "photo.\(ext.isEmpty ? "jpg" : ext)") : name
        let mime = isPdf ? "application/pdf" : (type.hasPrefix("image/") ? type : "image/jpeg")
        return PaperPart(partId: part["partId"] as? String ?? "", fileName: fileName, mimeType: mime, size: size)
    }

    private static func errorMessage(_ body: String) -> String {
        let o = (try? JSONSerialization.jsonObject(with: Data(body.utf8))) as? [String: Any]
        return ((o?["error"] as? [String: Any])?["message"] as? String) ?? String(body.prefix(200))
    }

    static func escape(_ s: String) -> String {
        s.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;").replacingOccurrences(of: ">", with: "&gt;")
    }
}
