import CryptoKit
import Foundation
import UniformTypeIdentifiers

/** Each paper's file, kept as `Application Support/docs/<id>`. */
enum PaperFiles {
    static let dir: URL = {
        let d = URL.applicationSupportDirectory.appending(path: "docs", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()

    static func url(_ id: String) -> URL { dir.appending(path: id) }

    static func exists(_ id: String) -> Bool { FileManager.default.fileExists(atPath: url(id).path) }

    /** A temp file in the same folder, so the final move is atomic. */
    static func temp() -> URL { dir.appending(path: ".tmp-\(UUID().uuidString)") }

    static func delete(_ id: String) { try? FileManager.default.removeItem(at: url(id)) }

    static func deleteAll() {
        for f in (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? [] { try? FileManager.default.removeItem(at: f) }
    }

    static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 64 * 1024), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /** The file's type from its first bytes, for when the sender doesn't say. */
    static func sniffMimeType(_ url: URL) -> String? {
        guard let h = try? FileHandle(forReadingFrom: url), let head = try? h.read(upToCount: 12), head.count >= 4 else { return nil }
        try? h.close()
        let b = [UInt8](head)
        if b[0] == 0x25, b[1] == 0x50, b[2] == 0x44, b[3] == 0x46 { return "application/pdf" }
        if b[0] == 0xFF, b[1] == 0xD8, b[2] == 0xFF { return "image/jpeg" }
        if b[0] == 0x89, b[1] == 0x50, b[2] == 0x4E, b[3] == 0x47 { return "image/png" }
        if b.count >= 8, String(bytes: b[4..<8], encoding: .ascii) == "ftyp" { return "image/heic" }
        if b[0] == 0x52, b[1] == 0x49, b[2] == 0x46, b[3] == 0x46 { return "image/webp" }
        return nil
    }

    static func mimeType(_ p: Paper) -> String? { p.mimeType ?? sniffMimeType(url(p.id)) }

    static func isPDF(_ p: Paper) -> Bool { mimeType(p) == "application/pdf" }
}
