// Port of core/src/test/kotlin/fr/toolmap/core/track/KmlGeoJsonTest.kt. The KMZ archives that Kotlin builds with
// `ZipOutputStream` are built here by a small writer (deflated entries by the Compression framework).

import Compression
import Foundation
import Testing
@testable import ToolMapCore

/// CRC-32 of ZIP (polynomial 0xEDB88320).
private func crc32(_ bytes: [UInt8]) -> UInt32 {
    var crc: UInt32 = 0xFFFF_FFFF
    for b in bytes {
        crc ^= UInt32(b)
        for _ in 0 ..< 8 {
            crc = (crc & 1) != 0 ? (crc >> 1) ^ 0xEDB8_8320 : crc >> 1
        }
    }
    return ~crc
}

/// Raw DEFLATE (what a ZIP entry of method 8 holds).
private func deflated(_ bytes: [UInt8]) -> [UInt8] {
    if bytes.isEmpty { return [0x03, 0x00] }
    let capacity = bytes.count + 1024
    var out = [UInt8](repeating: 0, count: capacity)
    let written = out.withUnsafeMutableBufferPointer { destination in
        bytes.withUnsafeBufferPointer { source in
            compression_encode_buffer(
                destination.baseAddress!,
                capacity,
                source.baseAddress!,
                bytes.count,
                nil,
                COMPRESSION_ZLIB
            )
        }
    }
    return Array(out.prefix(written))
}

private func appendLE16(_ value: Int, _ out: inout [UInt8]) {
    out.append(UInt8(value & 0xFF))
    out.append(UInt8((value >> 8) & 0xFF))
}

private func appendLE32(_ value: Int, _ out: inout [UInt8]) {
    for k in 0 ..< 4 {
        out.append(UInt8((value >> (8 * k)) & 0xFF))
    }
}

/// A ZIP archive of the given entries, as `ZipOutputStream` writes one (without its data descriptors).
private func zipArchive(_ entries: [(name: String, content: [UInt8], deflate: Bool)]) -> Data {
    var out: [UInt8] = []
    var central: [UInt8] = []
    for entry in entries {
        let name = Array(entry.name.utf8)
        let stored = entry.deflate ? deflated(entry.content) : entry.content
        let crc = Int(crc32(entry.content))
        let method = entry.deflate ? 8 : 0
        let offset = out.count
        appendLE32(0x0403_4B50, &out)
        appendLE16(20, &out)
        appendLE16(0, &out)
        appendLE16(method, &out)
        appendLE16(0, &out)
        appendLE16(0, &out)
        appendLE32(crc, &out)
        appendLE32(stored.count, &out)
        appendLE32(entry.content.count, &out)
        appendLE16(name.count, &out)
        appendLE16(0, &out)
        out += name
        out += stored

        appendLE32(0x0201_4B50, &central)
        appendLE16(20, &central)
        appendLE16(20, &central)
        appendLE16(0, &central)
        appendLE16(method, &central)
        appendLE16(0, &central)
        appendLE16(0, &central)
        appendLE32(crc, &central)
        appendLE32(stored.count, &central)
        appendLE32(entry.content.count, &central)
        appendLE16(name.count, &central)
        appendLE16(0, &central)
        appendLE16(0, &central)
        appendLE16(0, &central)
        appendLE16(0, &central)
        appendLE32(0, &central)
        appendLE32(offset, &central)
        central += name
    }
    let centralOffset = out.count
    out += central
    appendLE32(0x0605_4B50, &out)
    appendLE16(0, &out)
    appendLE16(0, &out)
    appendLE16(entries.count, &out)
    appendLE16(entries.count, &out)
    appendLE32(central.count, &out)
    appendLE32(centralOffset, &out)
    appendLE16(0, &out)
    return Data(out)
}

struct KmlGeoJsonTests {
    private func kml(_ name: String) throws -> Data {
        try Fixtures.data("kml/\(name)")
    }

    @Test("point placemark becomes a waypoint")
    func point() throws {
        let doc = try KmlReader.read(kml("point.kml"))
        let w = try #require(doc.waypoints.first)
        #expect(doc.waypoints.count == 1)
        #expect(w.name == "Simple placemark")
        #expect(w.lat == 37.42228990140251)
        #expect(w.lon == -122.0822035425683)
        #expect(w.ele == 0.0)
        #expect((w.desc ?? "").contains("Attached to the ground"))
        #expect(doc.format == .kml)
    }

