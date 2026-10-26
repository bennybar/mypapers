import Foundation
import Synchronization

/** One content part of a request: an input_text, input_file or input_image (all their fields are strings). */
typealias AIPart = [String: String]

/**
 * The OpenAI Responses API, with the user's own key: OpenAI itself, or an OpenAI-compatible proxy
 * (LiteLLM…). Every call is cancellable: cancelling the task drops the request, and a background
 * response being waited for is cancelled at the server too.
 */
final class OpenAIClient: Sendable {
    private let session: URLSession
    /** How often a background response is looked at. */
    private let pollInterval: Duration
    /** How long a background response may take before it's given up on. */
    private let longLimit: Duration
    /** Models (by server and name) that turned down a reasoning effort: asked without one from then on. */
    private let noReasoning = Mutex<Set<String>>([])

    init(session: URLSession? = nil, pollInterval: Duration = .seconds(4), longLimit: Duration = .seconds(30 * 60)) {
        self.session = session ?? {
            let c = URLSessionConfiguration.default
            c.timeoutIntervalForRequest = 300
            c.timeoutIntervalForResource = 900
            return URLSession(configuration: c)
        }()
        self.pollInterval = pollInterval
        self.longLimit = longLimit
    }

    /**
     * Checks the key, the model and the Responses API in one tiny real request: what reading uses.
     * (Not the model lookup: on a LiteLLM proxy that's for its admins only.)
     */
    func check(_ config: AIConfig) async throws {
        _ = try await complete(config, prompt: "Reply with ok set to true.", parts: [], schemaName: "check", schema: OpenAIClient.checkSchema)
    }

    /** Reads a document; [parts]: the file as content parts, see [AIInput]. */
    func extract(_ config: AIConfig, prompt: String, parts: [AIPart]) async throws -> Extraction {
        let json = try await complete(config, prompt: prompt, parts: parts, schemaName: "document", schema: Prompts.schema)
        return try Extraction(json: Data(json.utf8))
    }

    /** Any structured answer: [schema] is the JSON schema it must follow; returns the answer's JSON text. */
    func complete(_ config: AIConfig, prompt: String, parts: [AIPart], schemaName: String, schema: String, effort: String = "low") async throws -> String {
        let reasoning = effortFor(config, effort)
        let body = OpenAIClient.requestBody(model: config.model, prompt: prompt, parts: parts, schemaName: schemaName, schema: schema, effort: reasoning)
        do {
            return try OpenAIClient.outputText(try await send(config, path: "/responses", body: body))
        } catch let e as AIError {
            guard turnedDownReasoning(config, e, sent: reasoning) else { throw e }
            return try await complete(config, prompt: prompt, parts: parts, schemaName: schemaName, schema: schema, effort: effort)
        }
    }

    /**
     * Like [complete], for answers that take minutes (writing a whole page out): the AI works on it in
     * the background and it's fetched when done, instead of one connection held open all along.
     */
    func completeLong(_ config: AIConfig, prompt: String, parts: [AIPart], schemaName: String, schema: String, effort: String) async throws -> String {
        let reasoning = effortFor(config, effort)
        var body = OpenAIClient.requestBody(model: config.model, prompt: prompt, parts: parts, schemaName: schemaName, schema: schema, effort: reasoning)
        body["background"] = true
        var response: [String: Any]
        do {
            response = try OpenAIClient.object(try await send(config, path: "/responses", body: body))
        } catch let e as AIError {
            if turnedDownReasoning(config, e, sent: reasoning) {
                return try await completeLong(config, prompt: prompt, parts: parts, schemaName: schemaName, schema: schema, effort: effort)
            }
            // A proxy without background mode: one ordinary (long) request instead.
            if config.baseUrl.trimmingCharacters(in: .whitespaces).isEmpty || e.retryable { throw e }
            return try await complete(config, prompt: prompt, parts: parts, schemaName: schemaName, schema: schema, effort: effort)
        }
        // A proxy may answer at once, without background mode.
        guard let id = response["id"] as? String else { return try OpenAIClient.outputText(response) }
        let started = ContinuousClock.now
        do {
            while ["queued", "in_progress"].contains(response["status"] as? String ?? "") {
                if ContinuousClock.now - started > longLimit { throw AIError.message("\(config.provider) took too long") }
                try await Task.sleep(for: pollInterval)
                response = try OpenAIClient.object(try await send(config, path: "/responses/\(id)", body: nil))
            }
        } catch {
            // Stopped here (or given up on): stopped there too, so it isn't worked on (and charged) for nothing.
            let cancel = request(config, path: "/responses/\(id)/cancel", body: [:])
            let session = self.session
            await Task.detached { _ = try? await session.data(for: cancel) }.value
            throw error
        }
        return try OpenAIClient.outputText(response)
    }

