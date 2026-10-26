import Foundation

/** A file in the app's Drive folder; [updatedAt]: the paper's edit time, kept on the file (nil for a paper's file). */
struct DriveFile: Equatable, Sendable {
    let id: String
    let name: String
    let updatedAt: Int64?
}

/** One entry of Drive's change feed; [file] nil = removed. */
struct DriveChange: Equatable, Sendable {
    let fileId: String
    let file: DriveFile?
}

/** [next]: the token to read on from; [done]: nothing more for now. */
struct ChangePage: Sendable {
    let changes: [DriveChange]
    let next: String
    let done: Bool
}

struct DriveError: LocalizedError, Sendable {
    let message: String
    var code: Int = 0
    var errorDescription: String? { message }
}

/** Drive access is gone (revoked, or never allowed on this phone): the user has to allow it again. */
struct DriveAuthError: LocalizedError, Sendable {
    static let message = "Allow Google Drive access again in Settings"
    var errorDescription: String? { DriveAuthError.message }
}

/** What goes into a Drive file: some bytes (a paper's JSON), or a file on the phone (its PDF or photo). */
enum DriveContent: Sendable {
    case data(Data, mimeType: String)
    case file(URL, mimeType: String)
}

/** The app's own hidden folder in the user's Google Drive (appDataFolder), as the sync needs it. */
protocol DriveStore: Sendable {
    func startPageToken() async throws -> String
    func listAll() async throws -> [DriveFile]
    func changes(_ token: String) async throws -> ChangePage
    /** The file named [name], or nil. */
    func find(_ name: String) async throws -> String?
    func read(_ id: String) async throws -> Data
    func download(_ id: String, to: URL) async throws
    /** Writes [content] to file [id] (a new file named [name] if nil); returns its id. Throws a 404 [DriveError] if [id] is gone. */
    func put(_ id: String?, name: String, updatedAt: Int64?, content: DriveContent) async throws -> String
    /** Deleting one already gone is fine. */
    func delete(_ id: String) async throws
}

/** The Drive REST API (v3), with tokens from [token]; [clearToken] drops one Google turned down. */
final class DriveClient: DriveStore {
    private let token: @Sendable () async throws -> String
    private let clearToken: @Sendable (String) async -> Void
    private let base: String
    private let session: URLSession

    init(
        token: @escaping @Sendable () async throws -> String,
        clearToken: @escaping @Sendable (String) async -> Void = { _ in },
        base: String = "https://www.googleapis.com",
        session: URLSession = .shared,
    ) {
        self.token = token
        self.clearToken = clearToken
        self.base = base
        self.session = session
    }

    static let json = "application/json; charset=UTF-8"

    func startPageToken() async throws -> String {
        let o = try await json(get("\(base)/drive/v3/changes/startPageToken"))
        guard let t = o["startPageToken"] as? String else { throw DriveError(message: "Google Drive gave no start token") }
        return t
    }

    func listAll() async throws -> [DriveFile] {
        var all: [DriveFile] = []
        var page: String?
        repeat {
            var c = URLComponents(string: "\(base)/drive/v3/files")!
            c.queryItems = [
                URLQueryItem(name: "spaces", value: "appDataFolder"),
                URLQueryItem(name: "pageSize", value: "1000"),
                URLQueryItem(name: "fields", value: "nextPageToken,files(id,name,appProperties)"),
            ] + (page.map { [URLQueryItem(name: "pageToken", value: $0)] } ?? [])
            let o = try await json(get(c.url!.absoluteString))
            all += ((o["files"] as? [[String: Any]]) ?? []).compactMap(DriveClient.driveFile)
            page = o["nextPageToken"] as? String
        } while page != nil
        return all
    }

    func changes(_ token: String) async throws -> ChangePage {
        var c = URLComponents(string: "\(base)/drive/v3/changes")!
        c.queryItems = [
            URLQueryItem(name: "pageToken", value: token),
            URLQueryItem(name: "spaces", value: "appDataFolder"),
            URLQueryItem(name: "pageSize", value: "1000"),
            URLQueryItem(name: "fields", value: "nextPageToken,newStartPageToken,changes(fileId,removed,file(id,name,trashed,appProperties))"),
        ]
        let o = try await json(get(c.url!.absoluteString))
        let changes = ((o["changes"] as? [[String: Any]]) ?? []).compactMap { c -> DriveChange? in
            guard let fileId = c["fileId"] as? String else { return nil }
            let file = c["file"] as? [String: Any]
            let gone = (c["removed"] as? Bool) == true || (file?["trashed"] as? Bool) == true
            return DriveChange(fileId: fileId, file: gone || file == nil ? nil : DriveClient.driveFile(file!))
        }
        if let next = o["nextPageToken"] as? String { return ChangePage(changes: changes, next: next, done: false) }
        guard let start = o["newStartPageToken"] as? String else { throw DriveError(message: "Google Drive gave no next token") }
        return ChangePage(changes: changes, next: start, done: true)
    }