    @Test("line strings become tracks and multi-geometry becomes one multi-segment track")
    func lineStrings() throws {
        let lines = try KmlReader.read(kml("linestring.kml"))
        #expect(lines.name == "LineString.kml")
        #expect(lines.tracks.map(\.name) == ["unextruded", "extruded"])
        try #require(lines.tracks.count == 2)
        #expect(lines.tracks[1].segments.first?.first?.ele == 50.0)

        let multi = try KmlReader.read(kml("multigeometry.kml"))
        let track = try #require(multi.tracks.first)
        #expect(multi.tracks.count == 1)
        #expect(track.name == "SF Marina Harbor Master")
        #expect(track.segments.map(\.count) == [2, 2])
    }

    @Test("polygon outer ring becomes a closed route and the inner ring is ignored")
    func polygon() throws {
        let doc = try KmlReader.read(kml("polygon.kml"))
        let route = try #require(doc.routes.first)
        #expect(doc.routes.count == 1)
        #expect(route.name == "hollow box")
        #expect(route.points.count == 5)
        #expect(route.points.first?.lat == route.points.last?.lat)
        #expect(doc.issues.map(\.kind).contains(.polygonAsRoute))
    }

    @Test("KML without a namespace is read")
    func noNamespace() throws {
        let doc = try KmlReader.read(kml("style.kml"))
        #expect(doc.waypoints.count == 2)
        #expect(doc.waypoints.first?.name == "Google Earth - New Polygon")
    }

    @Test("folders, styles, style maps, timestamps and gx tracks")
    func foldersStylesGxTrack() throws {
        let doc = try KmlReader.read(kml("folders-styles-gxtrack.kml"))
        #expect(doc.name == "Sortie test")

        let refuge = try #require(doc.waypoints.first)
        #expect(doc.waypoints.count == 1)
        #expect(refuge.name == "Refuge")
        #expect(refuge.folder == "Jour 1 / Points")
        #expect(refuge.timeMillis == TrackTime.parse("2026-07-14T08:30:00Z"))
        #expect(refuge.ele == 3613.0)

        let climb = try #require(doc.tracks.first(where: { $0.name == "Montée" }))
        #expect(climb.folder == "Jour 1")
        #expect(climb.desc == "<b>Raide</b> & longue")
        // KML colours are aabbggrr: ff0000ff is opaque red.
        #expect(climb.style?.colorArgb == Int32(bitPattern: 0xFFFF_0000))
        #expect(climb.style?.widthDp == 5)
        #expect(climb.segments.first?.map(\.ele) == [1000.0, 1200.0, 1500.0])

        let variant = try #require(doc.tracks.first(where: { $0.name == "Variante" }))
        // Resolved through the StyleMap's "normal" pair: 80ff0000 is half-transparent blue.
        #expect(variant.style?.colorArgb == Int32(bitPattern: 0x8000_00FF))
        #expect(variant.style?.widthDp == 2.5)
        let start = try #require(variant.segments.first?.first)
        #expect(start.ele == nil)

        let gx = try #require(doc.tracks.first(where: { $0.name == "Enregistrement GPS" }))
        let points = try #require(gx.segments.first)
        try #require(points.count == 3)
        #expect(points[1].timeMillis == TrackTime.parse("2026-07-14T09:00:10Z"))
        #expect(points[1].ele == 1002.0)
        #expect(points[1].lon == 6.8601)
    }

    @Test("KMZ is a zip holding the KML")
    func kmz() throws {
        let kmlBytes = try [UInt8](kml("point.kml"))
        let archive = zipArchive([
            (name: "files/icon.png", content: [1, 2, 3], deflate: true),
            (name: "doc.kml", content: kmlBytes, deflate: true),
        ])
        let doc = try TrackImporter.read(archive, nameHint: "x.kmz")
        #expect(doc.format == .kmz)
        #expect(doc.waypoints.first?.name == "Simple placemark")
        #expect(doc.waypoints.count == 1)
        // The same with a stored (not compressed) entry.
        let stored = zipArchive([(name: "doc.kml", content: kmlBytes, deflate: false)])
        let fromStored = try TrackImporter.read(stored)
        #expect(fromStored.waypoints.first?.name == "Simple placemark")
        let empty = zipArchive([(name: "a.txt", content: [], deflate: false)])
        #expect(throws: TrackFormatError.self) { try TrackImporter.read(empty) }
    }

