import Foundation

/// The repository's `fixtures/` folder, read in place (the same files as the Kotlin tests of `:core`). Found from
/// this source file, so that it works for `swift test` on macOS and for `xcodebuild test` in the iOS simulator.
enum Fixtures {
    static let directory: URL = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // ToolMapCoreTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // ToolMapCore
        .deletingLastPathComponent() // ios
        .deletingLastPathComponent() // repository root
        .appendingPathComponent("fixtures")

    /// The file as UTF-8 text; a byte order mark is kept, as the JVM decoder keeps it.
    static func text(_ path: String) throws -> String {
        let data = try Data(contentsOf: directory.appendingPathComponent(path))
        return String(decoding: data, as: UTF8.self)
    }
}
