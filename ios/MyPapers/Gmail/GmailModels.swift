import Foundation
import SwiftData

/**
 * Something found in Gmail that may be a paper: an attachment, or a receipt written in the email
 * itself (imported as a PDF of the email). Local to this phone: what gets imported becomes a normal,
 * synced paper.
 */
@Model
final class GmailCandidate {
    static let kindAttachment = "attachment"
    static let kindEmail = "email"
    static let stateNew = "new"
    static let stateQueued = "queued"
    static let stateImported = "imported"
    static let stateDismissed = "dismissed"
    static let stateFailed = "failed"

    /** "<Gmail message id>:<part id>". */
    @Attribute(.unique) var key: String
    /** The Gmail API's message id. */
    var gmailId: String
    var subject: String
    var sender: String
    /** When the email was sent (epoch ms). */
    var sentAt: Int64
    /** [kindAttachment] or [kindEmail]. */
    var kind: String
    /** The part's id in the Gmail API's message payload (e.g. "1"); "" for the email as a whole. */
    var partId: String
    var fileName: String
    var mimeType: String
    var size: Int64
    /** new, queued, imported, dismissed or failed. */
    var state: String = GmailCandidate.stateNew
    var error: String?
    /** The paper it became. */
    var documentId: String?
    var foundAt: Int64

    init(key: String, gmailId: String, subject: String, sender: String, sentAt: Int64, kind: String,
         partId: String, fileName: String, mimeType: String, size: Int64, foundAt: Int64) {
        self.key = key
        self.gmailId = gmailId
        self.subject = subject
        self.sender = sender
        self.sentAt = sentAt
        self.kind = kind
        self.partId = partId
        self.fileName = fileName
        self.mimeType = mimeType
        self.size = size
        self.foundAt = foundAt
    }
}

/** An email the scan already looked at, so later scans (or a resumed one) don't fetch it again. */
@Model
final class GmailSeen {
    @Attribute(.unique) var gmailId: String
    var seenAt: Int64

    init(gmailId: String, seenAt: Int64) {
        self.gmailId = gmailId
        self.seenAt = seenAt
    }
}

enum GmailStore {
    /** For the app's ModelContainer, next to Paper. */
    static let models: [any PersistentModel.Type] = [GmailCandidate.self, GmailSeen.self]
}
