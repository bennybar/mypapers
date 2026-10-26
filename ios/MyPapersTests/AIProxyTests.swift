import Foundation
import Synchronization
import Testing
@testable import MyPapers

/** A stand-in server: queued responses, and the requests it got. */
final class FakeServer: URLProtocol, @unchecked Sendable {
    struct Reply: Sendable {
        var status = 200
        var body = ""
        var delay: Double = 0
    }

    struct Got: Sendable {
        var method: String
        var path: String
        var auth: String?
        var body: String
    }

    static let state = Mutex<(replies: [Reply], got: [Got])>(([], []))

    static func reset() { state.withLock { $0 = ([], []) } }
    static func enqueue(_ r: Reply) { state.withLock { $0.replies.append(r) } }
    static var got: [Got] { state.withLock { $0.got } }

    private let stopped = Mutex(false)

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        var body = request.httpBody ?? Data()
        if body.isEmpty, let stream = request.httpBodyStream {
            stream.open()
            var buf = [UInt8](repeating: 0, count: 64 * 1024)
            while stream.hasBytesAvailable { let n = stream.read(&buf, maxLength: buf.count); if n <= 0 { break }; body.append(buf, count: n) }
            stream.close()
        }
        let got = Got(method: request.httpMethod ?? "GET", path: request.url?.path ?? "", auth: request.value(forHTTPHeaderField: "Authorization"),
                      body: String(decoding: body, as: UTF8.self))
        let reply = FakeServer.state.withLock { s -> Reply in
            s.got.append(got)
            return s.replies.isEmpty ? Reply(status: 500, body: #"{"error":{"message":"nothing queued"}}"#) : s.replies.removeFirst()
        }
        let url = request.url!
        DispatchQueue.global().asyncAfter(deadline: .now() + reply.delay) { [self] in
            guard !stopped.withLock({ $0 }) else { return }
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: url, statusCode: reply.status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data(reply.body.utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }

    override func stopLoading() { stopped.withLock { $0 = true } }

    static func session() -> URLSession {
        let c = URLSessionConfiguration.ephemeral
        c.protocolClasses = [FakeServer.self]
        return URLSession(configuration: c)
    }
}

/** The AI through an OpenAI-compatible proxy (LiteLLM…), the stand-in server here. */
@Suite(.serialized)
struct AIProxyTests {
    let config = AIConfig(apiKey: "proxy-key", model: "claude-sonnet", baseUrl: "https://proxy.test/v1/")
    let client = OpenAIClient(session: FakeServer.session(), pollInterval: .milliseconds(50))
    let answer = #"{"id":"r1","status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"text\":\"hello\"}"}]}]}"#

    init() { FakeServer.reset() }

    @Test func requestsGoToTheProxyWithItsKey() async throws {
        FakeServer.enqueue(.init(body: answer))
        #expect(try await client.complete(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema) == #"{"text":"hello"}"#)
        let r = FakeServer.got[0]
        #expect(r.path == "/v1/responses")
        #expect(r.auth == "Bearer proxy-key")
        #expect(r.body.contains(#""model":"claude-sonnet""#))
        #expect(config.provider == "proxy.test")
    }

    /** Test is a tiny real request to /responses (LiteLLM keeps /models/{id} for its admins). */
    @Test func checkAsksTheModel() async throws {
        FakeServer.enqueue(.init(body: answer))
        try await client.check(config)
        #expect(FakeServer.got[0].path == "/v1/responses")
        FakeServer.enqueue(.init(status: 401, body: #"{"error":{"message":"Authentication Error, Invalid proxy server token passed."}}"#))
        await #expect {
            try await client.check(config)
        } throws: { ($0 as? AIError)?.localizedDescription.contains("Invalid proxy server token") == true }
    }

    /** No background mode at the proxy: asked again as one ordinary request. */
    @Test func longAnswerWithoutBackgroundMode() async throws {
        FakeServer.enqueue(.init(status: 400, body: #"{"error":{"message":"Unsupported parameter: background"}}"#))
        FakeServer.enqueue(.init(body: answer))
        #expect(try await client.completeLong(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema, effort: "medium") == #"{"text":"hello"}"#)
        #expect(FakeServer.got[0].body.contains(#""background":true"#))
        #expect(!FakeServer.got[1].body.contains("background"))
    }

    /** A proxy that answers a background request at once. */
    @Test func longAnswerGivenAtOnce() async throws {
        FakeServer.enqueue(.init(body: answer))
        #expect(try await client.completeLong(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema, effort: "medium") == #"{"text":"hello"}"#)
    }

    /** A model that doesn't reason turns down the effort: asked again without it, and from then on. */
    @Test func modelWithoutReasoning() async throws {
        FakeServer.enqueue(.init(status: 400, body: #"{"error":{"message":"litellm.BadRequestError: Unsupported parameter: 'reasoning.effort' is not supported with this model."}}"#))
        FakeServer.enqueue(.init(body: answer))
        FakeServer.enqueue(.init(body: answer))
        _ = try await client.complete(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema)
        _ = try await client.complete(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema)
        let got = FakeServer.got
        #expect(got[0].body.contains(#""reasoning""#))
        #expect(!got[1].body.contains("reasoning"))
        #expect(!got[2].body.contains("reasoning"))
    }

    /** Stopped while the AI works in the background: it's told to stop too. */
    @Test func stoppingCancelsTheBackgroundResponse() async throws {
        FakeServer.enqueue(.init(body: #"{"id":"r9","status":"in_progress"}"#))
        for _ in 0..<200 { FakeServer.enqueue(.init(body: #"{"id":"r9","status":"in_progress"}"#)) }
        let client = self.client, config = self.config
        let job = Task { try await client.completeLong(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema, effort: "medium") }
        // Once it has looked at how it's going, its id is known.
        while FakeServer.got.count < 2 { try await Task.sleep(for: .milliseconds(10)) }
        job.cancel()
        _ = await job.result
        #expect(FakeServer.got.contains { $0.method == "POST" && $0.path == "/v1/responses/r9/cancel" })
    }

    /** Stopped while waiting for an ordinary answer: the request is dropped, not waited out. */
    @Test func stoppingDropsTheRequest() async throws {
        FakeServer.enqueue(.init(body: answer, delay: 30))
        let client = self.client, config = self.config
        let job = Task { try await client.complete(config, prompt: "p", parts: [], schemaName: "t", schema: Prompts.transcribeSchema) }
        while FakeServer.got.isEmpty { try await Task.sleep(for: .milliseconds(10)) }
        let started = ContinuousClock.now
        job.cancel()
        _ = await job.result
        #expect(ContinuousClock.now - started < .seconds(5))
    }
}
