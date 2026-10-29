// Port of core/src/test/kotlin/fr/toolmap/core/source/MultiZoomDraftTest.kt.

import Testing
@testable import ToolMapCore

struct MultiZoomDraftTests {
    private let builtins: [TileSource]
    private let byId: [String: TileSource]

    init() throws {
        builtins = try BuiltinSources.load()
        var table: [String: TileSource] = [:]
        for source in builtins {
            table[source.id] = source
        }
        byId = table
    }

    private func fixture(_ name: String) throws -> [TileSource] {
        try AqxParser.parse(Fixtures.text("aqx/\(name)")).file.sources
    }

    private func kinds(
        _ draft: MultiZoomDraft,
        _ selfId: String? = nil,
        _ table: [String: TileSource]? = nil
    ) -> [DraftIssue.Kind] {
        let maps = table ?? byId
        return draft.issues(selfId, { maps[$0] }).map(\.kind)
    }

    @Test("the personal multi-zoom file becomes bands over the built-in maps")
    func personalFile() throws {
        let file = try #require(try fixture("user/USR.PERSONNAL_MULTIZOOM.AQX").first)
        let draft = MultiZoomDraft.derivedFrom(file, builtins + [file], "Rando")
        let expected = [
            ZoomBand(1, 8, "osm"),
            ZoomBand(9, 11, "opentopomap"),
            ZoomBand(12, 16, "ign-scan25"),
            ZoomBand(18, 18, "osm"),
            ZoomBand(19, 19, "ign-ortho"),
        ]
        #expect(draft.bands == expected)
        // Zoom 17 is in no level of the file: it stays a gap, filled from the nearest band.
        #expect(kinds(draft) == [.gap])
        let maps = byId
        #expect(draft.canSave(nil, { maps[$0] }))
    }

    @Test("holes in a zoom list become forced zooms, as AlpineQuest shows them")
    func holesForced() throws {
        // Ski touring file: zoom values 3,5,7-16 of IGN Cartes.
        let sources = try fixture("user/USR.IGNPENTESYETI_TRANSITOIRE_2024_SANS_GEOKEY.AQX")
        let top25 = try #require(sources.first(where: { $0.id == "IGN_TOP25" }))
        let draft = MultiZoomDraft.derivedFrom(top25, builtins + [top25], "Top 25")
        let expected = [
            ZoomBand(3, 3, "ign-maps"),
            ZoomBand(4, 4, "ign-maps", 3),
            ZoomBand(5, 5, "ign-maps"),
            ZoomBand(6, 6, "ign-maps", 5),
            ZoomBand(7, 16, "ign-maps"),
        ]
        #expect(draft.bands == expected)
        #expect(kinds(draft).isEmpty)
    }

    @Test("a level no catalogue map serves points to the imported map itself")
    func importedItself() throws {
        // OpenSnowMap is built in since ADR-069: the same file on another server is served by no catalogue map.
        let snow = try #require(try fixture("user/COM.109.AQX").first)
        let elsewhere = snow.withFields { fields in
            fields.id = "NEIGE"
            fields.levels = snow.levels.map { level in
                level.withFields { $0.servers = ["https://snow.example.org/{$z}/{$x}/{$y}.png"] }
            }
        }
        let draft = MultiZoomDraft.derivedFrom(elsewhere, builtins + [elsewhere], "Neige")
        #expect(draft.bands.allSatisfy { $0.mapId == "NEIGE" })
        // The file itself now points to the built-in map that serves its tiles.
        let own = MultiZoomDraft.derivedFrom(snow, builtins + [snow], "Neige")
        #expect(own.bands.allSatisfy { $0.mapId == "OSMSNOWM" })
    }

    @Test("a multi-zoom map round-trips through its draft")
    func roundTrip() throws {
        let topo25 = try #require(byId["ign-topo25k"])
        let draft = try #require(MultiZoomDraft.of(topo25))
        let expected = [
            ZoomBand(1, 12, "ign-maps"),
            ZoomBand(13, 14, "ign-maps", 15),
            ZoomBand(15, 16, "ign-maps"),
            ZoomBand(17, 18, "ign-maps", 16),
        ]
        #expect(draft.bands == expected)
        let maps = byId
        let saved = try draft.toSource("ign-topo25k", { maps[$0] })
        #expect(saved.levels == topo25.levels)
        #expect(saved.copyright == "IGN")
        #expect(saved.type == "topo")
        let osm = try #require(byId["osm"])
        #expect(MultiZoomDraft.of(osm) == nil)
    }

    @Test("blocking problems prevent saving")
    func blocking() {
        let ok = MultiZoomDraft("Ma carte", [ZoomBand(1, 10, "osm"), ZoomBand(11, 16, "ign-scan25")])
        let maps = byId
        #expect(ok.canSave("mz-ma-carte", { maps[$0] }))
        var unnamed = ok
        unnamed.name = " "
        #expect(kinds(unnamed) == [.noName])
        #expect(kinds(MultiZoomDraft("Ma carte", [])) == [.noBand])
        #expect(kinds(MultiZoomDraft("Ma carte", [ZoomBand(1, 12, "osm"), ZoomBand(11, 16, "ign-scan25")])) == [.overlap])
        #expect(kinds(MultiZoomDraft("Ma carte", [ZoomBand(9, 3, "osm")])) == [.badRange])
        #expect(kinds(MultiZoomDraft("Ma carte", [ZoomBand(1, 3, "gone")])) == [.unknownMap])
        #expect(kinds(MultiZoomDraft("Ma carte", [ZoomBand(1, 10, "ign-maps", 15)])) == [.forcedTooFar])
        let far = MultiZoomDraft("Ma carte", [ZoomBand(11, 14, "ign-maps", 15)])
        #expect(!far.issues(nil, { maps[$0] }).contains(where: { $0.kind.blocking }))
    }

    @Test("a band showing a map that shows this one is a loop")
    func loop() throws {
        let maps = byId
        let mine = try MultiZoomDraft("A", [ZoomBand(1, 18, "ign-maps")]).toSource("mz-a", { maps[$0] })
        let other = try MultiZoomDraft("B", [ZoomBand(1, 18, "mz-a")]).toSource("mz-b", { maps[$0] })
        var table = byId
        table[mine.id] = mine
        table[other.id] = other
        let edited = MultiZoomDraft("A", [ZoomBand(1, 18, "mz-b")])
        #expect(kinds(edited, "mz-a", table) == [.loop])
        #expect(kinds(MultiZoomDraft("A", [ZoomBand(1, 18, "mz-a")]), "mz-a", table) == [.loop])
        // A new map (no id yet) cannot be referenced by anything.
        #expect(kinds(edited, nil, table).isEmpty)
    }

    @Test("informative problems do not block")
    func informative() {
        let draft = MultiZoomDraft("Info", [ZoomBand(1, 3, "ign-scan25"), ZoomBand(17, 19, "osm", 20)])
        #expect(kinds(draft) == [.notServed, .gap, .forcedNotServed])
        let maps = byId
        #expect(draft.canSave(nil, { maps[$0] }))
    }

    @Test("new ids are readable and free")
    func newIds() {
        #expect(MultiZoomDraft.newId("Rando été", taken: { _ in false }) == "mz-rando-ete")
        #expect(MultiZoomDraft.newId("Rando été", taken: { $0 == "mz-rando-ete" }) == "mz-rando-ete-2")
        #expect(MultiZoomDraft.newId("!!!", taken: { _ in false }) == "mz-carte")
    }
}