    func find(_ name: String) async throws -> String? {
        var c = URLComponents(string: "\(base)/drive/v3/files")!
        c.queryItems = [
            URLQueryItem(name: "spaces", value: "appDataFolder"),
            URLQueryItem(name: "q", value: "name = '\(name.replacingOccurrences(of: "'", with: "\\'"))' and trashed = false"),
            URLQueryItem(name: "fields", value: "files(id)"),
        ]
        let o = try await json(get(c.url!.absoluteString))
        return ((o["files"] as? [[String: Any]])?.first)?["id"] as? String
    }

    func read(_ id: String) async throws -> Data {
        try await call { URLRequest(url: URL(string: "\(self.base)/drive/v3/files/\(id)?alt=media")!) }.0
    }

    func download(_ id: String, to: URL) async throws {
        let data = try await read(id)
        try data.write(to: to, options: .atomic)
    }

    // A resumable upload (fine for big files too): the file's details first, then its content.
    func put(_ id: String?, name: String, updatedAt: Int64?, content: DriveContent) async throws -> String {
        let stamp: Any = updatedAt.map { String($0) } ?? NSNull()
        var meta: [String: Any] = ["appProperties": ["updatedAt": stamp]]
        if id == nil {
            meta["name"] = name
            meta["parents"] = ["appDataFolder"]
        }
        let metaBody = try JSONSerialization.data(withJSONObject: meta)
        let (_, start) = try await call {
            let url = id == nil ? "\(self.base)/upload/drive/v3/files?uploadType=resumable" : "\(self.base)/upload/drive/v3/files/\(id!)?uploadType=resumable"
            var r = URLRequest(url: URL(string: url)!)
            r.httpMethod = id == nil ? "POST" : "PATCH"
            r.setValue(DriveClient.json, forHTTPHeaderField: "Content-Type")
            r.httpBody = metaBody
            return r
        }
        guard let location = start.value(forHTTPHeaderField: "Location"), let session = URL(string: location) else {
            throw DriveError(message: "Drive gave no upload address")
        }
        let (data, _) = try await call {
            var r = URLRequest(url: session)
            r.httpMethod = "PUT"
            switch content {
            case .data(let d, let type):
                r.setValue(type, forHTTPHeaderField: "Content-Type")
                r.httpBody = d
            case .file(let url, let type):
                r.setValue(type, forHTTPHeaderField: "Content-Type")
                r.httpBodyStream = InputStream(url: url)
                if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
                    r.setValue(String(size), forHTTPHeaderField: "Content-Length")
                }
            }
            return r
        }
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any], let newId = o["id"] as? String else {
            throw DriveError(message: "Drive gave no file id")
        }
        return newId
    }

    func delete(_ id: String) async throws {
        do {
            _ = try await call {
                var r = URLRequest(url: URL(string: "\(self.base)/drive/v3/files/\(id)")!)
                r.httpMethod = "DELETE"
                return r
            }
        } catch let e as DriveError where e.code == 404 {}
    }

    private func get(_ url: String) async throws -> Data {
        try await call { URLRequest(url: URL(string: url)!) }.0
    }

    private func json(_ data: Data) throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
    }

    /** One request; a turned-down token is dropped and the request tried once more with a fresh one. */
    private func call(_ build: @Sendable () -> URLRequest) async throws -> (Data, HTTPURLResponse) {
        var t = try await token()
        for attempt in 0..<2 {
            var r = build()
            r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await session.data(for: r)
            let http = response as! HTTPURLResponse
            if (200..<300).contains(http.statusCode) { return (data, http) }
            if http.statusCode == 401 {
                if attempt == 0 {
                    await clearToken(t)
                    t = try await token()
                    continue
                }
                throw DriveAuthError()
            }
            let message = ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])
                .flatMap { $0["error"] as? [String: Any] }?["message"] as? String
                ?? String(decoding: data.prefix(200), as: UTF8.self)
            throw DriveError(message: "Google Drive (\(http.statusCode)): \(message)", code: http.statusCode)
        }
        throw DriveAuthError()
    }

    private static func driveFile(_ o: [String: Any]) -> DriveFile? {
        guard let id = o["id"] as? String else { return nil }
        let updated = ((o["appProperties"] as? [String: Any])?["updatedAt"] as? String).flatMap { Int64($0) }
        return DriveFile(id: id, name: o["name"] as? String ?? "", updatedAt: updated)
    }
}
