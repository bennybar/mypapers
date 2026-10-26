import Foundation

/**
 * A paper as the `<id>.json` file in Google Drive, the same shape the Android app writes
 * (it began as a CouchDB document: `_id`, `type`, and `_attachments.file` when the file is there).
 */
enum PaperJSON {
    /** [keepFile]: Drive has this paper's file (`<id>.file`). */
    static func encode(_ p: Paper, keepFile: Bool) -> Data {
        func orNull(_ v: Any?) -> Any { v ?? NSNull() }
        var o: [String: Any] = [:]
        o["_id"] = p.id
        o["type"] = "document"
        o["title"] = p.title
        o["vendor"] = p.vendor
        o["docType"] = p.docType
        o["date"] = orNull(p.date)
        o["totalCents"] = orNull(p.totalCents.map { NSNumber(value: $0) })
        o["currency"] = orNull(p.currency)
        o["vatCents"] = orNull(p.vatCents.map { NSNumber(value: $0) })
        o["tags"] = p.tags
        o["keywords"] = p.keywords
        o["notes"] = p.notes
        o["extractedText"] = p.extractedText
        o["fullText"] = p.fullText
        o["transcript"] = p.transcript
        o["details"] = p.details.map { d -> [String: Any] in
            var e: [String: Any] = ["label": d.label, "value": d.value]
            if d.asked { e["asked"] = true }
            return e
        }
        o["fileHash"] = orNull(p.fileHash)
        o["fileName"] = orNull(p.fileName)
        o["mimeType"] = orNull(p.mimeType)
        o["createdAt"] = NSNumber(value: p.createdAt)
        o["updatedAt"] = NSNumber(value: p.updatedAt)
        o["aiState"] = p.aiState
        o["aiError"] = orNull(p.aiError)
        o["duplicateOf"] = orNull(p.duplicateOf)
        o["trashedAt"] = orNull(p.trashedAt.map { NSNumber(value: $0) })
        o["edited"] = p.edited
        if keepFile { o["_attachments"] = ["file": ["stub": true]] }
        return (try? JSONSerialization.data(withJSONObject: o, options: [.sortedKeys])) ?? Data()
    }

    /** The fields read from Drive JSON, applied to [p] (a new row or the local copy being replaced). */
    static func apply(_ data: Data, to p: Paper) throws {
        guard let o = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw JSONError.notAnObject }
        func str(_ k: String) -> String? { o[k] as? String }
        func long(_ k: String) -> Int64? { (o[k] as? NSNumber)?.int64Value }
        func strings(_ k: String) -> [String] { (o[k] as? [Any])?.compactMap { $0 as? String } ?? [] }
        p.title = str("title") ?? ""
        p.vendor = str("vendor") ?? ""
        p.docType = str("docType") ?? ""
        p.date = str("date")
        p.totalCents = long("totalCents")
        p.currency = str("currency")
        p.vatCents = long("vatCents")
        p.tags = strings("tags")
        p.keywords = strings("keywords")
        p.edited = strings("edited")
        p.notes = str("notes") ?? ""
        p.extractedText = str("extractedText") ?? ""
        p.fullText = str("fullText") ?? ""
        p.transcript = str("transcript") ?? ""
        p.details = ((o["details"] as? [Any]) ?? []).compactMap { (e: Any) -> KeyDetail? in
            guard let d = e as? [String: Any], let label = d["label"] as? String, let value = d["value"] as? String,
                  !label.trimmingCharacters(in: .whitespaces).isEmpty, !value.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
            return KeyDetail(label: label, value: value, asked: (d["asked"] as? Bool) == true)
        }
        p.fileHash = str("fileHash")
        p.fileName = str("fileName")
        p.mimeType = str("mimeType")
        p.createdAt = long("createdAt") ?? 0
        p.updatedAt = long("updatedAt") ?? 0
        p.aiState = str("aiState") ?? ""
        p.aiError = str("aiError")
        p.duplicateOf = str("duplicateOf")
        p.trashedAt = long("trashedAt")
        p.fileUploaded = hasFile(o)
    }

    static func id(_ data: Data) -> String? {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["_id"] as? String
    }

    static func updatedAt(_ data: Data) -> Int64 {
        (((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["updatedAt"] as? NSNumber)?.int64Value ?? 0
    }

    static func fileHash(_ data: Data) -> String? {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any])?["fileHash"] as? String
    }

    static func hasFile(_ data: Data) -> Bool {
        ((try? JSONSerialization.jsonObject(with: data)) as? [String: Any]).map(hasFile) ?? false
    }

    private static func hasFile(_ o: [String: Any]) -> Bool {
        (o["_attachments"] as? [String: Any])?["file"] != nil
    }

    enum JSONError: Error { case notAnObject }
}
