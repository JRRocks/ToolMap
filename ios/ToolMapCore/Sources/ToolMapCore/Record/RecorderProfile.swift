// Port of core/src/main/kotlin/fr/toolmap/core/record/RecorderProfile.kt.

import Foundation

/// Ready-made recording settings, so that nobody has to think in milliseconds and meters before a hike. The raw value is
/// the Kotlin name, kept in the recording session file.
public enum RecorderProfile: String, CaseIterable, Hashable, Sendable, Codable {
    /// Hiking: a point every 2 s and 5 m.
    case walk = "WALK"
    /// Cycling: fewer points on straight roads.
    case bike = "BIKE"
    /// Every second, 2 m: dense tracks, more battery and storage.
    case precise = "PRECISE"
    /// A point every 10 s and 25 m: days of recording on one charge.
    case saver = "SAVER"

    public var settings: RecorderCoreSettings {
        switch self {
        case .walk: RecorderCoreSettings(minIntervalMillis: 2_000, minDistanceMeters: 5.0)
        case .bike: RecorderCoreSettings(minIntervalMillis: 2_000, minDistanceMeters: 10.0)
        case .precise: RecorderCoreSettings(minIntervalMillis: 1_000, minDistanceMeters: 2.0)
        case .saver: RecorderCoreSettings(minIntervalMillis: 10_000, minDistanceMeters: 25.0, maxAccuracyMeters: 80.0)
        }
    }

    public static func fromName(_ name: String?) -> RecorderProfile {
        guard let wanted = name else { return .walk }
        return RecorderProfile(rawValue: wanted) ?? .walk
    }
}
