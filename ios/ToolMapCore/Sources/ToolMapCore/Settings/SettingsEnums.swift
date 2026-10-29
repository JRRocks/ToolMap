// The enums of core/src/main/kotlin/fr/toolmap/core/settings/AppSettings.kt. Raw values are the Kotlin names, so the
// settings JSON reads the same on both apps.

public enum UnitSystem: String, Codable, CaseIterable, Sendable {
    case metric = "METRIC"
    case imperial = "IMPERIAL"
}

public enum ThemeMode: String, Codable, CaseIterable, Sendable {
    case system = "SYSTEM"
    case light = "LIGHT"
    case dark = "DARK"
}

/// Angles as a compass reads them: degrees, NATO mils (6 400 per turn) or gradians (400 per turn).
public enum BearingUnit: String, Codable, CaseIterable, Sendable {
    case degrees = "DEGREES"
    case mils = "MILS"
    case gradians = "GRADIANS"
}

/// Slopes as a percentage of the horizontal distance (`+12 %`), or as an angle.
public enum SlopeUnit: String, Codable, CaseIterable, Sendable {
    case percent = "PERCENT"
    case degrees = "DEGREES"
}

public enum PressureUnit: String, Codable, CaseIterable, Sendable {
    case hpa = "HPA"
    case mmhg = "MMHG"
    case inhg = "INHG"
}

/// The north the bearings and the compass are measured from.
public enum NorthReference: String, Codable, CaseIterable, Sendable {
    case trueNorth = "TRUE"
    case magnetic = "MAGNETIC"
    case grid = "GRID"
}

/// Where the offline maps are kept (not a saved setting on Android, ADR-042).
public enum StorageLocation: String, Codable, CaseIterable, Sendable {
    case `internal` = "INTERNAL"
    case external = "EXTERNAL"
}

public enum InfoBoxSize: String, Codable, CaseIterable, Sendable {
    case small = "SMALL"
    case normal = "NORMAL"
    case large = "LARGE"

    public var scale: Float {
        switch self {
        case .small: 0.85
        case .normal: 1
        case .large: 1.25
        }
    }
}

public enum InfoBoxBackground: String, Codable, CaseIterable, Sendable {
    case transparent = "TRANSPARENT"
    /// The chips of the map (P29.1, « Pastilles »): the map barely shows through, the text reads on any map.
    case translucent = "TRANSLUCENT"
    case opaque = "OPAQUE"

    public var alpha: Float {
        switch self {
        case .transparent: 0
        case .translucent: 0.94
        case .opaque: 1
        }
    }
}

public enum GridDensity: String, Codable, CaseIterable, Sendable {
    case fine = "FINE"
    case normal = "NORMAL"
    case coarse = "COARSE"

    public var spacing: Double {
        switch self {
        case .fine: 0.5
        case .normal: 1.0
        case .coarse: 2.0
        }
    }
}

public enum GridStyle: String, Codable, CaseIterable, Sendable {
    case solid = "SOLID"
    case dashed = "DASHED"
}

/// How many zoom levels the + and − buttons change at a tap.
public enum ZoomStep: String, Codable, CaseIterable, Sendable {
    case half = "HALF"
    case one = "ONE"
    case two = "TWO"

    public var levels: Double {
        switch self {
        case .half: 0.5
        case .one: 1.0
        case .two: 2.0
        }
    }
}

/// What a long press on the map does.
public enum LongPressAction: String, Codable, CaseIterable, Sendable {
    case none = "NONE"
    case waypoint = "WAYPOINT"
    case measure = "MEASURE"
    case target = "TARGET"
}

/// How the direction of travel (from the GNSS course) is drawn on the position marker.
public enum CourseDisplay: String, Codable, CaseIterable, Sendable {
    case none = "NONE"
    case arrow = "ARROW"
}

/// How the compass heading is shown: as the cone on the position marker, as a number, or both.
public enum HeadingDisplay: String, Codable, CaseIterable, Sendable {
    case none = "NONE"
    case number = "NUMBER"
    case arrow = "ARROW"
    case both = "BOTH"
}

/// Size of the placemarks: paths, waypoints and icons.
public enum PlacemarkSize: String, Codable, CaseIterable, Sendable {
    case small = "SMALL"
    case normal = "NORMAL"
    case large = "LARGE"

    public var factor: Double {
        switch self {
        case .small: 0.75
        case .normal: 1.0
        case .large: 1.5
        }
    }
}

/// Where the altitude of a recorded or displayed position comes from.
public enum AltitudeMode: String, Codable, CaseIterable, Sendable {
    /// The barometer once it is calibrated, the GPS otherwise.
    case auto = "AUTO"
    case gps = "GPS"
    case barometer = "BAROMETER"

    /// Whether the barometer gives the altitude: every mode but GPS.
    public var barometerOn: Bool { self != .gps }

    /// The mode once the « Baromètre » tick is set or cleared.
    public func withBarometer(_ on: Bool) -> AltitudeMode {
        if !on { return .gps }
        return self == .gps ? .auto : self
    }
}
