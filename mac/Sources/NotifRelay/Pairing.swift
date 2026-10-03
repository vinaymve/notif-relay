import Foundation
import CryptoKit

/// Shared secret between the Mac and the phone: which ntfy topic to use and the AES key.
/// Encoded into the QR code as `notifrelay://pair?v=1&s=<server>&t=<topic>&k=<key>`.
struct Pairing: Codable, Equatable {
    var server: String
    var topic: String
    var key: String  // base64url, 32 bytes

    static let defaultServer = "https://ntfy.sh"
    private static let defaultsKey = "pairing"

    static func generate(server: String = defaultServer) -> Pairing {
        let key = SymmetricKey(size: .bits256).withUnsafeBytes { Data($0) }
        return Pairing(server: server, topic: "nr-" + randomBytes(18).base64URL, key: key.base64URL)
    }

    static func load() -> Pairing? {
        guard let data = UserDefaults.standard.data(forKey: defaultsKey) else { return nil }
        return try? JSONDecoder().decode(Pairing.self, from: data)
    }

    func save() {
        UserDefaults.standard.set(try? JSONEncoder().encode(self), forKey: Self.defaultsKey)
    }

    var symmetricKey: SymmetricKey? {
        Data(base64URL: key).map(SymmetricKey.init(data:))
    }

    var topicURL: URL {
        URL(string: server)!.appendingPathComponent(topic)
    }

    var uri: String {
        var c = URLComponents()
        c.scheme = "notifrelay"
        c.host = "pair"
        c.queryItems = [
            URLQueryItem(name: "v", value: "1"),
            URLQueryItem(name: "s", value: server),
            URLQueryItem(name: "t", value: topic),
            URLQueryItem(name: "k", value: key),
        ]
        return c.string!
    }
}

/// What the phone sends, before encryption. Must match `Relay.kt` on Android.
struct Payload: Codable {
    var v: Int = 1
    var app: String
    var pkg: String
    var title: String
    var text: String
    var ts: Int64
}

/// AES-256-GCM. Wire format is base64(nonce[12] || ciphertext || tag[16]).
enum Box {
    struct InvalidInput: Error {}

    static func seal(_ data: Data, key: SymmetricKey) throws -> String {
        guard let combined = try AES.GCM.seal(data, using: key).combined else { throw InvalidInput() }
        return combined.base64EncodedString()
    }

    static func open(_ base64: String, key: SymmetricKey) throws -> Data {
        guard let data = Data(base64Encoded: base64) else { throw InvalidInput() }
        return try AES.GCM.open(AES.GCM.SealedBox(combined: data), using: key)
    }
}

private func randomBytes(_ count: Int) -> Data {
    var bytes = [UInt8](repeating: 0, count: count)
    _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
    return Data(bytes)
}

extension Data {
    var base64URL: String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    init?(base64URL: String) {
        var s = base64URL.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        s += String(repeating: "=", count: (4 - s.count % 4) % 4)
        self.init(base64Encoded: s)
    }
}

extension SymmetricKey {
    var base64URL: String { withUnsafeBytes { Data($0) }.base64URL }
}
