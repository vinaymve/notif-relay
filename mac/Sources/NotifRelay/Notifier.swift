import OSLog
import UserNotifications

let log = Logger(subsystem: "dev.notifrelay.mac", category: "relay")

enum Notifier {
    static func requestAuthorization() {
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound]) { granted, error in
            log.notice("Notification permission granted=\(granted, privacy: .public) error=\(String(describing: error), privacy: .public)")
            center.getNotificationSettings { s in
                log.notice("auth=\(s.authorizationStatus.rawValue, privacy: .public) alert=\(s.alertSetting.rawValue, privacy: .public) style=\(s.alertStyle.rawValue, privacy: .public)")
            }
        }
    }

    static func isAllowed() async -> Bool {
        let status = await UNUserNotificationCenter.current().notificationSettings().authorizationStatus
        return status == .authorized || status == .provisional
    }

    static func show(_ payload: Payload) {
        let content = UNMutableNotificationContent()
        content.title = payload.app
        if !payload.title.isEmpty { content.subtitle = payload.title }
        content.body = payload.text
        content.sound = .default
        content.threadIdentifier = payload.pkg  // groups notifications per Android app
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request) { error in
            if let error { log.error("Posting notification failed: \(error.localizedDescription, privacy: .public)") }
        }
    }
}
