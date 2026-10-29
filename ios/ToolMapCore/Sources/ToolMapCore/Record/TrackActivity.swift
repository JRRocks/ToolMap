// Port of core/src/main/kotlin/fr/toolmap/core/record/TrackActivity.kt (generated on Android by tools/icons/generate.mjs
// from tools/icons/catalog.txt: a change of the catalog is to be reported here by hand).

import Foundation

/// The activity asked when a recording or a segment starts (« Ask for activity »). It names the track, so that the list
/// of tracks says what they are. The list and the colours are the ones of AlpineQuest's activity dialog. The raw value
/// is the Kotlin name.
public enum TrackActivity: String, CaseIterable, Hashable, Sendable, Codable {
    case walk = "WALK"
    case hike = "HIKE"
    case run = "RUN"
    case trailRun = "TRAIL_RUN"
    case bicycle = "BICYCLE"
    case scooter = "SCOOTER"
    case skate = "SKATE"
    case roller = "ROLLER"
    case drive = "DRIVE"
    case motorcycle = "MOTORCYCLE"
    case offroad = "OFFROAD"
    case rickshaw = "RICKSHAW"
    case autobus = "AUTOBUS"
    case train = "TRAIN"
    case tram = "TRAM"
    case cableCar = "CABLE_CAR"
    case ski = "SKI"
    case nordic = "NORDIC"
    case skimo = "SKIMO"
    case snowshoe = "SNOWSHOE"
    case atv = "ATV"
    case snowmobile = "SNOWMOBILE"
    case sleigh = "SLEIGH"
    case horse = "HORSE"
    case boat = "BOAT"
    case sail = "SAIL"
    case canoe = "CANOE"
    case water = "WATER"
    case plane = "PLANE"
    case helicopter = "HELICOPTER"
    case paragliding = "PARAGLIDING"
    case air = "AIR"
    case other = "OTHER"

    /// ARGB in a signed 32-bit integer, like Android's.
    public var colorArgb: Int32 {
        switch self {
        case .walk: Int32(bitPattern: 0xFF43_C14A)
        case .hike: Int32(bitPattern: 0xFF2E_9E5B)
        case .run: Int32(bitPattern: 0xFFE8_730C)
        case .trailRun: Int32(bitPattern: 0xFFE3_9A0B)
        case .bicycle: Int32(bitPattern: 0xFF14_B8C4)
        case .scooter: Int32(bitPattern: 0xFF2E_9AA3)
        case .skate: Int32(bitPattern: 0xFF4F_7F7A)
        case .roller: Int32(bitPattern: 0xFF5A_6B6B)
        case .drive: Int32(bitPattern: 0xFFC2_1DC2)
        case .motorcycle: Int32(bitPattern: 0xFF9C_27B0)
        case .offroad: Int32(bitPattern: 0xFF8B_2D1B)
        case .rickshaw: Int32(bitPattern: 0xFF4A_2C5A)
        case .autobus: Int32(bitPattern: 0xFF8A_3B3B)
        case .train: Int32(bitPattern: 0xFFD8_1B3C)
        case .tram: Int32(bitPattern: 0xFFC9_4F5F)
        case .cableCar: Int32(bitPattern: 0xFFE5_7373)
        case .ski: Int32(bitPattern: 0xFF1A_237E)
        case .nordic: Int32(bitPattern: 0xFF1E_88E5)
        case .skimo: Int32(bitPattern: 0xFF29_B6F6)
        case .snowshoe: Int32(bitPattern: 0xFF4D_B6AC)
        case .atv: Int32(bitPattern: 0xFFB5_651D)
        case .snowmobile: Int32(bitPattern: 0xFF5F_7F8F)
        case .sleigh: Int32(bitPattern: 0xFF5B_7F2A)
        case .horse: Int32(bitPattern: 0xFF7B_7B00)
        case .boat: Int32(bitPattern: 0xFF0D_1F8C)
        case .sail: Int32(bitPattern: 0xFF1E_88E5)
        case .canoe: Int32(bitPattern: 0xFF64_B5F6)
        case .water: Int32(bitPattern: 0xFF90_CAF9)
        case .plane: Int32(bitPattern: 0xFFFB_C02D)
        case .helicopter: Int32(bitPattern: 0xFFF9_D54B)
        case .paragliding: Int32(bitPattern: 0xFFC8_A84B)
        case .air: Int32(bitPattern: 0xFF9E_9E7A)
        case .other: Int32(bitPattern: 0xFFF5_A623)
        }
    }
}
