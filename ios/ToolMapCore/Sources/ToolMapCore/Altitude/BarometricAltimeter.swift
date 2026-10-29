// Port of core/src/main/kotlin/fr/toolmap/core/altitude/BarometricAltimeter.kt.

import Foundation

/// Where a reference altitude, used to calibrate the barometer, comes from.
public enum ReferenceSource: String, Codable, CaseIterable, Sendable {
    /// The elevation model at the position (Terrarium tiles): above mean sea level, a few tens of meters off at worst.
    case terrain = "TERRAIN"
    /// The GPS, known to be above mean sea level (Android 14+ computes it).
    case gpsMsl = "GPS_MSL"
    /// The GPS on a device that may report the height above the ellipsoid (up to ±100 m away from sea level).
    case gpsUnknownDatum = "GPS_UNKNOWN_DATUM"
    /// The user typed it.
    case manual = "MANUAL"
}

/// What happened to a reference altitude.
public enum ReferenceResult: String, Codable, CaseIterable, Sendable {
    case accepted = "ACCEPTED"
    case throttled = "THROTTLED"
    case rejectedOutlier = "REJECTED_OUTLIER"
    case noPressure = "NO_PRESSURE"
    case invalid = "INVALID"
}

/// The barometer's altitude and how far off it may be.
public struct BaroReading: Equatable, Hashable, Sendable {
    public let altitudeMeters: Double
    public let uncertaintyMeters: Double
    public let pressureHpa: Double
    public let seaLevelHpa: Double

    public init(altitudeMeters: Double, uncertaintyMeters: Double, pressureHpa: Double, seaLevelHpa: Double) {
        self.altitudeMeters = altitudeMeters
        self.uncertaintyMeters = uncertaintyMeters
        self.pressureHpa = pressureHpa
        self.seaLevelHpa = seaLevelHpa
    }
}

/// The tuning of `BarometricAltimeter`; the defaults suit walking in changing weather.
public struct BaroConfig: Equatable, Hashable, Sendable {
    /// How much the sea-level pressure may wander, in hPa over an hour (one standard deviation).
    public let driftHpaPerHour: Double
    /// Time constant of the pressure smoothing, in seconds.
    public let pressureTimeConstantSeconds: Double
    /// Two references from the same source closer in time than this are not independent: the later is dropped.
    public let minReferenceGapMillis: Int64
    /// A reference further than this many standard deviations from the estimate is an outlier.
    public let gateSigmas: Double
    /// After this many outliers in a row the estimate is the one that is wrong: start again from the reference.
    public let maxRejections: Int
    /// A pressure reading older than this is not current: no altitude from it.
    public let staleMillis: Int64
    /// What smoothed pressure noise adds to the uncertainty, in meters.
    public let noiseMeters: Double

    public init(
        driftHpaPerHour: Double = 1.0,
        pressureTimeConstantSeconds: Double = 4.0,
        minReferenceGapMillis: Int64 = 30000,
        gateSigmas: Double = 4.0,
        maxRejections: Int = 5,
        staleMillis: Int64 = 30000,
        noiseMeters: Double = 1.0
    ) {
        self.driftHpaPerHour = driftHpaPerHour
        self.pressureTimeConstantSeconds = pressureTimeConstantSeconds
        self.minReferenceGapMillis = minReferenceGapMillis
        self.gateSigmas = gateSigmas
        self.maxRejections = maxRejections
        self.staleMillis = staleMillis
        self.noiseMeters = noiseMeters
    }
}

/// A barometric altimeter (feature P9.5) that calibrates itself. The pressure gives the altitude only against the
/// sea-level pressure of the moment, which changes with the weather by a few hectopascals a day (a hPa is about 8 m),
/// so that pressure is estimated continuously from reference altitudes (the elevation model, the GPS) with a
/// one-dimensional Kalman filter: each reference pulls the estimate by its weight, and the estimate's uncertainty grows
/// with time at the drift rate of the weather. Short-term changes of altitude (ascent, descent) are exact to a meter or
/// two whatever the calibration; the calibration only fixes the absolute value.
///
/// Not thread-safe: the caller serialises the calls.
public final class BarometricAltimeter {
    private static let millis = 1000.0
    private static let secondsPerHour = 3600.0
    private static let minAltitude = -500.0
    private static let maxAltitude = 9000.0

    private let config: BaroConfig

    private var pressure: Double?
    private var pressureMillis: Int64 = 0

    private var seaLevel: Double?
    private var variance = 0.0
    private var filterMillis: Int64 = 0
    private var rejections = 0
    private var lastReference: [ReferenceSource: Int64] = [:]

