// Ports of core/src/test/kotlin/fr/toolmap/core/source/ZoomSetTest.kt, MapRefTest.kt (without the AQX round trip,
// which needs AqxWriter) and UpdateDelayTest.kt.

import Testing
@testable import ToolMapCore

struct ZoomSetTests {
    @Test("parses a range")
    func range() throws {
        let zooms = try ZoomSet.parse("1-17")
        #expect(zooms.min == 1)
        #expect(zooms.max == 17)
        #expect(zooms.size == 17)
        #expect(zooms.isContiguous)
    }

    @Test("parses lists and mixed forms")
    func listsAndMixedForms() throws {
        #expect(try ZoomSet.parse("1,3,5").toList() == [1, 3, 5])
        #expect(try ZoomSet.parse("1-3,5,7-9").toList() == [1, 2, 3, 5, 7, 8, 9])
        #expect(try ZoomSet.parse(" 6, 7 ,8 ").toList() == [6, 7, 8])
    }

    @Test("non contiguous sets are detected")
    func nonContiguous() throws {
        let zooms = try ZoomSet.parse("2,4,6")
        #expect(!zooms.isContiguous)
        #expect(zooms.contains(4))
        #expect(!zooms.contains(5))
    }

    @Test("canonical spec round trips")
    func canonicalSpec() throws {
        for spec in ["1-18", "1-3,5,7-9", "4", "2,4,6"] {
            #expect(try ZoomSet.parse(spec).toSpec() == spec)
        }
        #expect(try ZoomSet.parse("3,1,2,2").toSpec() == "1-3")
    }

    @Test("rejects invalid specs")
    func rejectsInvalidSpecs() {
        for spec in ["", "  ", "a", "5-3", "1-2-3", "-1", "31", "1-40"] {
            #expect(throws: ToolMapError.self, "spec '\(spec)'") { try ZoomSet.parse(spec) }
        }
    }
}

struct MapRefTests {
    @Test("a reference round-trips through its server value")
    func roundTrip() throws {
        let references = try [
            MapRef(sourceId: "ign-maps"),
            MapRef(sourceId: "ign-maps", tileZoom: 15),
            MapRef(sourceId: "IGN_MAPS-2", tileZoom: 0),
            MapRef(sourceId: "é ü/?&", tileZoom: 7),
        ]
        for reference in references {
            #expect(MapRef.parse(reference.toServer()) == reference, "\(reference.toServer())")
        }
        #expect(try MapRef(sourceId: "ign-maps", tileZoom: 15).toServer() == "toolmap-map:ign-maps?z=15")
        #expect(try MapRef(sourceId: "é ü/?&").toServer() == "toolmap-map:%C3%A9%20%C3%BC%2F%3F%26")
    }

    @Test("ordinary and malformed servers are not references")
    func notReferences() {
        #expect(MapRef.parse("https://tile.openstreetmap.org/{$z}/{$x}/{$y}.png") == nil)
        #expect(MapRef.parse(LevelDef.localOnlyServer) == nil)
        #expect(MapRef.parse("toolmap-map:") == nil)
        #expect(MapRef.parse("toolmap-map:osm?z=abc") == nil)
        #expect(MapRef.parse("toolmap-map:osm?z=99") == nil)
        #expect(MapRef.parse("toolmap-map:bad%2") == nil)
    }

    @Test("a level with one reference is a multi-zoom level")
    func multiZoomLevel() throws {
        let whole = try MapRef(sourceId: "ign-maps")
        let forced = try MapRef(sourceId: "ign-maps", tileZoom: 15)
        let source = try TileSource(
            id: "mz-test",
            name: "Test multi-zoom",
            levels: [
                LevelDef(zooms: ZoomSet.range(1, 11), servers: [whole.toServer()]),
                LevelDef(zooms: ZoomSet.range(12, 18), servers: [forced.toServer()]),
            ]
        )
        #expect(source.isMultiZoom)
        #expect(source.levelFor(13)?.mapRef == forced)
        #expect(source.levels.allSatisfy { !$0.hasServer })
    }
}

struct UpdateDelayTests {
    private let day = 86_400_000

    @Test("units convert to milliseconds")
    func units() {
        #expect(UpdateDelay.toMillis("3D") == 3 * day)
        #expect(UpdateDelay.toMillis("2W") == 14 * day)
        #expect(UpdateDelay.toMillis("3M") == 90 * day)
        #expect(UpdateDelay.toMillis("1Y") == 365 * day)
    }

    @Test("case and spaces are tolerated")
    func caseAndSpaces() {
        #expect(UpdateDelay.toMillis(" 2w ") == 14 * day)
        #expect(UpdateDelay.toMillis("1 m") == 30 * day)
    }

    @Test("zero, missing or unreadable means never expires")
    func neverExpires() {
        #expect(UpdateDelay.toMillis("0D") == nil)
        #expect(UpdateDelay.toMillis(nil) == nil)
        #expect(UpdateDelay.toMillis("") == nil)
        #expect(UpdateDelay.toMillis("soon") == nil)
        #expect(UpdateDelay.toMillis("-2W") == nil)
        #expect(UpdateDelay.toMillis("2X") == nil)
    }
}
