// Port of core/src/test/kotlin/fr/toolmap/core/aqx/AqxWriterTest.kt, of the case "a multi-zoom source survives an
// AQX round trip" of source/MapRefTest.kt and of source/LocalOnlyTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct AqxWriterTests {
    private func roundTrip(_ sources: [TileSource]) throws -> AqxParseResult {
        try AqxParser.parse(AqxWriter.write(sources))
    }

    @Test("the built-in catalogue survives a write then parse")
    func builtinRoundTrip() throws {
        let original = try BuiltinSources.load()
        let result = try roundTrip(original)
        #expect(result.file.sources == original)
        #expect(result.warnings.isEmpty, "warnings: \(result.warnings)")
    }

    @Test("the user fixture survives a write then parse")
    func fixtureRoundTrip() throws {
        let original = try AqxParser.parse(Fixtures.text("aqx/IGN_Topo_zoom_test.aqx")).file.sources.filter(\.isSupported)
        #expect(original.count == 2)
        let back = try roundTrip(original).file.sources
        #expect(back == original)
    }

    @Test("every model field is written and read back")
    func everyField() throws {
        let fine = try ZoomSet.parse("1-3,5,7-9")
        let coarse = try ZoomSet.range(10, 12)
        let source = TileSource(
            id: "full",
            name: "Full & <odd> \"name\"",
            type: "topo",
            isLayer: true,
            opacity: 35,
            copyright: "(c) Someone & co",
            dataSource: "https://example.org/data",
            region: "FR",
            outline: [LonLat(lon: 1.0, lat: 2.5), LonLat(lon: 3.25, lat: 4.0), LonLat(lon: -5.0, lat: -6.0)],
            previewLocation: LonLatZoom(lon: 5.9, lat: 44.8, zoom: 12),
            defaultLocation: LonLat(lon: 5.5, lat: 45.5),
            params: ["key": "a&b]]>c", "other": "x"],
            levels: [
                LevelDef(
                    zooms: fine,
                    servers: [
                        "https://a.example/{$z}/{$x}/{$y}.png?k={$param:key}&v=1",
                        "https://b.example/{$q}",
                    ],
                    referer: "https://ref.example/",
                    userAgent: "UA/1.0 (+x)",
                    headers: [HttpHeader("X-One", "1"), HttpHeader("Accept", "image/png: yes")],
                    cookie: "a=b; c=d",
                    updateDelay: "2W",
                    maxThreads: 2,
                    insecure: true,
                    tileSize: 512,
                    clearColor: ClearColor(r: IntRange(250, 255), g: IntRange(0, 10), b: IntRange(7, 7)),
                    keyUrl: "https://example.org/key",
                    bboxCrs: LevelDef.crsWebMercator
                ),
                LevelDef(zooms: coarse, servers: ["https://c.example/{$z}/{$x}/{$y}"]),
            ]
        )
        let result = try roundTrip([source])
        #expect(result.file.sources == [source])
        #expect(result.warnings.isEmpty, "warnings: \(result.warnings)")
    }

    @Test("secrets can be redacted when sharing a file")
    func redaction() throws {
        let zooms = try ZoomSet.range(0, 5)
        let source = TileSource(
            id: "s",
            name: "S",
            params: ["ign-apikey": "SECRET", "lang": "fr"],
            levels: [LevelDef(zooms: zooms, servers: ["https://x/{$z}/{$x}/{$y}"])]
        )
        let text = AqxWriter.write([source], redactedParams: ["ign-apikey"])
        #expect(!text.contains("SECRET"))
        let sources = try AqxParser.parse(text).file.sources
        #expect(sources.count == 1)
        let back = try #require(sources.first)
        #expect(back.params["ign-apikey"] == "")
        #expect(back.params["lang"] == "fr")
    }

    @Test("file name and description are kept and the output is well formed XML")
    func nameAndDescription() throws {
        let text = AqxWriter.write([], name: "Pack <1>", description: "d & e")
        let file = try AqxParser.parse(text).file
        #expect(file.name == "Pack <1>")
        #expect(file.description == "d & e")
        #expect(file.version == AqxWriter.version)
    }

    @Test("numbers are written as Java writes them")
    func javaNumbers() {
        #expect(AqxWriter.number(5.0) == "5")
        #expect(AqxWriter.number(-6.0) == "-6")
        #expect(AqxWriter.number(5.9) == "5.9")
        #expect(AqxWriter.number(-0.125) == "-0.125")
        #expect(AqxWriter.number(0.0005) == "5.0E-4")
        #expect(AqxWriter.number(0.001) == "0.001")
        #expect(AqxWriter.number(1.5e-7) == "1.5E-7")
        #expect(AqxWriter.number(12345678.5) == "1.23456785E7")
        #expect(AqxWriter.number(1234567.25) == "1234567.25")
    }
}

struct MapRefAqxRoundTripTests {
    @Test("a multi-zoom source survives an AQX round trip")
    func multiZoomRoundTrip() throws {
        let low = try ZoomSet.range(1, 11)
        let high = try ZoomSet.range(12, 18)
        let plain = try MapRef(sourceId: "ign-maps")
        let forced = try MapRef(sourceId: "ign-maps", tileZoom: 15)
        let source = TileSource(
            id: "mz-test",
            name: "Test multi-zoom",
            levels: [
                LevelDef(zooms: low, servers: [plain.toServer()]),
                LevelDef(zooms: high, servers: [forced.toServer()]),
            ]
        )
        let sources = try AqxParser.parse(AqxWriter.write([source])).file.sources
        #expect(sources.count == 1)
        let back = try #require(sources.first)
        #expect(back == source)
        #expect(back.isMultiZoom)
        #expect(back.levelFor(13)?.mapRef == forced)
    }
}

struct LocalOnlyTests {
    private let local: TileSource

    init() throws {
        let zooms = try ZoomSet.range(10, 14)
        local = TileSource(
            id: "pack",
            name: "Paquet importé",
            levels: [LevelDef(zooms: zooms, servers: [LevelDef.localOnlyServer])]
        )
    }

    @Test("a level whose only server is the offline marker is local only")
    func localLevel() throws {
        let zooms = try ZoomSet.range(1, 5)
        #expect(LevelDef(zooms: zooms, servers: [LevelDef.localOnlyServer]).isLocalOnly)
        #expect(!LevelDef(zooms: zooms, servers: ["https://t/{$z}/{$x}/{$y}.png"]).isLocalOnly)
        #expect(!LevelDef(zooms: zooms, servers: [LevelDef.localOnlyServer, "https://t/{$z}"]).isLocalOnly)
        #expect(!LevelDef(zooms: zooms, servers: []).isLocalOnly)
    }

    @Test("a source is local only when all its levels are")
    func localSource() throws {
        #expect(local.isLocalOnly)
        let zooms = try ZoomSet.range(15, 16)
        let extra = LevelDef(zooms: zooms, servers: ["https://t/{$z}"])
        let mixed = local.withFields { $0.levels = local.levels + [extra] }
        #expect(!mixed.isLocalOnly)
        #expect(!local.withFields { $0.levels = [] }.isLocalOnly)
    }

    @Test("no built-in source is local only")
    func noBuiltinLocal() throws {
        #expect(try BuiltinSources.load().allSatisfy { !$0.isLocalOnly })
    }

    @Test("a local only source survives an AQX round trip and stays supported")
    func localRoundTrip() throws {
        let result = try AqxParser.parse(AqxWriter.write([local]))
        #expect(result.file.sources == [local])
        let back = try #require(result.file.sources.first)
        #expect(back.isSupported)
        #expect(back.isLocalOnly)
    }
}
