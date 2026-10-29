// Port of core/src/test/kotlin/fr/toolmap/core/source/BuiltinSourcesTest.kt (the favourites' cases come with the
// layers, P32.6).

import Testing
@testable import ToolMapCore

struct BuiltinSourcesTests {
    private let sources: [TileSource]

    init() throws {
        sources = try BuiltinSources.load()
    }

    private func source(_ id: String) throws -> TileSource {
        try #require(sources.first { $0.id == id })
    }

    @Test("the resource parses without any warning")
    func noWarning() throws {
        let result = try AqxParser.parse(BuiltinSources.text())
        #expect(result.warnings.isEmpty, "warnings: \(result.warnings)")
    }

    @Test("ids are unique and urls have no unresolved placeholder")
    func uniqueIdsAndResolvedUrls() throws {
        #expect(Set(sources.map(\.id)).count == sources.count)
        for source in sources {
            #expect(source.isSupported, "\(source.id): \(source.unsupportedReason ?? "")")
            let preview = try #require(source.previewLocation)
            #expect(source.levelFor(preview.zoom) != nil, "\(source.id) has no level at its preview zoom")
            #expect(source.copyright != nil, "\(source.id) needs a copyright (attribution is mandatory)")
            if source.isMultiZoom {
                for level in source.levels {
                    let reference = try #require(level.mapRef, "\(source.id): a multi-zoom map only shows other maps")
                    #expect(sources.contains { $0.id == reference.sourceId && !$0.isMultiZoom }, "\(source.id)")
                }
                continue
            }
            let tile = try TileMath.tileAt(preview.lon, preview.lat, preview.zoom)
            let url = try #require(try TileUrlBuilder(source).build(preview.zoom, tile.x, tile.y))
            #expect(!url.contains("{") && !url.contains("}"), "\(source.id): unresolved placeholder in \(url)")
            #expect(url.hasPrefix("https://"), "\(source.id): \(url)")
        }
    }

    @Test("IGN scan 25 is served from zoom 6 to 16 with the public key")
    func scan25() throws {
        let scan25 = try source("ign-scan25")
        #expect(scan25.minZoom == 6)
        #expect(scan25.maxZoom == 16)
        let url = try #require(try TileUrlBuilder(scan25).build(14, 8460, 5906))
        #expect(url.contains("apikey=ign_scan_ws") && url.contains("SCAN25TOUR") && url.hasSuffix("TileRow=5906"))
        #expect(scan25.levels.first?.referer == "https://data.geopf.fr/")
    }

    @Test("slopes is a half transparent overlay")
    func slopes() throws {
        let slopes = try source("ign-pentes")
        #expect(slopes.isLayer)
        #expect(slopes.opacity == 50)
        #expect(slopes.minZoom == 1)
        #expect(slopes.maxZoom == 16)
    }

    @Test("OSM declares an identifying user agent and at most two connections")
    func osm() throws {
        let level = try #require(try source("osm").levels.first)
        #expect((level.userAgent ?? "").hasPrefix("ToolMap/"))
        #expect(level.maxThreads == 2)
    }

    @Test("the catalogue holds every planned map plus the elevation source")
    func catalogue() throws {
        let expected: Set<String> = [
            "osm", "opentopomap", "ign-maps", "ign-plan", "ign-ortho", "ign-scan25", "ign-pentes", "ign-topo25k",
            "OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES", "terrarium",
        ]
        #expect(Set(sources.map(\.id)) == expected)
        #expect(try source("terrarium").type == "dem")
        #expect(sources.filter(\.isLayer).map(\.id) == ["ign-pentes", "OSMSKIM", "TRACESHIVERNALES"])
    }

    @Test("OpenTopoMap rotates its three servers and identifies ToolMap")
    func openTopoMap() throws {
        let topo = try source("opentopomap")
        let level = try #require(topo.levels.first)
        #expect(level.servers.count == 3)
        #expect((level.userAgent ?? "").hasPrefix("ToolMap/"))
        let builder = TileUrlBuilder(topo)
        var hosts: [String] = []
        for _ in 0 ..< 3 {
            let url = try #require(try builder.build(14, 8460, 5906))
            hosts.append(String(url.dropFirst("https://".count).prefix { $0 != "." }))
        }
        #expect(hosts == ["a", "b", "c"])
    }

    @Test("IGN public layers need neither key nor referer")
    func ignPublicLayers() throws {
        for id in ["ign-plan", "ign-ortho", "ign-pentes"] {
            let level = try #require(try source(id).levels.first)
            #expect(level.referer == nil, "\(id) should not declare a referer")
            #expect(!level.servers.contains { $0.contains("apikey") }, "\(id) should not need an API key")
        }
    }

    @Test("IGN Topo 25k keeps the 1 to 25,000 map from zoom 13 to 18 with the tiles of IGN Cartes")
    func topo25k() throws {
        let topo25 = try source("ign-topo25k")
        #expect(topo25.isMultiZoom)
        #expect(topo25.minZoom == 1)
        #expect(topo25.maxZoom == 18)
        let whole = try MapRef(sourceId: "ign-maps")
        let at15 = try MapRef(sourceId: "ign-maps", tileZoom: 15)
        let at16 = try MapRef(sourceId: "ign-maps", tileZoom: 16)
        #expect(topo25.levelFor(12)?.mapRef == whole)
        #expect(topo25.levelFor(13)?.mapRef == at15)
        #expect(topo25.levelFor(14)?.mapRef == at15)
        #expect(topo25.levelFor(16)?.mapRef == whole)
        #expect(topo25.levelFor(18)?.mapRef == at16)
    }
}