    @Test("GeoJSON feature collection with every geometry type")
    func geoJsonCollection() throws {
        let doc = try GeoJsonReader.read(
            """
            {"type":"FeatureCollection","name":"Test","features":[
              {"type":"Feature","properties":{"name":"Col","time":"2026-01-02T03:04:05Z"},
               "geometry":{"type":"Point","coordinates":[6.5,45.5,2000.5]}},
              {"type":"Feature","properties":{"title":"Sentier","stroke":"#00ff00","stroke-width":3,
                 "coordTimes":["2026-01-02T03:04:05Z","2026-01-02T03:04:15Z"]},
               "geometry":{"type":"LineString","coordinates":[[6.0,45.0,100],[6.1,45.1,110]]}},
              {"type":"Feature","properties":{"name":"Deux parties"},
               "geometry":{"type":"MultiLineString","coordinates":[[[1,2],[3,4]],[[5,6],[7,8]]]}},
              {"type":"Feature","properties":{"name":"Lac"},
               "geometry":{"type":"Polygon","coordinates":[[[1,1],[2,1],[2,2],[1,1]],[[1.2,1.1],[1.3,1.1],[1.2,1.2],[1.2,1.1]]]}},
              {"type":"Feature","properties":{},
               "geometry":{"type":"GeometryCollection","geometries":[
                  {"type":"MultiPoint","coordinates":[[10,20],[11,21]]}]}},
              {"type":"Feature","properties":{"name":"Cassé"},
               "geometry":{"type":"LineString","coordinates":[[1,2],["x",3],[500,4],[5,6]]}}
            ]}
            """
        )
        #expect(doc.name == "Test")
        #expect(doc.waypoints.map(\.name) == ["Col", nil, nil])
        #expect(doc.waypoints.first?.ele == 2000.5)
        #expect(doc.waypoints.first?.timeMillis == TrackTime.parse("2026-01-02T03:04:05Z"))
        let trail = try #require(doc.tracks.first(where: { $0.name == "Sentier" }))
        #expect(trail.style?.colorArgb == Int32(bitPattern: 0xFF00_FF00))
        #expect(trail.style?.widthDp == 3)
        let trailPoints = try #require(trail.segments.first)
        try #require(trailPoints.count == 2)
        #expect(trailPoints[1].timeMillis == TrackTime.parse("2026-01-02T03:04:15Z"))
        let twoParts = try #require(doc.tracks.first(where: { $0.name == "Deux parties" }))
        #expect(twoParts.segments.map(\.count) == [2, 2])
        #expect(doc.routes.count == 1)
        #expect(doc.routes.first?.points.count == 4)
        // Two of the four positions of the broken line are unusable.
        let broken = try #require(doc.tracks.first(where: { $0.name == "Cassé" }))
        #expect(broken.segments.first?.count == 2)
        #expect(doc.issues.map(\.kind).contains(.invalidCoordinate))
    }

    @Test("bare geometry and bad GeoJSON")
    func bareAndBad() throws {
        let doc = try GeoJsonReader.read(#"{"type":"LineString","coordinates":[[0,0],[1,1]]}"#)
        #expect(doc.tracks.count == 1)
        #expect(throws: TrackFormatError.self) { try GeoJsonReader.read("[1,2,3]") }
        #expect(throws: TrackFormatError.self) { try GeoJsonReader.read(#"{"hello":1}"#) }
        #expect(throws: TrackFormatError.self) { try GeoJsonReader.read("{broken") }
    }

    @Test("importer detects the format from the content")
    func detection() throws {
        let gpx = #"<?xml version="1.0"?><gpx version="1.1" creator="x"><wpt lat="1" lon="2"/></gpx>"#
        #expect(TrackImporter.detect(Data(gpx.utf8)) == .gpx)
        #expect(TrackImporter.detect(Data("  \n\(gpx)".utf8)) == .gpx)
        #expect(TrackImporter.detect(Data(#"<topo:gpx xmlns:topo="x"/>"#.utf8)) == .gpx)
        let pointKml = try kml("point.kml")
        #expect(TrackImporter.detect(pointKml) == .kml)
        #expect(TrackImporter.detect(Data(#"  {"type":"Point"}"#.utf8)) == .geojson)
        #expect(TrackImporter.detect(Data("<html></html>".utf8)) == nil)
        #expect(TrackImporter.detect(Data()) == nil)
        #expect(throws: TrackFormatError.self) { try TrackImporter.read(Data("hello".utf8), nameHint: "a.txt") }
        let fromGpx = try TrackImporter.read(Data(gpx.utf8))
        #expect(fromGpx.waypoints.count == 1)
        let polygonKml = try kml("polygon.kml")
        let fromKml = try TrackImporter.read(polygonKml)
        #expect(!fromKml.routes.isEmpty)
    }
}
