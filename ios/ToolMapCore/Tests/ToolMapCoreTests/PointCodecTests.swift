// Port of core/src/test/kotlin/fr/toolmap/core/track/PointCodecTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

/// `java.util.Random`, so that the random walk is the very one of the Kotlin test.
private final class JavaRandom {
    private var seed: UInt64

    init(_ seed: Int64) {
        self.seed = (UInt64(bitPattern: seed) ^ 0x5_DEEC_E66D) & ((1 << 48) - 1)
    }

    private func next(_ bits: Int) -> Int64 {
        seed = (seed &* 0x5_DEEC_E66D &+ 0xB) & ((1 << 48) - 1)
        return Int64(Int32(truncatingIfNeeded: seed >> UInt64(48 - bits)))
    }

    func nextDouble() -> Double {
        Double((next(26) << 27) + next(27)) * 0x1.0p-53
    }
}

struct PointCodecTests {
    @Test("values with up to nine decimals, six decimals of elevation and milliseconds come back exactly")
    func exactValues() throws {
        let points = [
            TrackPoint(lat: 45.123456789, lon: 6.987654321, ele: 1234.567891, timeMillis: 1_700_000_000_123),
            TrackPoint(lat: -33.5, lon: -70.25, ele: nil, timeMillis: nil),
            TrackPoint(lat: 0.0, lon: 0.0, ele: -12.5, timeMillis: -2_147_483_648_000),
            TrackPoint(lat: 89.999999999, lon: -179.999999999, ele: 8848.0, timeMillis: 4_102_444_800_000),
            TrackPoint(
                lat: 1.0,
                lon: 2.0,
                ele: 3.0,
                timeMillis: 4,
                extras: [RawXml("<extensions><a:b xmlns:a=\"u\">é&amp;</a:b></extensions>")]
            ),
        ]
        #expect(try PointCodec.decode(PointCodec.encode(points)) == points)
    }

    @Test("empty list and single point")
    func emptyAndSingle() throws {
        #expect(try PointCodec.decode(PointCodec.encode([])).isEmpty)
        let one = [TrackPoint(lat: 1.5, lon: 2.5)]
        #expect(try PointCodec.decode(PointCodec.encode(one)) == one)
    }

    @Test("finer data is rounded to a tenth of a millimetre, a micrometre and a millisecond")
    func rounding() throws {
        let decoded = try PointCodec.decode(
            PointCodec.encode([TrackPoint(lat: 45.12345678912345, lon: 6.0, ele: 100.0000004, timeMillis: 5)])
        )
        let back = try #require(decoded.first)
        #expect(back.lat == 45.123456789)
        #expect(back.ele == 100.0)
    }

    @Test("a 50000-point random walk costs about ten bytes a point and round-trips")
    func randomWalk() throws {
        let random = JavaRandom(42)
        var lat = 45.0
        var lon = 6.0
        var ele = 1500.0
        var points: [TrackPoint] = []
        for i in 0 ..< 50000 {
            lat += (random.nextDouble() - 0.5) * 2e-4
            lon += (random.nextDouble() - 0.5) * 2e-4
            ele += (random.nextDouble() - 0.5) * 2
            points.append(
                TrackPoint(
                    lat: Double(kotlinRound(lat * 1e7)) / 1e7,
                    lon: Double(kotlinRound(lon * 1e7)) / 1e7,
                    ele: Double(kotlinRound(ele * 10)) / 10.0,
                    timeMillis: 1_700_000_000_000 + Int64(i) * 1000
                )
            )
        }
        let bytes = PointCodec.encode(points)
        #expect(bytes.count < points.count * 12, "\(bytes.count) bytes for \(points.count) points")
        #expect(try PointCodec.decode(bytes) == points)
    }

    @Test("real tracks round-trip through the codec")
    func realTracks() throws {
        // GPSBabel writes 9 decimals of position and 6 of elevation: exact. Garmin Connect writes 14 digits (floats
        // widened to doubles): rounded to the codec's units.
        for name in ["korita-zbevnica.gpx", "Mojstrovka.gpx", "cerknicko-jezero.gpx"] {
            let doc = try GpxReader.read(Fixtures.data("gpx/\(name)"))
            for segment in doc.tracks.flatMap(\.segments) {
                #expect(try PointCodec.decode(PointCodec.encode(segment)) == segment, "\(name)")
            }
        }
        let garminDoc = try GpxReader.read(Fixtures.data("gpx/garmin-connect-run.gpx"))
        let garmin = try #require(garminDoc.tracks.first?.segments.first)
        let back = try PointCodec.decode(PointCodec.encode(garmin))
        #expect(back.count == garmin.count)
        for (a, b) in zip(garmin, back) {
            #expect(abs(a.lat - b.lat) <= 1e-9)
            #expect(abs(a.lon - b.lon) <= 1e-9)
            let eleA = try #require(a.ele)
            let eleB = try #require(b.ele)
            #expect(abs(eleA - eleB) <= 1e-6)
            #expect(a.timeMillis == b.timeMillis)
            #expect(a.extras == b.extras)
        }
    }

    @Test("damaged data is refused instead of crashing")
    func damaged() {
        let good = PointCodec.encode([
            TrackPoint(lat: 1.0, lon: 2.0, ele: 3.0, timeMillis: 4),
            TrackPoint(lat: 1.1, lon: 2.1, ele: 3.1, timeMillis: 5),
        ])
        #expect(throws: ToolMapError.self) { try PointCodec.decode(Data()) }
        #expect(throws: ToolMapError.self) { try PointCodec.decode(Data(good.prefix(good.count - 3))) }
        #expect(throws: ToolMapError.self) { try PointCodec.decode(Data([9, 0])) }
        #expect(throws: ToolMapError.self) { try PointCodec.decode(Data([1, 100])) }
    }

    @Test("waypoints, links and extras survive their JSON columns")
    func jsonColumns() throws {
        let waypoints = [
            Waypoint(
                lat: 1.0,
                lon: 2.0,
                ele: 3.0,
                timeMillis: 4,
                name: "n",
                cmt: "c",
                desc: "d",
                src: "s",
                links: [GpxLink(href: "h", text: "t", type: "ty")],
                sym: "sym",
                type: "type",
                extras: [RawXml("<x/>")]
            ),
            Waypoint(lat: 5.0, lon: 6.0),
        ]
        let encoded = try TrackJson.encodeWaypoints(waypoints)
        #expect(try TrackJson.decodeWaypoints(encoded) == waypoints)
        #expect(try TrackJson.encodeLinks([]) == nil)
        #expect(try TrackJson.decodeLinks(nil).isEmpty)
        let links = try TrackJson.encodeLinks([GpxLink(href: "a")])
        #expect(try TrackJson.decodeLinks(links) == [GpxLink(href: "a")])
        let extras = try TrackJson.encodeExtras([RawXml("<e/>")])
        #expect(try TrackJson.decodeExtras(extras) == [RawXml("<e/>")])
    }
}
