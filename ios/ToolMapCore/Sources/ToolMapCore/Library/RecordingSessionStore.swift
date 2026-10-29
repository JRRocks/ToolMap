// Port of data/src/main/kotlin/fr/toolmap/data/tracks/RecordingSessionStore.kt.

import Foundation

/// The one file telling that a recording is under way (or was, when the process died). Written atomically so that a
/// kill in the middle of a save leaves the previous version; a damaged file reads as "no recording".
public final class RecordingSessionStore: Sendable {
    private let file: URL

    public init(_ file: URL) {
        self.file = file
    }

    public func read() -> RecordingSession? {
        guard let data = try? Data(contentsOf: file) else { return nil }
        return RecordingSessionCodec.decodeOrNull(String(decoding: data, as: UTF8.self))
    }

    /// Writes a temporary file next to it, then renames it over the previous one.
    public func write(_ session: RecordingSession) throws {
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(RecordingSessionCodec.encode(session).utf8).write(to: file, options: .atomic)
    }

    public func clear() {
        try? FileManager.default.removeItem(at: file)
    }
}
