import Foundation
import UserNotifications

/**
 * "Expires in 7 days" reminders for papers with an expiry, renewal or due date in their details:
 * 30 and 7 days before, at 9:00. Scheduled as local notifications (iOS keeps them; no daily check
 * needed), and rescheduled whenever the papers change. Tapping one opens the paper.
 */
enum ExpiryReminders {
    private static let prefix = "expiry:"
    /** Days before the expiry to remind at. */
    private static let when = [30, 7]

    struct Item: Sendable {
        let id: String
        let title: String
        let details: [KeyDetail]
    }

    static func schedule(_ papers: [Item], dayFirst: Bool) async {
        let center = UNUserNotificationCenter.current()
        guard await center.notificationSettings().authorizationStatus == .authorized else { return }
        let pending = await center.pendingNotificationRequests().map(\.identifier).filter { $0.hasPrefix(prefix) }
        center.removePendingNotificationRequests(withIdentifiers: pending)
        let today = Day.today
        // iOS keeps at most 64 pending: the nearest ones.
        var requests: [(Date, UNNotificationRequest)] = []
        for p in papers {
            guard let expires = Expiry.date(p.details, dayFirst: dayFirst, today: today), expires >= today else { continue }
            for days in when {
                let day = expires.adding(days: -days)
                guard day >= today else { continue }
                var c = Calendar.current.dateComponents([.year, .month, .day], from: day.date)
                c.hour = 9
                guard let fire = Calendar.current.date(from: c), fire > Date() else { continue }
                let content = UNMutableNotificationContent()
                content.title = p.title
                content.body = days == 1 ? "Expires tomorrow" : "Expires in \(days) days, on \(Format.date(expires.iso) ?? expires.iso)"
                content.userInfo = ["paperId": p.id]
                content.sound = .default
                let trigger = UNCalendarNotificationTrigger(dateMatching: c, repeats: false)
                requests.append((fire, UNNotificationRequest(identifier: "\(prefix)\(p.id):\(days)", content: content, trigger: trigger)))
            }
        }
        for (_, r) in requests.sorted(by: { $0.0 < $1.0 }).prefix(50) { try? await center.add(r) }
    }
}
