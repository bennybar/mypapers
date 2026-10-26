import Foundation
import Observation

/** Changes to a paper the AI proposes ("set the title to …", "add tag Car"); nil/empty: leave it. */
struct PaperChanges: Codable, Hashable, Sendable {
    var title: String?
    var vendor: String?
    var docType: String?
    /** yyyy-MM-dd. */
    var date: String?
    var total: Double?
    var currency: String?
    var addTags: [String] = []
    var removeTags: [String] = []
    /** Labels of key details to remove. */
    var removeDetails: [String] = []

    var isEmpty: Bool {
        title == nil && vendor == nil && docType == nil && date == nil && total == nil && currency == nil &&
            addTags.isEmpty && removeTags.isEmpty && removeDetails.isEmpty
    }
}

/**
 * One question or instruction about a paper and what came back: a reply, key details found (saved
 * only on request) and proposed changes (applied only on request). Pending while reply and error are nil.
 */
struct PaperTurn: Codable, Hashable, Sendable {
    var question: String
    var reply: String?
    var details: [KeyDetail]?
    var changes: PaperChanges?
    var error: String?
    /** The label of the detail this reworks: saving replaces that detail, whatever the answer's labels. */
    var reworks: String?
    var saved = false
    var applied = false

    var done: Bool { reply != nil || error != nil }
}

/** One answer in a conversation about all the papers; pending while answer and error are nil. */
struct AllTurn: Codable, Hashable, Sendable {
    var question: String
    var answer: String?
    var paperIds: [String] = []
    /** Per currency, in cents. */
    var totals: [String: Int64]?
    var error: String?

    var done: Bool { answer != nil || error != nil }
}

/**
 * Ask AI conversations: one per paper, and one about all the papers. Kept until closed, so an answer
 * that came while the app was away is still there.
 */
@MainActor @Observable
final class Conversations {
    private(set) var papers: [String: [PaperTurn]]
    private(set) var all: [AllTurn]?
    private let defaults = UserDefaults.standard

    init() {
        papers = (defaults.data(forKey: "threads.papers")).flatMap { try? JSONDecoder().decode([String: [PaperTurn]].self, from: $0) } ?? [:]
        all = (defaults.data(forKey: "threads.all")).flatMap { try? JSONDecoder().decode([AllTurn].self, from: $0) }
    }

    /** [turns] nil or empty: the conversation is closed. */
    func setPaper(_ id: String, _ turns: [PaperTurn]?) {
        papers[id] = (turns?.isEmpty ?? true) ? nil : turns
        defaults.set(try? JSONEncoder().encode(papers), forKey: "threads.papers")
    }

    func setAll(_ turns: [AllTurn]?) {
        all = (turns?.isEmpty ?? true) ? nil : turns
        defaults.set(try? JSONEncoder().encode(all), forKey: "threads.all")
    }
}