    public init(config: BaroConfig = BaroConfig()) {
        self.config = config
    }

    /// Whether there is a sea-level pressure estimate to read altitudes against.
    public var calibrated: Bool { seaLevel != nil }

    /// The current sea-level pressure estimate in hPa, once calibrated.
    public var seaLevelHpa: Double? { seaLevel }

    /// A pressure reading in hPa at `timeMillis`; implausible values are dropped.
    public func onPressure(_ hpa: Double, _ timeMillis: Int64) {
        if !hpa.isFinite || hpa < Atmosphere.minPlausibleHpa || hpa > Atmosphere.maxPlausibleHpa { return }
        if let previous = pressure, timeMillis - pressureMillis <= config.staleMillis {
            let dt = Swift.max(Double(timeMillis - pressureMillis) / BarometricAltimeter.millis, 0.0)
            let alpha = 1.0 - exp(-dt / config.pressureTimeConstantSeconds)
            pressure = previous + alpha * (hpa - previous)
        } else {
            pressure = hpa
        }
        pressureMillis = timeMillis
    }

    /// Uses a known altitude to correct the sea-level pressure estimate.
    public func onReference(
        _ altitudeMeters: Double,
        _ sigmaMeters: Double,
        _ timeMillis: Int64,
        _ source: ReferenceSource
    ) -> ReferenceResult {
        guard let p = currentPressure(timeMillis) else { return .noPressure }
        if !altitudeMeters.isFinite
            || altitudeMeters < BarometricAltimeter.minAltitude
            || altitudeMeters > BarometricAltimeter.maxAltitude
            || !sigmaMeters.isFinite
            || sigmaMeters <= 0.0
        {
            return .invalid
        }
        if source != .manual, let last = lastReference[source], abs(timeMillis - last) < config.minReferenceGapMillis {
            return .throttled
        }
        let measured = Atmosphere.seaLevelHpa(p, altitudeMeters)
        let noise = sigmaMeters * Atmosphere.seaLevelHpaPerMeter(measured, altitudeMeters)
        let measurementVariance = noise * noise
        if let estimate = seaLevel, source != .manual {
            let predicted = predictedVariance(timeMillis)
            let innovation = measured - estimate
            if abs(innovation) > config.gateSigmas * (predicted + measurementVariance).squareRoot() {
                rejections += 1
                if rejections < config.maxRejections { return .rejectedOutlier }
                start(measured, measurementVariance, timeMillis)
            } else {
                let gain = predicted / (predicted + measurementVariance)
                seaLevel = estimate + gain * innovation
                variance = (1.0 - gain) * predicted
                filterMillis = timeMillis
                rejections = 0
            }
        } else {
            start(measured, measurementVariance, timeMillis)
        }
        lastReference[source] = timeMillis
        return .accepted
    }

    /// The altitude at `timeMillis`; nil before calibration or when the pressure reading is not current.
    public func reading(_ timeMillis: Int64) -> BaroReading? {
        guard let p = currentPressure(timeMillis), let estimate = seaLevel else { return nil }
        let altitude = Atmosphere.altitudeMeters(p, estimate)
        let hpaPerMeter = Atmosphere.seaLevelHpaPerMeter(estimate, altitude)
        let uncertainty = predictedVariance(timeMillis).squareRoot() / hpaPerMeter + config.noiseMeters
        return BaroReading(altitudeMeters: altitude, uncertaintyMeters: uncertainty, pressureHpa: p, seaLevelHpa: estimate)
    }

    /// Forgets the calibration (and the pressure), as after a long gap or a change of mode.
    public func reset() {
        pressure = nil
        seaLevel = nil
        variance = 0.0
        rejections = 0
        lastReference.removeAll()
    }

    private func start(_ seaLevelHpa: Double, _ varianceHpa2: Double, _ timeMillis: Int64) {
        seaLevel = seaLevelHpa
        variance = varianceHpa2
        filterMillis = timeMillis
        rejections = 0
    }

    private func currentPressure(_ timeMillis: Int64) -> Double? {
        guard let p = pressure else { return nil }
        return abs(timeMillis - pressureMillis) > config.staleMillis ? nil : p
    }

    private func predictedVariance(_ timeMillis: Int64) -> Double {
        let seconds = Swift.max(Double(timeMillis - filterMillis) / BarometricAltimeter.millis, 0.0)
        let perSecond = config.driftHpaPerHour * config.driftHpaPerHour / BarometricAltimeter.secondsPerHour
        return variance + perSecond * seconds
    }
}
