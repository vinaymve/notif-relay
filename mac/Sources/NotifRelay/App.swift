import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI
import UserNotifications

@main
struct NotifRelayApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            Image(systemName: menuIcon)
        }

        Window("Pair Phone", id: "pair") {
            PairView(model: model)
        }
        .windowResizability(.contentSize)
        .defaultLaunchBehavior(model.isFirstLaunch ? .presented : .suppressed)
    }

    private var menuIcon: String {
        if model.paused { return "bell.slash" }
        if !model.notificationsAllowed { return "exclamationmark.triangle" }
        if case .offline = model.status { return "bell.badge.slash" }
        return "bell"
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        UNUserNotificationCenter.current().delegate = self
        Task { @MainActor in AppModel.shared.start() }
    }

    // Show banners even when our (menu bar) app counts as frontmost.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }
}

struct MenuContent: View {
    @Bindable var model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Text(model.status.label)
        if !model.notificationsAllowed {
            Button("⚠️ Notifications are off. Open Settings…") { model.openNotificationSettings() }
        }
        Divider()
        if model.recent.isEmpty {
            Text("No notifications yet")
        } else {
            Section("Recent (click to copy)") {
                ForEach(model.recent.prefix(10)) { item in
                    Button(summary(item.payload)) {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(item.payload.text, forType: .string)
                    }
                }
            }
        }
        Divider()
        Toggle("Pause Notifications", isOn: $model.paused)
        Toggle("Launch at Login", isOn: $model.launchAtLogin)
        Button("Pair Phone…") {
            NSApp.activate()
            openWindow(id: "pair")
        }
        Button("Send Test Notification") {
            Task { await model.sendTest() }
        }
        Button("Reconnect") { model.reconnect() }
        Divider()
        Button("Quit Notif Relay") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
    }

    private func summary(_ p: Payload) -> String {
        let line = [p.title, p.text].filter { !$0.isEmpty }.joined(separator: ": ")
        let short = line.count > 60 ? line.prefix(60) + "…" : line
        return "\(p.app) · \(short)"
    }
}

struct PairView: View {
    @Bindable var model: AppModel
    @State private var server = ""
    @State private var confirmingReset = false

    var body: some View {
        VStack(spacing: 14) {
            Text("Scan with Notif Relay on your phone")
                .font(.headline)
            if let image = qrImage(model.pairing.uri) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 240, height: 240)
            }
            Text(model.status.label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text("Anyone with this code can read your synced notifications. Don't share it.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            DisclosureGroup("Advanced") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Topic: \(model.pairing.topic)")
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                    TextField("Server", text: $server)
                        .textFieldStyle(.roundedBorder)
                    Button("Generate New Pairing…") { confirmingReset = true }
                }
                .padding(.top, 6)
            }
        }
        .padding(24)
        .frame(width: 340)
        .onAppear { server = model.pairing.server }
        .confirmationDialog("Generate a new pairing?", isPresented: $confirmingReset) {
            Button("Generate", role: .destructive) { model.repair(server: server) }
        } message: {
            Text("Your phone will stop syncing until you scan the new code.")
        }
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)) else { return nil }
        let rep = NSCIImageRep(ciImage: output)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}
