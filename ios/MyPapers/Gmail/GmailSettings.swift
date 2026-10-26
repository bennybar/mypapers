import Foundation
import Observation

/** Which Gmail is connected, the scan options, and scan progress (this phone only). */
@MainActor @Observable
final class GmailSettings {
    @ObservationIgnored private let defaults: UserDefaults

    /** The Google account Gmail was connected with (the signed-in one); nil = not connected. */
    private(set) var email: String?
    /** How far back the first scan looks: 3, 6, 12 or 24. */
    private(set) var monthsBack: Int
    /** Look for new papers once a day after the first scan. */
    private(set) var dailySweep: Bool
    /** When the last completed scan started (epoch ms); 0 = never: the next scan is the first, full one. */
    var lastScanAt: Int64 { didSet { defaults.set(lastScanAt, forKey: K.lastScanAt) } }
    /** How long the last completed scan took (ms). */
    var lastScanTook: Int64 { didSet { defaults.set(lastScanTook, forKey: K.lastScanTook) } }
    /** How many months back the completed scans have covered; a longer window scans the rest once. */
    var coveredMonths: Int { didSet { defaults.set(coveredMonths, forKey: K.coveredMonths) } }

    var isConnected: Bool { !(email ?? "").isEmpty }

    private enum K {
        static let email = "gmail.email"
        static let monthsBack = "gmail.monthsBack"
        static let dailySweep = "gmail.dailySweep"
        static let lastScanAt = "gmail.lastScanAt"
        static let lastScanTook = "gmail.lastScanTook"
        static let coveredMonths = "gmail.coveredMonths"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        email = defaults.string(forKey: K.email).flatMap { $0.isEmpty ? nil : $0 }
        monthsBack = defaults.object(forKey: K.monthsBack) as? Int ?? 6
        dailySweep = defaults.object(forKey: K.dailySweep) as? Bool ?? true
        lastScanAt = (defaults.object(forKey: K.lastScanAt) as? NSNumber)?.int64Value ?? 0
        lastScanTook = (defaults.object(forKey: K.lastScanTook) as? NSNumber)?.int64Value ?? 0
        coveredMonths = defaults.object(forKey: K.coveredMonths) as? Int ?? 0
    }

    /** Connects (an address) or disconnects (nil). Another mailbox: the next scan is a full first scan again. */
    func setEmail(_ address: String?) {
        let new = (address ?? "").isEmpty ? nil : address
        if new != email {
            lastScanAt = 0
            coveredMonths = 0
        }
        email = new
        defaults.set(new ?? "", forKey: K.email)
    }

    func setMonthsBack(_ months: Int) {
        monthsBack = months
        defaults.set(months, forKey: K.monthsBack)
    }

    func setDailySweep(_ on: Bool) {
        dailySweep = on
        defaults.set(on, forKey: K.dailySweep)
    }
}
