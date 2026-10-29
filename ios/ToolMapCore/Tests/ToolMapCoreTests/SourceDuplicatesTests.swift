// Port of core/src/test/kotlin/fr/toolmap/core/source/SourceDuplicatesTest.kt and source/CreditsTest.kt.

import Testing
@testable import ToolMapCore

struct SourceDuplicatesTests {
    private let builtins: [TileSource]

    init() throws {
        builtins = try BuiltinSources.load()
    }

    private func fixture(_ name: String) throws -> [TileSource] {
        try AqxParser.parse(Fixtures.text("aqx/\(name)")).file.sources
    }

    private func builtin(_ id: String) throws -> TileSource {
        try #require(builtins.first(where: { $0.id == id }))
    }

    @Test("the user's IGN files are exact duplicates of built-in maps")
    func ignDuplicates() throws {
        let test4 = try fixture("user/USR.AQ_TEST_4.AQX")
        let maps = try #require(test4.first(where: { $0.id == "IGN_MAPS" }))
        #expect(SourceDuplicates.of(maps, builtins).duplicateOf?.id == "ign-maps")
        // SCAN 25 up to zoom 16, like the built-in one since the server was checked.
        let scan = try #require(test4.first(where: { $0.id == "IGN_SCAN25TOUR" }))
        #expect(SourceDuplicates.of(scan, builtins).duplicateOf?.id == "ign-scan25")
        let skiTouring = try fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX")
        let pentes = try #require(skiTouring.first(where: { $0.id == "PENTES" }))
        #expect(SourceDuplicates.of(pentes, builtins).duplicateOf?.id == "ign-pentes")
    }

    @Test("a map that goes further than the catalogue one only shares its tiles")
    func widerShares() throws {
        let scan = try builtin("ign-scan25")
        let level = try #require(scan.levels.first)
        try #require(scan.levels.count == 1)
        let zooms = try ZoomSet.range(6, 17)
        let wider = scan.withFields { fields in
            fields.id = "SCAN_WIDE"
            fields.levels = [level.withFields { $0.zooms = zooms }]
        }
        let info = SourceDuplicates.of(wider, builtins)
        #expect(info.duplicateOf == nil)
        #expect(info.sharesWith.map(\.id) == ["ign-scan25"])
    }

    @Test("the multi-zoom file shares with four maps without duplicating any")
    func multiZoomShares() throws {
        let file = try fixture("user/USR.PERSONNAL_MULTIZOOM.AQX")
        try #require(file.count == 1)
        let info = SourceDuplicates.of(file[0], builtins)
        #expect(info.duplicateOf == nil)
        #expect(Set(info.sharesWith.map(\.id)) == ["osm", "opentopomap", "ign-scan25", "ign-ortho"])
    }

    @Test("new maps, the map itself and multi-zoom maps are not duplicates")
    func notDuplicates() throws {
        let snow = try #require(try fixture("user/COM.109.AQX").first)
        #expect(SourceDuplicates.of(snow, builtins).isEmpty)
        let osm = try builtin("osm")
        #expect(SourceDuplicates.of(osm, builtins).isEmpty)
        let topo25 = try builtin("ign-topo25k")
        #expect(SourceDuplicates.of(topo25, builtins).isEmpty)
        // A base map and a layer with the same tiles are kept apart.
        let asLayer = osm.withFields { fields in
            fields.id = "osm-layer"
            fields.isLayer = true
        }
        let info = SourceDuplicates.of(asLayer, builtins)
        #expect(info.duplicateOf == nil)
        #expect(info.sharesWith.map(\.id) == ["osm"])
    }
}

struct CreditsTests {
    @Test("the credits of the built-in maps fit on one short line")
    func builtinCredits() {
        let shown = [
            "(c) OpenStreetMap contributors",
            "(c) OpenStreetMap contributors, SRTM | Style : (c) OpenTopoMap (CC-BY-SA)",
            "IGN",
        ]
        #expect(Credits.short(shown) == "© OSM · SRTM · OpenTopoMap · IGN")
    }

    @Test("a name is kept once whatever its case and licence")
    func nameOnce() {
        let credits = ["OpenStreetMap contributors (CC-BY-SA)", "openstreetmap", "Mapzen, AWS Open Data Terrain Tiles"]
        #expect(Credits.short(credits) == "© OSM · Mapzen · AWS Open Data Terrain Tiles")
    }

    @Test("no credits give an empty line")
    func noCredits() {
        #expect(Credits.short([]) == "")
        #expect(Credits.short([" (c) ", ""]) == "")
    }
}
