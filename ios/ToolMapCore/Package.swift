// swift-tools-version: 6.0
// ToolMapCore: the logic of ToolMap for iPhone, without any user interface (ADR-098, docs/plan-ios.md).
// Each file ports a file of the Android module `:core` (core/src/main/kotlin/fr/toolmap/core/), with the same tests.
import PackageDescription

let package = Package(
    name: "ToolMapCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ToolMapCore", targets: ["ToolMapCore"]),
    ],
    targets: [
        .target(name: "ToolMapCore"),
        .testTarget(name: "ToolMapCoreTests", dependencies: ["ToolMapCore"]),
    ]
)
