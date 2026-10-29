// Port of core/src/main/kotlin/fr/toolmap/core/geo/Mgrs.kt.

import Foundation

/// A Military Grid Reference System reference: grid zone, 100 km square and the position inside it.
public struct MgrsReference: Equatable, Sendable {
    public let zone: Int
    public let band: Character
    public let columnLetter: Character
    public let rowLetter: Character
    /// Meters east of the square's west edge, 0 until 100 000.
    public let easting: Int
    /// Meters north of the square's south edge, 0 until 100 000.
    public let northing: Int

    private static let powers = [1, 10, 100, 1000, 10000, 100_000]

    /// `31U DQ 52482 11717` (digits truncated, never rounded, like a printed map). Throws for digits outside 0...5.
    public func format(digits: Int = 5, separators: Bool = true) throws -> String {
        try require((0 ... 5).contains(digits), "digits must be 0..5")
        let divisor = MgrsReference.powers[5 - digits]
        func padded(_ value: Int) -> String {
            let text = String(value / divisor)
            return String(repeating: "0", count: Swift.max(digits - text.count, 0)) + text
        }
        let e = digits == 0 ? "" : padded(easting)
        let n = digits == 0 ? "" : padded(northing)
        let gzd = "\(zone)\(band)"
        let square = "\(columnLetter)\(rowLetter)"
        if separators {
            return [gzd, square, e, n].filter { !$0.isEmpty }.joined(separator: " ")
        }
        return gzd + square + e + n
    }
}

/// MGRS on top of `Utm` (WGS84, between 80 S and 84 N).
public enum Mgrs {
    private static let columnSets = Array("ABCDEFGH" + "JKLMNPQR" + "STUVWXYZ")
    private static let rows = Array("ABCDEFGHJKLMNPQRSTUV")
    private static let bands = Array("CDEFGHJKLMNPQRSTUVWX")
    private static let powers = [1, 10, 100, 1000, 10000, 100_000]
    private static let northFirstBand = 10
    private static let rowCycle = 2_000_000.0
    private static let bandToleranceDegrees = 0.5
    private static let rowOffsetEven = 5

    /// The reference of a position; nil outside the UTM latitudes.
    public static func of(_ lat: Double, _ lon: Double) -> MgrsReference? {
        guard let band = Utm.bandLetter(lat) else { return nil }
        return of(Utm.toUtm(lat, lon), band: band)
    }

    public static func of(_ utm: UtmCoordinate, band: Character) -> MgrsReference {
        let e100 = kotlinToInt(floor(utm.easting / 100_000.0))
        let n100 = kotlinToInt(floor(utm.northing / 100_000.0))
        let set = (utm.zone - 1) % 3
        let column = columnSets[set * 8 + Swift.min(Swift.max(e100 - 1, 0), 7)]
        let rowOffset = utm.zone % 2 == 0 ? rowOffsetEven : 0
        let row = rows[floorMod(n100 + rowOffset, rows.count)]
        return MgrsReference(
            zone: utm.zone,
            band: band,
            columnLetter: column,
            rowLetter: row,
            easting: kotlinToInt(floor(utm.easting - Double(e100) * 100_000.0)),
            northing: kotlinToInt(floor(utm.northing - Double(n100) * 100_000.0))
        )
    }

    /// The centre of the square a reference designates, at the precision it was written with (`digits` per axis,
    /// 0...5). nil when the letters cannot go together.
    public static func toLonLat(
        zone: Int,
        band: Character,
        columnLetter: Character,
        rowLetter: Character,
        easting: Int,
        northing: Int,
        digits: Int = 5
    ) -> LonLat? {
        guard (1 ... 60).contains(zone), (0 ... 5).contains(digits),
              let bandIndex = bands.firstIndex(of: band),
              let columnIndex = columnSets.firstIndex(of: columnLetter),
              let rowIndex = rows.firstIndex(of: rowLetter)
        else { return nil }
        // 32X, 34X and 36X do not exist: Svalbard's zones are 9° and 12° wide.
        if GridZones.get(zone, band) == nil { return nil }
        let set = (zone - 1) % 3
        if columnIndex / 8 != set { return nil }
        let e100 = columnIndex % 8 + 1
        let rowOffset = zone % 2 == 0 ? rowOffsetEven : 0
        let n100InCycle = floorMod(rowIndex - rowOffset, rows.count)
        let cell = Double(powers[5 - digits])
        let north = bandIndex >= northFirstBand
        // The band tells which of the 2 000 km repetitions of the row letters is meant: the lowest one that is not
        // south of the band (the band's southern edge is lowest on the central meridian).
        let southLat = -80.0 + 8.0 * Double(bandIndex)
        let minNorthing = Utm.toUtm(southLat, Utm.centralMeridian(zone), zone: zone).northing
        var base = Double(n100InCycle) * 100_000.0
        let first = floor(minNorthing / 100_000.0) * 100_000.0
        while base < first {
            base += rowCycle
        }
        let utm = UtmCoordinate(
            zone: zone,
            north: north,
            easting: Double(e100) * 100_000.0 + Double(easting) + cell / 2,
            northing: base + Double(northing) + cell / 2
        )
        let position = Utm.toLonLat(utm)
        let topLat = band == "X" ? 84.0 : southLat + 8.0
        guard position.lat >= southLat - bandToleranceDegrees, position.lat <= topLat + bandToleranceDegrees else {
            return nil
        }
        return position
    }

    private static func floorMod(_ a: Int, _ b: Int) -> Int {
        ((a % b) + b) % b
    }
}
