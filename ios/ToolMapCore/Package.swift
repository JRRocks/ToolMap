// swift-tools-version: 6.0
// ToolMapCore: the logic of ToolMap for iPhone, without any user interface (ADR-100, docs/plan-ios.md).
// Each file ports a file of the Android module `:core` (core/src/main/kotlin/fr/toolmap/core/), with the same tests.
import PackageDescription

let package = Package(
    name: "ToolMapCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ToolMapCore", targets: ["ToolMapCore"]),
    ],
    targets: [
        .target(
            name: "ToolMapCore",
            resources: [
                // Copies of core/src/main/resources/ (the two apps share no file at build time, ADR-100).
                .copy("Resources/builtin-sources.aqx"),
                .copy("Resources/online-catalog.aqx"),
                // The waypoint, activity and token icons of Android, as path data (ios/tools/glyphs.py).
                .copy("Resources/glyphs.json"),
            ]
        ),
        .testTarget(name: "ToolMapCoreTests", dependencies: ["ToolMapCore"]),
    ]
)
