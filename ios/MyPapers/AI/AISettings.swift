import Foundation
import Observation
import Security

/** The AI to read papers with: the user's own key, the model, and optionally a proxy in front of OpenAI. */
struct AIConfig: Equatable, Sendable {
    var apiKey: String
    var model: String
    /** Read each new paper in the background as it comes in (off: only when asked). */
    var autoRead: Bool = false
    /** An OpenAI-compatible proxy (LiteLLM…), e.g. https://litellm.example.com/v1; blank: OpenAI itself. */
    var baseUrl: String = ""

    var isSet: Bool { !apiKey.trimmingCharacters(in: .whitespaces).isEmpty }

    /** Where requests go: the proxy, or OpenAI. */
    var endpoint: String {
        var s = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        return s.isEmpty ? AIConfig.openAI : s
    }

    /** Who answers, for messages ("OpenAI", or the proxy's host). */
    var provider: String {
        if baseUrl.trimmingCharacters(in: .whitespaces).isEmpty { return "OpenAI" }
        return URL(string: endpoint)?.host ?? endpoint
    }

    static let openAI = "https://api.openai.com/v1"
    static let defaultModel = "gpt-5.4-mini"
}

/**
 * The user's own OpenAI key (or a proxy's), kept in this phone's Keychain only (never synced), and
 * the model and proxy, in UserDefaults.
 */
@MainActor @Observable
final class AISettings {
    private(set) var config: AIConfig
    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        config = AIConfig(
            apiKey: Keychain.read("openai.key") ?? "",
            model: defaults.string(forKey: "ai.model") ?? AIConfig.defaultModel,
            autoRead: defaults.bool(forKey: "ai.autoRead"),
            baseUrl: defaults.string(forKey: "ai.baseUrl") ?? ""
        )
    }

    func save(_ c: AIConfig) {
        let key = c.apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if key.isEmpty { Keychain.delete("openai.key") } else { Keychain.write("openai.key", key) }
        let model = c.model.trimmingCharacters(in: .whitespacesAndNewlines)
        defaults.set(model.isEmpty ? AIConfig.defaultModel : model, forKey: "ai.model")
        defaults.set(c.autoRead, forKey: "ai.autoRead")
        defaults.set(c.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "ai.baseUrl")
        config = AIConfig(apiKey: key, model: model.isEmpty ? AIConfig.defaultModel : model, autoRead: c.autoRead,
                          baseUrl: c.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/** A few strings in the Keychain, readable only on this device after its first unlock. */
enum Keychain {
    private static let service = "com.bennybar.mypapers"

    static func read(_ account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ account: String, _ value: String) {
        delete(account)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8), kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete(_ account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}
