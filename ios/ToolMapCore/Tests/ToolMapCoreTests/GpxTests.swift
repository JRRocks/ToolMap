// Port of core/src/test/kotlin/fr/toolmap/core/track/GpxTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct GpxTests {
    private func fixture(_ name: String) throws -> Data {
        try Fixtures.data("gpx/\(name)")
    }

    private func read(_ text: String) throws -> TrackDocument {
        try GpxReader.read(Data(text.utf8))
    }

    /// What a round trip must keep: everything but who wrote the file and the issues met while reading it.
    private func content(_ document: TrackDocument) -> TrackDocument {
        var copy = document
        copy.creator = nil
        copy.version = nil
        copy.issues = []
        return copy
    }

    /// Kotlin `indexOf`: the offset of `part` in `text`, -1 when it is missing.
    private func offset(_ text: String, _ part: String) -> Int {
        guard let range = text.range(of: part) else { return -1 }
        return text.distance(from: text.startIndex, to: range.lowerBound)
    }

    @Test("Garmin Connect GPX 1_1 keeps heart rate extensions and namespaces")
    func garminConnect() throws {
        let doc = try GpxReader.read(fixture("garmin-connect-run.gpx"))
        #expect(doc.version == "1.1")
        #expect(doc.creator == "Garmin Connect")
        #expect(doc.links == [GpxLink(href: "connect.garmin.com", text: "Garmin Connect")])
        #expect(doc.timeMillis == TrackTime.parse("2012-10-24T23:22:51.000Z"))
        #expect(doc.namespaces["gpxtpx"] == "http://www.garmin.com/xmlschemas/TrackPointExtension/v1")
        #expect(doc.namespaces["gpxx"] == "http://www.garmin.com/xmlschemas/GpxExtensions/v3")

        #expect(doc.tracks.count == 1)
        let track = try #require(doc.tracks.first)
        #expect(track.name == "Untitled")
        #expect(track.segments.count == 1)
        #expect(track.pointCount == 206)
        let first = try #require(track.segments.first?.first)
        #expect(first.lat == 38.92747367732227)
        #expect(first.lon == -77.02016168273985)
        #expect(first.ele == 25.600000381469727)
        #expect(first.timeMillis == TrackTime.parse("2012-10-24T23:29:40.000Z"))
        #expect(first.extras.count == 1)
        let ext = try #require(first.extras.first)
        #expect(ext.name == "extensions")
        #expect(ext.xml.contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        #expect(doc.issues.isEmpty, "unexpected issues: \(doc.issues)")
    }

    @Test("GPSBabel GPX 1_0 with waypoints and several tracks")
    func gpsBabel() throws {
        let doc = try GpxReader.read(fixture("cerknicko-jezero.gpx"))
        #expect(doc.version == "1.0")
        #expect(doc.creator == "GPSBabel - http://www.gpsbabel.org")
        #expect(doc.timeMillis == TrackTime.parse("2010-08-06T10:36:35Z"))
        #expect(doc.waypoints.count == 7)
        let flag = try #require(doc.waypoints.first)
        #expect(flag.name == "001")
        #expect(flag.cmt == "05-AUG-10 16:58:37")
        #expect(flag.sym == "Flag, Blue")
        #expect(flag.lat == 45.772163216)
        #expect(flag.lon == 14.357652292)
        try #require(doc.waypoints.count > 1)
        #expect(doc.waypoints[1].ele == -0.11438)
        // The file has 8 <trk>, some of them without any point: those are dropped and reported.
        #expect(doc.tracks.reduce(0) { $0 + $1.pointCount } == 296)
        #expect(doc.tracks.allSatisfy { !$0.segments.isEmpty })
        #expect(doc.issues.contains { $0.kind == .emptyItemDropped })
        let numbers = doc.tracks.compactMap(\.number)
        #expect(!numbers.isEmpty)
        #expect(numbers.sorted() == numbers)
    }

    @Test("dates written with seven fractional digits and in 1901 are read")
    func oldDates() throws {
        let doc = try GpxReader.read(fixture("Mojstrovka.gpx"))
        let track = try #require(doc.tracks.first)
        #expect(doc.tracks.count == 1)
        #expect(track.pointCount == 184)
        let first = try #require(track.segments.first?.first)
        // 1901-12-13T20:45:52.2073437Z: a GPS that had no date yet; millisecond precision is kept.
        #expect(first.timeMillis == -2_147_483_648_000 + 207)
        #expect(first.ele == 1614.678)
    }

    @Test("big GPX 1_0 file round-trips")
    func bigFile() throws {
        let doc = try GpxReader.read(fixture("korita-zbevnica.gpx"))
        #expect(doc.waypoints.count == 2)
        #expect(doc.tracks.reduce(0) { $0 + $1.pointCount } == 871)
    }

    @Test("every real fixture survives a write and read without loss")
    func roundTrips() throws {
        let names = ["garmin-connect-run.gpx", "Mojstrovka.gpx", "cerknicko-jezero.gpx", "korita-zbevnica.gpx"]
        for name in names {
            let first = try GpxReader.read(fixture(name))
            let text = GpxWriter.toString(first)
            let second = try GpxReader.read(Data(text.utf8))
            #expect(content(first) == content(second), "round trip changed \(name)")
            // And a second generation is byte-for-byte what the first one wrote.
            #expect(text == GpxWriter.toString(second), "writer is not stable on \(name)")
            #expect(
                text.range(of: "[0-9][eE][+-]?[0-9]", options: .regularExpression) == nil,
                "scientific notation in \(name)"
            )
        }
    }

    @Test("exported file is GPX 1_1 with the vendor namespaces declared on the root")
    func exportedRoot() throws {
        let text = try GpxWriter.toString(GpxReader.read(fixture("garmin-connect-run.gpx")))
        let lines = text.split(separator: "\n")
        let root = try #require(lines.first(where: { $0.hasPrefix("<gpx") }))
        #expect(root.contains("version=\"1.1\""))
        #expect(root.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        #expect(root.contains("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""))
        #expect(text.contains("<gpxtpx:hr>130</gpxtpx:hr>"))
        #expect(text.contains("<bounds "))
    }

    @Test("namespace-less GPX and unknown vendor elements are kept")
    func unknownElements() throws {
        let doc = try read(
            """
            <gpx version="1.1" creator="x">
              <wpt lat="1.5" lon="2.5">
                <name>A &amp; B &lt;1&gt;</name>
                <magvar>3.5</magvar>
                <hdop>1.2</hdop>
                <extensions><osmand:color xmlns:osmand="https://osmand.net">#ff0000</osmand:color></extensions>
              </wpt>
            </gpx>
            """
        )
        let wpt = try #require(doc.waypoints.first)
        #expect(doc.waypoints.count == 1)
        #expect(wpt.name == "A & B <1>")
        #expect(wpt.extras.map(\.name) == ["magvar", "hdop", "extensions"])
        #expect(wpt.extras.last?.xml.contains("xmlns:osmand=\"https://osmand.net\"") == true)
        let written = GpxWriter.toString(doc)
        // Schema order is restored: magvar, then name, then hdop, then extensions.
        #expect(offset(written, "<magvar>") < offset(written, "<name>"))
        #expect(offset(written, "<name>") < offset(written, "<hdop>"))
        #expect(offset(written, "<hdop>") < offset(written, "<extensions>"))
        #expect(written.contains("<name>A &amp; B &lt;1&gt;</name>"))
        let back = try GpxReader.read(Data(written.utf8))
        #expect(content(doc) == content(back))
    }

    @Test("routes with named points and links")
    func routes() throws {
        let doc = try read(
            """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <metadata>
                <name>Trip</name><desc>Two days</desc>
                <author><name>Jean</name><email id="j" domain="example.org"/></author>
                <copyright author="Jean"><year>2026</year></copyright>
                <link href="https://example.org"><text>site</text><type>text/html</type></link>
                <keywords>alps, hike</keywords>
              </metadata>
              <rte>
                <name>Day 1</name><number>3</number><type>hike</type>
                <link href="https://example.org/1"/>
                <rtept lat="45.0" lon="6.0"><ele>1000</ele><name>Start</name><sym>Flag</sym></rtept>
                <rtept lat="45.1" lon="6.1"><name>Col</name><desc>turn left</desc></rtept>
              </rte>
            </gpx>
            """
        )
        #expect(doc.name == "Trip")
        #expect(doc.description == "Two days")
        #expect(doc.author == "Jean")
        #expect(doc.keywords == "alps, hike")
        #expect(doc.links == [GpxLink(href: "https://example.org", text: "site", type: "text/html")])
        #expect(doc.metadataExtras.map(\.name) == ["copyright"])
        #expect(doc.issues.contains { $0.kind == .fieldNotKept && $0.detail == "author/email" })
        let route = try #require(doc.routes.first)
        #expect(doc.routes.count == 1)
        #expect(route.name == "Day 1")
        #expect(route.number == 3)
        #expect(route.type == "hike")
        #expect(route.links == [GpxLink(href: "https://example.org/1")])
        #expect(route.points.map(\.name) == ["Start", "Col"])
        #expect(route.points.first?.ele == 1000.0)
        try #require(route.points.count == 2)
        #expect(route.points[1].desc == "turn left")
        let back = try GpxReader.read(GpxWriter.toBytes(doc))
        #expect(content(doc) == content(back))
    }

    @Test("GPX 1_0 url fields become links and speed and course are reported as dropped")
    func gpx10Fields() throws {
        let doc = try read(
            """
            <gpx version="1.0" creator="x" xmlns="http://www.topografix.com/GPX/1/0">
              <name>Old</name><author>Me</author><email>me@example.org</email>
              <url>https://x.org</url><urlname>X</urlname>
              <trk><name>t</name><trkseg>
                <trkpt lat="1" lon="2"><ele>3</ele><course>90</course><speed>2.5</speed></trkpt>
              </trkseg></trk>
            </gpx>
            """
        )
        #expect(doc.name == "Old")
        #expect(doc.author == "Me")
        #expect(doc.links == [GpxLink(href: "https://x.org", text: "X")])
        let point = try #require(doc.tracks.first?.segments.first?.first)
        #expect(point.ele == 3.0)
        #expect(point.extras.isEmpty)
        let dropped = doc.issues.filter { $0.kind == .fieldNotKept }.map(\.detail)
        #expect(
            dropped.contains("trkpt/speed") && dropped.contains("trkpt/course") && dropped.contains("gpx/email"),
            "\(dropped)"
        )
    }

    @Test("bad points and bad times are skipped and reported")
    func badPoints() throws {
        let doc = try read(
            """
            <gpx version="1.1" creator="x"><trk><trkseg>
              <trkpt lat="95" lon="2"><ele>1</ele></trkpt>
              <trkpt lat="x" lon="2"/>
              <trkpt lat="10" lon="20"><time>yesterday</time><ele>nan</ele></trkpt>
              <trkpt lat="11" lon="21"><time>2020-05-01T10:00:00+02:00</time></trkpt>
            </trkseg></trk></gpx>
            """
        )
        let points = try #require(doc.tracks.first?.segments.first)
        try #require(points.count == 2)
        #expect(points[0].timeMillis == nil)
        #expect(points[0].ele == nil)
        #expect(points[1].timeMillis == TrackTime.parse("2020-05-01T08:00:00Z"))
        let kinds = doc.issues.map(\.kind)
        #expect(kinds.filter { $0 == .invalidCoordinate }.count == 2)
        #expect(kinds.contains(.invalidTime))
    }

    @Test("Garmin and OsmAnd colours become the line style")
    func colours() throws {
        let doc = try read(
            """
            <gpx version="1.1" creator="x" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3"
                 xmlns="http://www.topografix.com/GPX/1/1">
              <trk><name>a</name>
                <extensions><gpxx:TrackExtension>
                  <gpxx:DisplayColor>Blue</gpxx:DisplayColor>
                </gpxx:TrackExtension></extensions>
                <trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
              <trk><name>b</name>
                <extensions><color>#80ff8800</color></extensions>
                <trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
              <trk><name>c</name><trkseg><trkpt lat="1" lon="2"/></trkseg></trk>
            </gpx>
            """
        )
        try #require(doc.tracks.count == 3)
        #expect(doc.tracks[0].style?.colorArgb == Int32(bitPattern: 0xFF00_00FF))
        #expect(doc.tracks[1].style?.colorArgb == Int32(bitPattern: 0x80FF_8800))
        #expect(doc.tracks[2].style == nil)
    }

    @Test("multi-segment tracks keep their segments")
    func multiSegment() throws {
        let doc = try read(
            """
            <gpx version="1.1" creator="x"><trk>
              <trkseg><trkpt lat="1" lon="1"/><trkpt lat="1" lon="2"/></trkseg>
              <trkseg><trkpt lat="2" lon="1"/></trkseg>
            </trk></gpx>
            """
        )
        #expect(doc.tracks.first?.segments.map(\.count) == [2, 1])
        let back = try GpxReader.read(GpxWriter.toBytes(doc))
        #expect(content(doc) == content(back))
    }

    @Test("files that are not GPX or are dangerous are refused with a clear error")
    func refused() {
        #expect(throws: TrackFormatError.self) { try read("<kml><Document/></kml>") }
        #expect(throws: TrackFormatError.self) { try read("not xml at all") }
        #expect(throws: TrackFormatError.self) { try read("") }
        let bomb = "<?xml version=\"1.0\"?>\n<!DOCTYPE gpx [<!ENTITY a \"aaaa\">]>\n" +
            "<gpx version=\"1.1\"><name>&a;</name></gpx>"
        #expect(throws: TrackFormatError.self) { try GpxReader.read(Data(bomb.utf8)) }
    }

    @Test("50000 points are written and read back in a few seconds")
    func bigTrack() throws {
        let points = (0 ..< 50000).map { i in
            TrackPoint(
                lat: 45.0 + Double(i) * 1e-5,
                lon: 6.0 + Double(i) * 1e-5,
                ele: 1000.0 + Double(i % 50),
                timeMillis: 1_700_000_000_000 + Int64(i) * 1000
            )
        }
        let doc = TrackDocument(tracks: [Track(name: "big", segments: [points])])
        let started = Date()
        let bytes = GpxWriter.toBytes(doc)
        let back = try GpxReader.read(bytes)
        let seconds = Date().timeIntervalSince(started)
        #expect(back.tracks.first?.segments.first == points)
        #expect(back.tracks.first?.name != nil)
        #expect(seconds < 10, "took \(seconds) s")
    }
}
