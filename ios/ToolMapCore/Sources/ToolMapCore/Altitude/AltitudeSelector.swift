// Port of core/src/main/kotlin/fr/toolmap/core/altitude/AltitudeSelector.kt.

public enum AltitudeSource: String, Codable, CaseIterable, Sendable {
    case barometer = "BAROMETER"
    case gps = "GPS"
}

/// The altitude to show and record, with where it comes from and how far off it may be (nil when unknown).
public struct Altitude: Equatable, Hashable, Sendable {
    public let meters: Double
    public let source: AltitudeSource
    public let uncertaintyMeters: Double?

    public init(meters: Double, source: AltitudeSource, uncertaintyMeters: Double?) {
        self.meters = meters
        self.source = source
        self.uncertaintyMeters = uncertaintyMeters
    }
}

/// Which altitude to give: the barometer's, the GPS's, or whichever is better.
public enum AltitudeSelector {
    /// In automatic mode a barometer less sure than this gives way to the GPS.
    public static let maxAutoUncertaintyMeters = 30.0

    public static func choose(
        _ mode: AltitudeMode,
        _ gpsMeters: Double?,
        _ gpsUncertaintyMeters: Double?,
        _ baro: BaroReading?
    ) -> Altitude? {
        let fromBaro = baro.map {
            Altitude(meters: $0.altitudeMeters, source: .barometer, uncertaintyMeters: $0.uncertaintyMeters)
        }
        let fromGps = gpsMeters.map { Altitude(meters: $0, source: .gps, uncertaintyMeters: gpsUncertaintyMeters) }
        switch mode {
        case .gps:
            return fromGps
        case .barometer:
            return fromBaro
        case .auto:
            guard let barometer = baro, let baroAltitude = fromBaro else { return fromGps }
            guard let gpsAltitude = fromGps else { return baroAltitude }
            return barometer.uncertaintyMeters <= maxAutoUncertaintyMeters ? baroAltitude : gpsAltitude
        }
    }
}
