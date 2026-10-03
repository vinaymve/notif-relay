import AppKit
import Foundation
import Network
import Observation
import ServiceManagement

/// Keeps a streaming subscription open to the ntfy topic and turns each
/// decrypted message into a macOS notification.
@MainActor @Observable
final class AppModel {
    static let shared = AppModel()

    enum Status: Equatable {
        case connecting, connected, offline(String)

        var label: String {
            switch self {
            case .connecting: "Connecting…"
            case .connected: "Connected"
            case .offline(let reason): "Offline, retrying (\(reason))"
            }
        }
    }

    struct Received: Identifiable {
        let id: String
        let payload: Payload
    }

    private(set) var pairing: Pairing
    private(set) var status: Status = .connecting
    private(set) var recent: [Received] = []
    let isFirstLaunch: Bool
    var paused: Bool {
        didSet { UserDefaults.standard.set(paused, forKey: "paused") }
    }

    /// Messages older than this (e.g. cached while the Mac was asleep) go into
    /// the recent list but don't pop a banner.
    @ObservationIgnored private let bannerMaxAge: TimeInterval = 30 * 60
    @ObservationIgnored private var task: Task<Void, Never>?
    @ObservationIgnored private var seen = Set<String>()
    @ObservationIgnored private let pathMonitor = NWPathMonitor()
    @ObservationIgnored private let streamSession: URLSession = {
        let config = URLSessionConfiguration.default
        // ntfy sends a keepalive every 45s, so silence longer than this means the connection is dead.
        config.timeoutIntervalForRequest = 100
        config.timeoutIntervalForResource = 7 * 24 * 60 * 60
        return URLSession(configuration: config)
    }()

    @ObservationIgnored private var lastId: String? {
        get { UserDefaults.standard.string(forKey: "lastId") }
        set { UserDefaults.standard.set(newValue, forKey: "lastId") }
    }

    private init() {
        if let saved = Pairing.load() {
            pairing = saved
            isFirstLaunch = false
        } else {
            let fresh = Pairing.generate()
            fresh.save()
            pairing = fresh
            isFirstLaunch = true
        }
        paused = UserDefaults.standard.bool(forKey: "paused")
    }

    func start() {
        Notifier.requestAuthorization()
        NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.reconnect() }
        }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            Task { @MainActor in self?.reconnect() }
        }
        pathMonitor.start(queue: .global(qos: .utility))
        reconnect()
    }

    func reconnect() {
        task?.cancel()
        task = Task { await runLoop() }
    }

    func repair(server: String) {
        let trimmed = server.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        pairing = Pairing.generate(server: trimmed.isEmpty ? Pairing.defaultServer : trimmed)
        pairing.save()
        lastId = nil
        seen.removeAll()
        recent.removeAll()
        reconnect()
    }

    var launchAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            do {
                if newValue { try SMAppService.mainApp.register() } else { try SMAppService.mainApp.unregister() }
            } catch {
                NSLog("Launch at login failed: \(error)")
            }
        }
    }

    func sendTest() async {
        guard let key = pairing.symmetricKey else { return }
        let payload = Payload(
            app: "Notif Relay", pkg: "mac.test", title: "Test from your Mac",
            text: "If you see this, the relay round trip works.",
            ts: Int64(Date().timeIntervalSince1970 * 1000))
        do {
            var request = URLRequest(url: pairing.topicURL)
            request.httpMethod = "POST"
            request.setValue("no", forHTTPHeaderField: "X-Firebase")
            request.httpBody = Data(try Box.seal(JSONEncoder().encode(payload), key: key).utf8)
            _ = try await URLSession.shared.data(for: request)
        } catch {
            NSLog("Test send failed: \(error)")
        }
    }

    // MARK: - Subscription

    private struct NtfyEvent: Decodable {
        let id: String
        let time: TimeInterval
        let event: String
        let message: String?
    }

    private func runLoop() async {
        var delay: UInt64 = 1
        while !Task.isCancelled {
            var url = URLComponents(url: pairing.topicURL.appendingPathComponent("json"), resolvingAgainstBaseURL: false)!
            // On first run, pick up anything from the last few minutes; after that, resume where we left off.
            url.queryItems = [URLQueryItem(name: "since", value: lastId ?? "10m")]
            status = .connecting
            do {
                let (bytes, response) = try await streamSession.bytes(from: url.url!)
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                guard code == 200 else { throw URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey: "HTTP \(code)"]) }
                status = .connected
                delay = 1
                for try await line in bytes.lines {
                    handle(line)
                }
            } catch {
                if Task.isCancelled { return }
                status = .offline(error.localizedDescription)
            }
            try? await Task.sleep(nanoseconds: delay * 1_000_000_000)
            delay = min(delay * 2, 30)
        }
    }

    private func handle(_ line: String) {
        guard let event = try? JSONDecoder().decode(NtfyEvent.self, from: Data(line.utf8)),
              event.event == "message", let message = event.message
        else { return }
        lastId = event.id
        guard seen.insert(event.id).inserted,
              let key = pairing.symmetricKey,
              let plain = try? Box.open(message, key: key),
              let payload = try? JSONDecoder().decode(Payload.self, from: plain)
        else { return }

        recent.insert(Received(id: event.id, payload: payload), at: 0)
        if recent.count > 20 { recent.removeLast() }

        let age = Date().timeIntervalSince1970 - event.time
        if !paused && age < bannerMaxAge {
            Notifier.show(payload)
        }
    }
}