    // MARK: Requests

    private func request(_ config: AIConfig, path: String, body: [String: Any]?) -> URLRequest {
        var r = URLRequest(url: URL(string: config.endpoint + path)!)
        r.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        if let body {
            r.httpMethod = "POST"
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
            r.httpBody = try? JSONSerialization.data(withJSONObject: body)
        }
        return r
    }

    /** The response body; an error status as an [AIError] that names who answered. */
    private func send(_ config: AIConfig, path: String, body: [String: Any]?) async throws -> Data {
        let (data, response) = try await session.data(for: request(config, path: path, body: body))
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else { throw OpenAIClient.error(code, data, config) }
        return data
    }

    private func key(_ c: AIConfig) -> String { "\(c.endpoint)|\(c.model)" }

    /** [effort], unless this model is known to turn it down. */
    private func effortFor(_ config: AIConfig, _ effort: String) -> String? {
        noReasoning.withLock { $0.contains(key(config)) } ? nil : effort
    }

    /** A model that doesn't reason turns down a reasoning effort with a 400 that names it: remembered. */
    private func turnedDownReasoning(_ config: AIConfig, _ e: AIError, sent: String?) -> Bool {
        guard sent != nil, e.code == 400, e.localizedDescription.range(of: "reasoning", options: .caseInsensitive) != nil else { return false }
        _ = noReasoning.withLock { $0.insert(key(config)) }
        return true
    }

    // MARK: JSON

    static let checkSchema = #"{"type":"object","additionalProperties":false,"required":["ok"],"properties":{"ok":{"type":"boolean"}}}"#

    /** [effort] nil: none asked for (a model that doesn't reason). */
    static func requestBody(model: String, prompt: String, parts: [AIPart], schemaName: String, schema: String, effort: String?) -> [String: Any] {
        var body: [String: Any] = ["model": model]
        if let effort { body["reasoning"] = ["effort": effort] }
        let content: [[String: Any]] = [["type": "input_text", "text": prompt]] + parts.map { $0 as [String: Any] }
        body["input"] = [["role": "user", "content": content]]
        let schemaObject = (try? JSONSerialization.jsonObject(with: Data(schema.utf8))) ?? [:]
        body["text"] = ["format": ["type": "json_schema", "name": schemaName, "strict": true, "schema": schemaObject] as [String: Any]]
        return body
    }

    static func object(_ data: Data) throws -> [String: Any] {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw AIError.badAnswer }
        return o
    }

    static func outputText(_ data: Data) throws -> String { try outputText(object(data)) }

    /** The answer is the output_text of the "message" output item (after a "reasoning" one). */
    static func outputText(_ root: [String: Any]) throws -> String {
        let output = root["output"] as? [[String: Any]] ?? []
        for item in output where item["type"] as? String == "message" {
            for c in item["content"] as? [[String: Any]] ?? [] where c["type"] as? String == "output_text" {
                if let text = c["text"] as? String { return text }
            }
        }
        throw AIError.message("The AI returned no answer (\(root["status"] as? String ?? "?"))", retryable: true)
    }

    static func error(_ code: Int, _ body: Data, _ config: AIConfig) -> AIError {
        let message = ((try? JSONSerialization.jsonObject(with: body)) as? [String: Any])
            .flatMap { $0["error"] as? [String: Any] }.flatMap { $0["message"] as? String }
            ?? String(String(decoding: body, as: UTF8.self).prefix(200))
        let who = config.provider
        switch code {
        case 401: return .message("\(who) refused the key: \(message)", retryable: false, code: code)
        case 404: return .message("Not found at \(who): \(message)", retryable: false, code: code)
        case 429, 500...599: return .message("\(who): \(message)", retryable: true, code: code)
        default: return .message("\(who) (\(code)): \(message)", retryable: false, code: code)
        }
    }
}
