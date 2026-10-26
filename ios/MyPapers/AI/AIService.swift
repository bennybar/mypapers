import Foundation

/**
 * The AI's work on papers (as Android's DocumentRepository did it): reading a paper, a question about
 * one paper, a question about all of them, and writing a paper out. Nothing asked for is saved here;
 * [PaperStore.saveDetails] and [PaperStore.applyChanges] do that when the user chooses.
 */
@MainActor
final class AIService {
    let store: PaperStore
    let settings: AISettings
    let client: OpenAIClient

    init(store: PaperStore, settings: AISettings, client: OpenAIClient = OpenAIClient()) {
        self.store = store
        self.settings = settings
        self.client = client
    }

    private func config() throws -> AIConfig {
        let c = settings.config
        guard c.isSet else { throw AIError.message("Add your OpenAI key in Settings first") }
        return c
    }

    /** The file's parts, built off the main thread (a PDF's base64, photos drawn and cut). */
    private func parts(_ p: Paper, closeUps: Bool = false) async throws -> [AIPart] {
        let url = PaperFiles.url(p.id), mime = p.mimeType, name = p.fileName
        return try await Task.detached(priority: .userInitiated) {
            closeUps ? try AIInput.closeUpParts(fileURL: url, mimeType: mime, fileName: name)
                     : try AIInput.parts(fileURL: url, mimeType: mime, fileName: name)
        }.value
    }

    /** Reads (or re-reads) a paper: fills the fields, adds tags (reusing existing ones), flags a likely duplicate. */
    func extract(id: String) async throws {
        guard let doc = store.live(id) else { return }
        guard settings.config.isSet else {
            doc.aiState = ""
            store.update(doc)
            return
        }
        guard doc.fileLocal else {
            store.markFailed(id, "The file isn't on this phone yet")
            return
        }
        let config = try config()
        let all = store.all()
        var seen = Set<String>()
        let existingTags = all.flatMap(\.tags).filter { seen.insert($0.lowercased()).inserted }.sorted { $0.lowercased() < $1.lowercased() }
        let input = try await parts(doc)
        let extraction = try await client.extract(config, prompt: Prompts.extraction(existingTags: existingTags, locale: Format.locale), parts: input)
        // The latest copy: it may have been edited while the model was reading.
        guard let latest = store.live(id) else { return }
        ExtractionRules.apply(extraction, to: latest, existingTags: existingTags)
        latest.duplicateOf = Duplicates.find(latest, among: all)?.id
        store.update(latest)
    }

    /**
     * The last of [turns] (a question or instruction about this paper) answered, with the earlier ones as
     * the conversation so far. The answer proposes; saving and applying are the user's.
     */
    func askPaper(id: String, turns: [PaperTurn]) async throws -> PaperTurn {
        guard var turn = turns.last else { throw AIError.badAnswer }
        guard let doc = store.live(id) else { turn.error = "This paper was deleted"; return turn }
        let config = try config()
        guard doc.fileLocal else { throw AIError.message("The file isn't on this phone yet") }
        let input = try await parts(doc)
        var record = "Title: \(doc.title)\nVendor: \(doc.vendor)\nType: \(doc.docType)\nDate: \(doc.date ?? "")\n"
        record += "Amount: \(doc.totalCents.map { "\(Double($0) / 100)" } ?? "") \(doc.currency ?? "")\n"
        record += "Tags: \(doc.tags.joined(separator: ", "))\nDetails:"
        for d in doc.details { record += "\n  \(d.label): \(d.value)" }
        let prompt = Prompts.ask(record: record, history: turns.dropLast().filter { $0.reply != nil }, instruction: turn.question)
        let json = try await client.complete(config, prompt: prompt, parts: input, schemaName: "answer", schema: Prompts.askSchema)
        guard let o = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { throw AIError.badAnswer }
        let c = o["changes"] as? [String: Any] ?? [:]
        func str(_ k: String) -> String? {
            let s = (c[k] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines)
            return s?.isEmpty == false ? s : nil
        }
        func list(_ k: String) -> [String] {
            ((c[k] as? [Any]) ?? []).compactMap { ($0 as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) }.filter { !$0.isEmpty }
        }
        let changes = PaperChanges(
            title: str("title"), vendor: str("vendor"),
            docType: str("docType").flatMap { documentTypes.contains($0) ? $0 : nil },
            date: str("date").flatMap { Day(iso: $0) != nil ? $0 : nil },
            total: (c["total"] as? NSNumber)?.doubleValue,
            currency: str("currency")?.uppercased(),
            addTags: list("addTags"), removeTags: list("removeTags"), removeDetails: list("removeDetails")
        )
        turn.reply = o["reply"] as? String ?? ""
        turn.details = Extraction.details(o["details"])
        turn.changes = changes.isEmpty ? nil : changes
        return turn
    }

    /** A question about all the papers (see [AskAll]), after [history]: the answer and the papers it's based on. */
    func askAll(question: String, history: [AllTurn] = []) async throws -> AskAllAnswer {
        let config = try config()
        let docs = store.all().sorted { ($0.date ?? "") > ($1.date ?? "") }
        if docs.isEmpty { return AskAllAnswer(answer: "There are no papers yet.", papers: [], totals: nil) }
        func numbered(_ ns: [Int]) -> [(Int, Paper)] { ns.compactMap { n in docs.indices.contains(n - 1) ? (n, docs[n - 1]) : nil } }
        let pick = try await client.complete(config, prompt: AskAll.pick(question, docs, history: history), parts: [], schemaName: "pick", schema: AskAll.pickSchema)
        let picked = numbered(AskAll.numbers(pick))
        if picked.isEmpty { return AskAllAnswer(answer: "Nothing in your papers seems to answer that.", papers: [], totals: nil) }
        let total = AskAll.isTotal(pick)
        let prompt = AskAll.answer(question, history: history, total: total, read: Array(picked.prefix(AskAll.maxRead)),
                                   listed: total ? Array(picked.dropFirst(AskAll.maxRead)) : [])
        let answer = try await client.complete(config, prompt: prompt, parts: [], schemaName: "answer", schema: AskAll.answerSchema)
        let used = numbered(AskAll.numbers(answer)).map(\.1)
        return AskAllAnswer(answer: AskAll.answerText(answer), papers: used, totals: total ? AskAll.totals(used) : nil)
    }

    /** All of the paper's text, written out by the AI as Markdown (handwriting too), saved on the paper. */
    func transcribe(id: String) async throws -> String {
        guard let doc = store.live(id) else { return "" }
        let config = try config()
        guard doc.fileLocal else { throw AIError.message("The file isn't on this phone yet") }
        let input = try await parts(doc, closeUps: true)
        let json = try await client.completeLong(config, prompt: Prompts.transcribe, parts: input, schemaName: "transcript",
                                                 schema: Prompts.transcribeSchema, effort: "medium")
        let text = ((try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["text"] as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if !text.isEmpty, let latest = store.live(id) {
            latest.transcript = String(text.prefix(PdfText.maxChars))
            store.update(latest)
        }
        return text
    }
}
