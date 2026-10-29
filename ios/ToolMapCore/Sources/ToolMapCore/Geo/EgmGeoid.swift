// Port of core/src/main/kotlin/fr/toolmap/core/geo/EgmGeoid.kt.

import Foundation

/// The EGM96 geoid (NGA's `WW15MGH.DAC`, public domain): the height of the geoid above the WGS84 ellipsoid, on a grid
/// of 15 arc-minutes. The GNSS gives heights above the ellipsoid; the maps, the barometer and the track files use
/// heights above the geoid (sea level), so the altitude of a fix is `ellipsoidal − undulation` (ADR-036).
///
/// The file holds 721 rows from 90° N to 90° S, of 1 440 values from 0° to 359.75° east, big-endian signed 16-bit
/// integers in centimetres. Values between the nodes are bilinear: the error is a few centimetres.
public final class EgmGeoid: Sendable {
    public static let rows = 721
    public static let cols = 1440
    public static let step = 0.25
    public static let fileBytes = rows * cols * 2

    private let centimetres: [Int16]

    private init(_ centimetres: [Int16]) {
        self.centimetres = centimetres
    }

    /// The undulation of the geoid at a position, in metres (negative where the geoid lies under the ellipsoid).
    public func undulation(_ lat: Double, _ lon: Double) -> Double {
        let rows = EgmGeoid.rows
        let cols = EgmGeoid.cols
        let step = EgmGeoid.step
        let row = EgmGeoid.clamp((90.0 - EgmGeoid.clamp(lat, -90.0, 90.0)) / step, 0.0, Double(rows - 1))
        let col = kotlinRem(kotlinRem(lon, 360.0) + 360.0, 360.0) / step
        let r0 = Swift.min(kotlinToInt(row), rows - 2)
        let c0 = kotlinToInt(col) % cols
        let fr = row - Double(r0)
        let fc = col - Double(kotlinToInt(col))
        let c1 = (c0 + 1) % cols
        let top = value(r0, c0) * (1 - fc) + value(r0, c1) * fc
        let bottom = value(r0 + 1, c0) * (1 - fc) + value(r0 + 1, c1) * fc
        return top * (1 - fr) + bottom * fr
    }

    /// The height above sea level of a height above the ellipsoid (the GNSS altitude) at a position.
    public func orthometric(_ ellipsoidalMeters: Double, _ lat: Double, _ lon: Double) -> Double {
        ellipsoidalMeters - undulation(lat, lon)
    }

    private func value(_ row: Int, _ col: Int) -> Double {
        Double(centimetres[row * EgmGeoid.cols + col]) / 100.0
    }

    /// Reads the grid. Throws when the bytes are not a whole EGM96 grid: the caller keeps its own altitude.
    public static func parse(_ bytes: Data) throws -> EgmGeoid {
        try require(bytes.count == fileBytes, "Geoid grid of \(bytes.count) bytes, expected \(fileBytes)")
        let count = rows * cols
        var values = [Int16](repeating: 0, count: count)
        bytes.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
            for i in 0 ..< count {
                let high = UInt16(raw[2 * i]) << 8
                let low = UInt16(raw[2 * i + 1])
                values[i] = Int16(bitPattern: high | low)
            }
        }
        return EgmGeoid(values)
    }

    /// Kotlin `coerceIn`: a NaN stays NaN.
    private static func clamp(_ value: Double, _ low: Double, _ high: Double) -> Double {
        if value < low { return low }
        if value > high { return high }
        return value
    }
}
