// Port of core/src/test/kotlin/fr/toolmap/core/geo/MgrsJunctionsTest.kt: positions at grid zone junctions checked
// against GeoConvert (GeographicLib), an implementation independent of ToolMap (`fixtures/mgrs/geoconvert-junctions.txt`,
// P24.1): the 6° E junction of the user's screenshots, Norway, Svalbard, the equator, band edges, 80 S, 84 N and the
// antimeridian.

import Foundation
import Testing
@testable import ToolMapCore

private struct JunctionRef {
    let lat: Double
    let lon: Double
    /// GeoConvert writes zones 1 to 9 with a leading zero (`01N`); ToolMap writes `1N`.
    let mgrs: String
    let zone: Int
    let north: Bool
    let easting: Double
    let northing: Double
}

struct MgrsJunctionsTests {
    private static let metersPerDegree = 111_320.0

    private let refs: [JunctionRef]

    init() throws {
        let text = try Fixtures.text("mgrs/geoconvert-junctions.txt")
        var refs: [JunctionRef] = []
        for raw in text.split(omittingEmptySubsequences: false, whereSeparator: { $0 == "\n" || $0 == "\r\n" }) {
            let line = String(raw)
            if kotlinTrim(line).isEmpty || line.hasPrefix("#") { continue }
            let parts = kotlinSplit(line, "|").map(kotlinTrim)
            let position = kotlinSplit(parts[0], " ")
            let utm = kotlinSplit(parts[2], " ")
            let zone = utm[0]
            var mgrs = Substring(parts[1])
            while mgrs.first == "0" {
                mgrs = mgrs.dropFirst()
            }
            let lat = try #require(kotlinDouble(position[0]))
            let lon = try #require(kotlinDouble(position[1]))
            let zoneNumber = try #require(kotlinInt(String(zone.dropLast())))
            let easting = try #require(kotlinDouble(utm[1]))
            let northing = try #require(kotlinDouble(utm[2]))
            refs.append(
                JunctionRef(
                    lat: lat,
                    lon: lon,
                    mgrs: String(mgrs),
                    zone: zoneNumber,
                    north: zone.last == "n",
                    easting: easting,
                    northing: northing
                )
            )
        }
        self.refs = refs
    }

    @Test("the fixture covers the junctions")
    func coverage() {
        #expect(refs.count >= 40, "only \(refs.count) references")
        let zones = Set(refs.map { String($0.mgrs.prefix(3)) })
        for z in ["31T", "32T", "31V", "32V", "33V", "31X", "33X", "35X", "37X", "38X", "32N", "32M"] {
            #expect(zones.contains(z), "no reference in \(z)")
        }
    }

    @Test("MGRS references match GeographicLib, Norway and Svalbard included")
    func mgrsMatches() throws {
        for r in refs {
            let reference = try #require(Mgrs.of(r.lat, r.lon), "at \(r.lat),\(r.lon)")
            #expect(try reference.format(digits: 5, separators: false) == r.mgrs, "at \(r.lat),\(r.lon)")
        }
    }

    @Test("UTM zone, hemisphere and coordinates match GeographicLib to a meter")
    func utmMatches() {
        for r in refs {
            let u = Utm.toUtm(r.lat, r.lon)
            #expect(u.zone == r.zone, "zone at \(r.lat),\(r.lon)")
            #expect(u.north == r.north, "hemisphere at \(r.lat),\(r.lon)")
            // GeoConvert rounds the UTM figures to the meter.
            #expect(abs(u.easting - r.easting) <= 0.51, "easting at \(r.lat),\(r.lon)")
            #expect(abs(u.northing - r.northing) <= 0.51, "northing at \(r.lat),\(r.lon)")
        }
    }

    @Test("the grid zone of each reference is the one GeographicLib names")
    func gridZones() throws {
        for r in refs {
            let zone = try #require(GridZones.of(r.lat, r.lon), "at \(r.lat),\(r.lon)")
            let digits = String(r.mgrs.prefix(while: { $0.isNumber }))
            let band = try #require(r.mgrs.drop(while: { $0.isNumber }).first)
            #expect(zone.name == digits + String(band))
            #expect(zone.contains(r.lat, r.lon), "\(zone.name) does not contain \(r.lat),\(r.lon)")
        }
    }

