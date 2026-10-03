// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "NotifRelay",
    platforms: [.macOS(.v15)],
    targets: [
        .executableTarget(name: "NotifRelay", path: "Sources/NotifRelay", swiftSettings: [.swiftLanguageMode(.v5)])
    ]
)
