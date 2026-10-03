import UserNotifications

enum Notifier {
    static func requestAuthorization() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if !granted { NSLog("Notification permission not granted: \(String(describing: error))") }
        }
    }

    static func show(_ payload: Payload) {
        let content = UNMutableNotificationContent()
        content.title = payload.app
        if !payload.title.isEmpty { content.subtitle = payload.title }
        content.body = payload.text
        content.sound = .default
        content.threadIdentifier = payload.pkg  // groups notifications per Android app
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }
}
