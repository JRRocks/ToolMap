// Port of core/src/main/kotlin/fr/toolmap/core/record/RecordingSession.kt.

import Foundation

/// What survives the death of the recording process: the stored track the points go to, and the time spent recording
/// so far. The points themselves are already in the database; this is enough to rebuild the live figures and to offer
/// the user to carry on. Its JSON is the one kotlinx.serialization writes on Android (`encodeDefaults`).
public struct RecordingSession: Hashable, Sendable, Codable {
    public let trackId: Int64
    public let startedAtMillis: Int64
    /// Recording time up to the last save, pauses excluded.
    public let activeMillis: Int64
    public let paused: Bool
    public let savedAtMillis: Int64
    /// `RecorderProfile` name, to carry on with the same settings after a restart.
    public let profile: String

    public init(
        trackId: Int64,
        startedAtMillis: Int64,
        activeMillis: Int64,
        paused: Bool,
        savedAtMillis: Int64,
        profile: String = RecorderProfile.walk.rawValue
    ) {
        self.trackId = trackId
        self.startedAtMillis = startedAtMillis
        self.activeMillis = activeMillis
        self.paused = paused
        self.savedAtMillis = savedAtMillis
        self.profile = profile
    }

    private enum CodingKeys: String, CodingKey {
        case trackId, startedAtMillis, activeMillis, paused, savedAtMillis, profile
    }

    /// As kotlinx.serialization without `coerceInputValues`: a missing `profile` takes its default, a null one fails.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        trackId = try c.decode(Int64.self, forKey: .trackId)
        startedAtMillis = try c.decode(Int64.self, forKey: .startedAtMillis)
        activeMillis = try c.decode(Int64.self, forKey: .activeMillis)
        paused = try c.decode(Bool.self, forKey: .paused)
        savedAtMillis = try c.decode(Int64.self, forKey: .savedAtMillis)
        if c.contains(.profile) {
            profile = try c.decode(String.self, forKey: .profile)
        } else {
            profile = RecorderProfile.walk.rawValue
        }
    }
}

public enum RecordingSessionCodec {
    public static func encode(_ session: RecordingSession) -> String {
        guard let data = try? JSONEncoder().encode(session) else { return "{}" }
        return String(decoding: data, as: UTF8.self)
    }

    public static func decodeOrNull(_ text: String) -> RecordingSession? {
        try? JSONDecoder().decode(RecordingSession.self, from: Data(text.utf8))
    }
}
