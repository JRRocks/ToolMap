// Port of core/src/test/kotlin/fr/toolmap/core/tiles/TileUrlBuilderTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct TileUrlBuilderTests {
    private func source(
        _ servers: String...,
        params: [String: String] = [:],
        bboxCrs: String = LevelDef.crsWGS84
    ) throws -> TileSource {
        try TileSource(
            id: "T",
            name: "T",
            params: params,
            levels: [LevelDef(zooms: ZoomSet.range(0, 20), servers: servers, bboxCrs: bboxCrs)]
        )
    }

    private func url(_ source: TileSource, _ z: Int, _ x: Int, _ y: Int) throws -> String? {
        try TileUrlBuilder(source).build(z, x, y)
    }

    @Test("xyz placeholders")
    func xyz() throws {
        #expect(try url(source("https://t.example/{$z}/{$x}/{$y}.png"), 3, 3, 5) == "https://t.example/3/3/5.png")
    }

    @Test("tms row")
    func tmsRow() throws {
        #expect(try url(source("https://t.example/{$z}/{$x}/{$!y}"), 3, 3, 5) == "https://t.example/3/3/2")
    }

    @Test("quadkey")
    func quadkey() throws {
        #expect(try url(source("https://t.example/{$q}.jpg"), 3, 3, 5) == "https://t.example/213.jpg")
    }

    @Test("param placeholder and missing param")
    func params() throws {
        let withKey = try source("https://t.example/{$z}?apikey={$param:apikey}", params: ["apikey": "k1"])
        #expect(try url(withKey, 4, 1, 1) == "https://t.example/4?apikey=k1")
        let withoutKey = try source("https://t.example/{$z}?apikey={$param:apikey}")
        do {
            _ = try url(withoutKey, 4, 1, 1)
            Issue.record("a missing param must be an error")
        } catch let error as TileUrlError {
            #expect(error.message.contains("apikey"))
        }
    }

    @Test("bbox in WGS84 is the default")
    func bboxWgs84() throws {
        // World tile at zoom 1, top-left quadrant.
        #expect(try url(source("bbox={$bbox}"), 1, 0, 0) == "bbox=-180,0,0,85.0511287798")
    }

    @Test("bbox in Web Mercator")
    func bboxMercator() throws {
        let mercator = try source("bbox={$bbox}", bboxCrs: LevelDef.crsWebMercator)
        #expect(try url(mercator, 1, 0, 0) == "bbox=-20037508.3428,0,0,20037508.3428")
    }

    @Test("integer bbox")
    func integerBbox() throws {
        let mercator = try source("{$ibbox}", bboxCrs: LevelDef.crsWebMercator)
        #expect(try url(mercator, 1, 0, 0) == "-20037508,0,0,20037508")
    }

    @Test("bbox uses a dot as decimal separator")
    func bboxDecimalSeparator() throws {
        let bbox = try #require(try url(source("{$bbox}"), 5, 10, 10))
        #expect(bbox.filter { $0 == "," }.count == 3, "only the 4 values are comma separated: \(bbox)")
        #expect(bbox.contains("."), "decimal separator must be a dot: \(bbox)")
    }

    @Test("bbox rounds half up on the exact binary value, like BigDecimal")
    func bboxHalfUp() {
        // -180 + 360 / 2^14 = -179.97802734375 exactly: the 11th decimal is a 5 followed by nothing.
        #expect(DecimalText.halfUp(-179.97802734375, decimals: 10) == "-179.9780273438")
        #expect(DecimalText.halfUp(2.5, decimals: 0) == "3")
        #expect(DecimalText.halfUp(-2.5, decimals: 0) == "-3")
        #expect(DecimalText.halfUp(-0.0, decimals: 4) == "0")
        #expect(DecimalText.halfUp(-0.00001, decimals: 4) == "0")
        #expect(DecimalText.halfUp(1e300, decimals: 4).count == 301)
        #expect(DecimalText.halfUp(0.1, decimals: 30) == "0.100000000000000005551115123126")
    }

    @Test("unknown placeholder is an error")
    func unknownPlaceholder() throws {
        do {
            _ = try url(source("https://{$serverpart}.example/{$z}"), 1, 0, 0)
            Issue.record("an unknown placeholder must be an error")
        } catch let error as TileUrlError {
            #expect(error.message.contains("serverpart"))
        }
    }

    @Test("servers rotate round robin")
    func roundRobin() throws {
        let builder = try TileUrlBuilder(source("https://a/{$z}", "https://b/{$z}", "https://c/{$z}"))
        let urls = try (0 ..< 7).map { _ in try builder.build(2, 0, 0) }
        #expect(urls == [
            "https://a/2",
            "https://b/2",
            "https://c/2",
            "https://a/2",
            "https://b/2",
            "https://c/2",
            "https://a/2",
        ])
    }

    @Test("explicit server index is deterministic and wraps")
    func explicitServerIndex() throws {
        let twoServers = try source("https://a/{$z}", "https://b/{$z}")
        let builder = TileUrlBuilder(twoServers)
        let level = twoServers.levels[0]
        #expect(try builder.build(level, 1, 0, 0, serverIndex: 1) == "https://b/1")
        #expect(try builder.build(level, 1, 0, 0, serverIndex: 3) == "https://b/1")
        #expect(try builder.build(level, 1, 0, 0, serverIndex: -1) == "https://b/1")
    }

    @Test("zoom outside every level gives nil")
    func zoomOutside() throws {
        let limited = try TileSource(
            id: "L",
            name: "L",
            levels: [LevelDef(zooms: ZoomSet.range(6, 14), servers: ["https://t/{$z}"])]
        )
        #expect(try TileUrlBuilder(limited).build(5, 0, 0) == nil)
        #expect(try TileUrlBuilder(limited).build(6, 0, 0) == "https://t/6")
    }

    @Test("reference IGN url at zoom 14")
    func referenceIgnUrl() throws {
        let file = try AqxParser.parse(Fixtures.text("aqx/IGN_Topo_zoom_test.aqx")).file
        let scan25 = try #require(file.sources.first { $0.id == "IGN_SCAN25TOUR" })
        // Same tile as the curl check of session 1 (HTTP 200, image/jpeg).
        #expect(
            try TileUrlBuilder(scan25).build(14, 8460, 5950) ==
                "https://data.geopf.fr/private/wmts?apikey=ign_scan_ws&layer=GEOGRAPHICALGRIDSYSTEMS.MAPS.SCAN25TOUR" +
                "&style=normal&tilematrixset=PM&Service=WMTS&Request=GetTile&Version=1.0.0&Format=image/jpeg" +
                "&TileMatrix=14&TileCol=8460&TileRow=5950"
        )
        #expect(try TileUrlBuilder(scan25).build(15, 0, 0) == nil, "SCAN 25 has no tile above zoom 14")
    }
}