    @Test("each reference reads back to its position")
    func readsBack() throws {
        let pattern = try NSRegularExpression(pattern: "^([0-9]{1,2})([C-X])([A-Z])([A-Z])([0-9]{5})([0-9]{5})$")
        for r in refs {
            let ns = r.mgrs as NSString
            let match = try #require(pattern.firstMatch(in: r.mgrs, options: [], range: NSRange(location: 0, length: ns.length)), "\(r.mgrs)")
            let m = (0 ..< match.numberOfRanges).map { ns.substring(with: match.range(at: $0)) }
            let zone = try #require(Int(m[1]))
            let band = try #require(m[2].first)
            let column = try #require(m[3].first)
            let row = try #require(m[4].first)
            let easting = try #require(Int(m[5]))
            let northing = try #require(Int(m[6]))
            let back = try #require(
                Mgrs.toLonLat(zone: zone, band: band, columnLetter: column, rowLetter: row, easting: easting, northing: northing),
                "\(r.mgrs)"
            )
            // The reference names a 1 m square (truncated): its centre is within 1.5 m of the position.
            let dNorth = (back.lat - r.lat) * MgrsJunctionsTests.metersPerDegree
            let dEast = Utm.normalizeLon(back.lon - r.lon) * MgrsJunctionsTests.metersPerDegree * cos(Angle.radians(r.lat))
            #expect(hypot(dNorth, dEast) < 1.5, "\(r.mgrs) reads back \(hypot(dNorth, dEast)) m away")
        }
    }

    @Test("Svalbard has no 32X, 34X or 36X")
    func svalbard() {
        #expect(Mgrs.toLonLat(zone: 32, band: "X", columnLetter: "N", rowLetter: "G", easting: 50_000, northing: 50_000) == nil)
        #expect(Mgrs.toLonLat(zone: 34, band: "X", columnLetter: "E", rowLetter: "G", easting: 50_000, northing: 50_000) == nil)
        #expect(Mgrs.toLonLat(zone: 36, band: "X", columnLetter: "W", rowLetter: "G", easting: 50_000, northing: 50_000) == nil)
        #expect(Utm.zoneOf(lat: 78.0, lon: 8.99) == 31)
        #expect(Utm.zoneOf(lat: 78.0, lon: 9.01) == 33)
        #expect(Utm.zoneOf(lat: 78.0, lon: 41.99) == 37)
        #expect(Utm.zoneOf(lat: 78.0, lon: 42.01) == 38)
        // Below 72 N the zones are regular again.
        #expect(Utm.zoneOf(lat: 71.99, lon: 10.0) == 32)
    }

    @Test("32V reaches 3 E between 56 and 64 N only")
    func norway() {
        #expect(Utm.zoneOf(lat: 60.0, lon: 2.999) == 31)
        #expect(Utm.zoneOf(lat: 60.0, lon: 3.001) == 32)
        #expect(Utm.zoneOf(lat: 55.999, lon: 4.0) == 31)
        #expect(Utm.zoneOf(lat: 56.001, lon: 4.0) == 32)
        #expect(Utm.zoneOf(lat: 64.001, lon: 4.0) == 31)
        // The regular zone of a longitude alone does not know the exceptions.
        #expect(Utm.zoneOf(4.0) == 31)
    }

    @Test("a grid keeps one hemisphere across the equator")
    func equator() throws {
        let south = Utm.toUtm(-0.0001, 6.5)
        #expect(south.north == false)
        // In the northern hemisphere's grid, the same place is just below 0.
        let carried = Utm.toUtm(-0.0001, 6.5, north: true)
        #expect(abs(carried.northing - (south.northing - 10_000_000.0)) <= 1e-6)
        #expect(carried.northing < 0.0)
        // And its 100 km square letters do not change: 10 000 km is five cycles of the row letters.
        let southText = try Mgrs.of(south, band: "M").format()
        let carriedText = try Mgrs.of(carried, band: "M").format()
        #expect(southText == carriedText)
    }

    @Test("convergence is the angle the projection gives to the meridian")
    func convergence() {
        // At the centres of the user's screenshots on both sides of 6 E: about 2°08' east in 31T, west in 32T.
        let places: [(Double, Double)] = [(45.42711, 5.99937), (45.42742, 6.00573), (60.0, 3.001), (78.0, 41.99), (-33.86, 151.21)]
        for (lat, lon) in places {
            let gamma = Utm.convergenceDegrees(lat: lat, lon: lon)
            // Grid north as the projection draws it: the direction of a short step due north, measured in the grid.
            let a = Utm.toUtm(lat, lon)
            let b = Utm.toUtm(lat + 1e-5, lon, zone: a.zone, north: a.north)
            let fromProjection = -Angle.degrees(atan2(b.easting - a.easting, b.northing - a.northing))
            #expect(abs(gamma - fromProjection) <= 1e-3, "at \(lat),\(lon)")
        }
        #expect(abs(Utm.convergenceDegrees(lat: 45.42711, lon: 5.99937) - 2.136) <= 0.01)
        #expect(abs(Utm.convergenceDegrees(lat: 45.42742, lon: 6.00573) - -2.132) <= 0.01)
        #expect(abs(Utm.convergenceDegrees(lat: 45.0, lon: 3.0)) < 1e-12)
    }
}
