// Port of core/src/test/kotlin/fr/toolmap/core/source/TileSetIdentityTest.kt.

import Foundation
import Testing
@testable import ToolMapCore

struct TileSetIdentityTests {
    private let builtins: [TileSource]
    private let keys: StorageKeys

    init() throws {
        builtins = try BuiltinSources.load()
        keys = StorageKeys(builtins: builtins)
    }

    private func fixture(_ name: String) throws -> [TileSource] {
        try AqxParser.parse(Fixtures.text("aqx/\(name)")).file.sources
    }

    private func builtin(_ id: String) throws -> TileSource {
        try #require(builtins.first { $0.id == id })
    }

    private func storageOf(_ source: TileSource, _ zoom: Int) -> String? {
        keys.storageIdFor(source, zoom) { _ in nil }
    }

    @Test("the same IGN map described by four files is stored once")
    func ignMapsOnce() throws {
        let reference = try #require(try fixture("IGN_Topo_zoom_test.aqx").first { $0.id == "IGN_MAPS" })
        let test4 = try #require(try fixture("user/USR.AQ_TEST_4.AQX").first { $0.id == "IGN_MAPS" })
        let skiTouring = try #require(
            try fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first { $0.id == "IGN_TOP25" }
        )
        for source in [reference, test4, skiTouring] {
            #expect(storageOf(source, 12) == "ign-maps", "\(source.id)")
        }
        #expect(storageOf(try builtin("ign-maps"), 12) == "ign-maps")
    }

    @Test("SCAN 25 shares its storage whatever the zoom list of the file")
    func scan25Shared() throws {
        let file = try fixture("IGN_Topo_zoom_test.aqx")
        let reference = try #require(file.first { $0.id == "IGN_SCAN25TOUR" })
        let test4 = try #require(try fixture("user/USR.AQ_TEST_4.AQX").first { $0.id == "IGN_SCAN25TOUR" })
        #expect(storageOf(reference, 10) == "ign-scan25")
        #expect(storageOf(test4, 16) == "ign-scan25")
        let firstSource = try #require(file.first)
        #expect(storageOf(test4, 16) != storageOf(firstSource, 16))
    }

    @Test("the personal multi-zoom file reuses the storage of five built-in maps")
    func personalMultiZoom() throws {
        let multi = try #require(try fixture("user/USR.PERSONNAL_MULTIZOOM.AQX").first)
        #expect(storageOf(multi, 5) == "osm")
        #expect(storageOf(multi, 10) == "opentopomap")
        #expect(storageOf(multi, 14) == "ign-scan25")
        #expect(storageOf(multi, 18) == "osm")
        #expect(storageOf(multi, 19) == "ign-ortho")
        // Zoom 17 is in no <zoom-levels> of the file: nothing is stored for it.
        #expect(storageOf(multi, 17) == nil)
        #expect(Set(keys.storageIdsOf(multi) { _ in nil }) == ["osm", "opentopomap", "ign-scan25", "ign-ortho"])
    }

    @Test("slopes written with other parameter case and order is the built-in slopes layer")
    func slopes() throws {
        let pentes = try #require(
            try fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").first { $0.id == "PENTES" }
        )
        #expect(storageOf(pentes, 12) == "ign-pentes")
    }

    @Test("normalization keeps what changes the image")
    func normalization() {
        let a = TileSetIdentity.normalizeServer("https://a.tile.example.org/{$z}/{$x}/{$y}.png")
        let b = TileSetIdentity.normalizeServer("http://C.tile.example.org/{$z}/{$x}/{$y}.png")
        #expect(a == b)
        // TMS rows are other tiles.
        #expect(a != TileSetIdentity.normalizeServer("https://a.tile.example.org/{$z}/{$x}/{$!y}.png"))
        // Paths are case-sensitive.
        #expect(a != TileSetIdentity.normalizeServer("https://a.tile.example.org/{$z}/{$x}/{$Y}.PNG"))
        // Different layers stay apart, keys never count.
        let wmts = "https://data.geopf.fr/wmts?layer=A&Format=image/png&TileMatrix={$z}&apikey=one"
        #expect(
            TileSetIdentity.normalizeServer(wmts) ==
                TileSetIdentity.normalizeServer("https://data.geopf.fr/wmts?FORMAT=image/png&LAYER=a&TILEMATRIX={$z}&key=2")
        )
        #expect(
            TileSetIdentity.normalizeServer(wmts) !=
                TileSetIdentity.normalizeServer(wmts.replacingOccurrences(of: "layer=A", with: "layer=B"))
        )
        // A two-label host has no rotating label to drop.
        #expect(TileSetIdentity.normalizeServer("https://example.org/t") == "example.org/t")
    }

    @Test("parameters are substituted before comparing")
    func parameters() throws {
        let viaParam = TileSource(
            id: "p",
            name: "p",
            params: ["layer": "ORTHO"],
            levels: [LevelDef(zooms: try ZoomSet.range(1, 5), servers: ["https://h.example.org/w?l={$param:layer}"])]
        )
        let inline = TileSource(
            id: "i",
            name: "p",
            params: ["layer": "ORTHO"],
            levels: [LevelDef(zooms: try ZoomSet.range(3, 9), servers: ["https://h.example.org/w?l=ortho"])]
        )
        #expect(TileSetIdentity.allOf(viaParam) == TileSetIdentity.allOf(inline))
        let storage = try #require(storageOf(viaParam, 3))
        #expect(storage.hasPrefix("t-"))
        #expect(storageOf(inline, 3) == storage)
    }

    @Test("a local package and a reference have no identity")
    func localAndReference() throws {
        let local = TileSource(
            id: "pack",
            name: "Pack",
            levels: [LevelDef(zooms: try ZoomSet.range(1, 5), servers: [LevelDef.localOnlyServer])]
        )
        #expect(TileSetIdentity.of(local, local.levels[0]) == nil)
        #expect(storageOf(local, 3) == "pack")
        let reference = LevelDef(zooms: try ZoomSet.range(1, 5), servers: [try MapRef(sourceId: "osm").toServer()])
        #expect(!reference.hasServer)
        #expect(TileSetIdentity.of(local, reference) == nil)
    }

    @Test("a multi-zoom map built in the app stores nothing of its own")
    func multiZoomStoresNothing() throws {
        var byId: [String: TileSource] = [:]
        for source in builtins {
            byId[source.id] = source
        }
        let topo25 = TileSource(
            id: "mz-topo25",
            name: "IGN Topo 25k",
            levels: [
                LevelDef(zooms: try ZoomSet.range(1, 12), servers: [try MapRef(sourceId: "ign-maps").toServer()]),
                LevelDef(zooms: try ZoomSet.range(13, 14), servers: [try MapRef(sourceId: "ign-maps", tileZoom: 15).toServer()]),
                LevelDef(zooms: try ZoomSet.range(15, 16), servers: [try MapRef(sourceId: "ign-maps").toServer()]),
            ]
        )
        #expect(keys.storageIdFor(topo25, 12) { byId[$0] } == "ign-maps")
        // A forced zoom is composed from the zoom-15 tiles: no zoom-13 tile is ever stored.
        #expect(keys.storageIdFor(topo25, 13) { byId[$0] } == nil)
        #expect(keys.storageIdsOf(topo25) { byId[$0] } == ["ign-maps"])
        // A deleted member leaves the level without storage.
        #expect(keys.storageIdFor(topo25, 12) { _ in nil } == nil)
        let loop = TileSource(
            id: "loop",
            name: "loop",
            levels: [LevelDef(zooms: try ZoomSet.range(1, 5), servers: [try MapRef(sourceId: "loop").toServer()])]
        )
        #expect(keys.storageIdFor(loop, 3) { _ in loop } == nil)
        #expect(keys.storageIdsOf(loop) { _ in loop }.isEmpty)
    }

    @Test("the author's ski files share the tiles of the built-in ski maps, whose old storage is taken over")
    func skiFiles() throws {
        let files = try fixture("user/COM.109.AQX") +
            fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX").filter { $0.id == "TRACESHIVERNALES" }
        #expect(files.map(\.id) == ["OSMSNOWM", "OSMSKIM", "TRACESHIVERNALES"])
        let takenOver = keys.takenOverByBuiltins
        for file in files {
            #expect(TileSetIdentity.allOf(try builtin(file.id)) == TileSetIdentity.allOf(file), "\(file.id)")
            #expect(storageOf(file, 13) == file.id, "\(file.id)")
            let identity = try #require(TileSetIdentity.allOf(file).first)
            #expect(takenOver[TileSetIdentity.storageIdOf(identity)] == file.id)
        }
    }

    @Test("storage ids are stable and short")
    func storageIds() {
        let id = TileSetIdentity.storageIdOf("example.org/{$z}/{$x}/{$y}#256")
        #expect(id == TileSetIdentity.storageIdOf("example.org/{$z}/{$x}/{$y}#256"))
        #expect(id.count == 18)
        #expect(id.hasPrefix("t-") && id.dropFirst(2).allSatisfy { "0123456789abcdef".contains($0) })
    }
}
