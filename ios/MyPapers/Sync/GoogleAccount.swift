@preconcurrency import GoogleSignIn
import Observation
import UIKit

enum AccountError: LocalizedError {
    case notSetUp
    case notSignedIn
    /** A scope the user has to allow (again) on Google's consent screen, from Settings. */
    case needsAccess(String)

    var errorDescription: String? {
        switch self {
        case .notSetUp: "Google sign-in isn't set up yet"
        case .notSignedIn: "Sign in with Google first"
        case .needsAccess(let m): m
        }
    }
}

/**
 * Sign in with Google: which account's Drive the papers live in. Asks for the app's hidden Drive
 * folder (drive.appdata: nothing else in Drive) at sign-in; other scopes (Gmail) are added when
 * that feature is turned on. Tokens are refreshed silently, also for background syncs.
 */
@MainActor @Observable
final class GoogleAccount {
    static let driveScope = "https://www.googleapis.com/auth/drive.appdata"

    /** Signed in as; nil = signed out. Kept across launches. */
    private(set) var email: String?

    @ObservationIgnored private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        email = defaults.string(forKey: "google.email")
    }

    /** The iOS OAuth client from Info.plist (GIDClientID); "" until it's created in the Cloud console. */
    static var clientID: String { (Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String) ?? "" }

    static var isSetUp: Bool { !clientID.isEmpty && !clientID.hasPrefix("$(") }

    private func configure() throws {
        guard GoogleAccount.isSetUp else { throw AccountError.notSetUp }
        if GIDSignIn.sharedInstance.configuration == nil {
            GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: GoogleAccount.clientID)
        }
    }

    /** At launch: the account signed in last time, without any UI. */
    func restore() async {
        guard (try? configure()) != nil, GIDSignIn.sharedInstance.hasPreviousSignIn() else { return }
        if let user = try? await GIDSignIn.sharedInstance.restorePreviousSignIn(), let e = user.profile?.email {
            setEmail(e)
        }
    }

    func signIn(presenting: UIViewController) async throws {
        try configure()
        let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenting, hint: email, additionalScopes: [GoogleAccount.driveScope])
        guard let e = result.user.profile?.email else { throw AccountError.notSignedIn }
        setEmail(e)
    }

    func signOut() {
        if GoogleAccount.isSetUp { GIDSignIn.sharedInstance.signOut() }
        setEmail(nil)
    }

    func hasScope(_ scope: String) -> Bool {
        GIDSignIn.sharedInstance.currentUser?.grantedScopes?.contains(scope) ?? false
    }

    /**
     * A fresh access token with [scopes] (beyond sign-in's). A missing scope is asked for on Google's
     * consent screen when [presenting] is given; otherwise (a background sync) it's an error to show.
     */
    func accessToken(adding scopes: [String] = [], presenting: UIViewController? = nil) async throws -> String {
        try configure()
        var user = GIDSignIn.sharedInstance.currentUser
        if user == nil, GIDSignIn.sharedInstance.hasPreviousSignIn() {
            user = try await GIDSignIn.sharedInstance.restorePreviousSignIn()
        }
        guard var user else { throw AccountError.notSignedIn }
        let granted = Set(user.grantedScopes ?? [])
        let missing = scopes.filter { !granted.contains($0) }
        if !missing.isEmpty {
            guard let presenting else {
                throw AccountError.needsAccess(missing.contains(GoogleAccount.driveScope) ? DriveAuthError.message : "Allow access again in Settings")
            }
            user = try await user.addScopes(missing, presenting: presenting).user
        }
        return try await user.refreshTokensIfNeeded().accessToken.tokenString
    }

    /** A token for syncing, without UI. */
    func driveToken() async throws -> String {
        do {
            return try await accessToken(adding: [GoogleAccount.driveScope])
        } catch AccountError.needsAccess {
            throw DriveAuthError()
        }
    }

    /** The OAuth redirect back into the app. */
    static func handle(url: URL) -> Bool { GIDSignIn.sharedInstance.handle(url) }

    private func setEmail(_ e: String?) {
        email = e
        defaults.set(e, forKey: "google.email")
    }
}
