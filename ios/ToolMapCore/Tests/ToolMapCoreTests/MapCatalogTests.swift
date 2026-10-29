// Port of core/src/test/kotlin/fr/toolmap/core/maps/MapCatalogTest.kt.

import Testing
@testable import ToolMapCore

struct MapCatalogTests {
    private func source(
        _ id: String,
        copyright: String? = nil,
        dataSource: String? = nil,
        isLayer: Bool = false,
        type: String? = "topo",
        server: String = "https://example.org/{$z}/{$x}/{$y}.png"
    ) throws -> TileSource {
        let zooms = try ZoomSet.range(0, 10)
        return TileSource(
            id: id,
            name: id,
            type: type,
            isLayer: isLayer,
            copyright: copyright,
            dataSource: dataSource,
            levels: [LevelDef(zooms: zooms, servers: [server])]
        )
    }

    private func ids(_ group: MapGroup) -> [String] {
        group.sources.map(\.id)
    }

    @Test("the shipped maps fall into the OpenStreetMap and IGN groups and the elevation data is hidden")
    func shippedGroups() throws {
        let groups = try MapCatalog.groups(BuiltinSources.load(), isImported: { _ in false })
        #expect(groups.map(\.kind) == [GroupKind.openStreetMap, .ign, .multiZoom, .other])
        try #require(groups.count == 4)
        // OpenSnowMap is OpenStreetMap data; its pistes overlay comes after the base maps.
        #expect(ids(groups[0]) == ["osm", "opentopomap", "OSMSNOWM", "OSMSKIM"])
        // Base maps first, then the overlay.
        #expect(ids(groups[1]) == ["ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes"])
        #expect(ids(groups[2]) == ["ign-topo25k"])
        #expect(groups[2].credit == nil)
        // The Yeti winter tracks (Petzl, ENSG) come from neither provider.
        #expect(ids(groups[3]) == ["TRACESHIVERNALES"])
        #expect(!groups.flatMap(\.sources).contains(where: { $0.id == "terrarium" }))
    }

    @Test("a map the user added goes to its own group even when it is an IGN map")
    func importedGroup() throws {
        let sources = try [source("IGN_MAPS", copyright: "IGN"), source("osm")]
        let groups = MapCatalog.groups(sources, isImported: { $0 == "IGN_MAPS" })
        #expect(groups.map(\.kind) == [GroupKind.imported, .other])
    }

    @Test("a map made of other maps goes to the multi-zoom group even when the user made it")
    func multiZoomGroup() throws {
        let reference = try MapRef(sourceId: "osm")
        let multiZoom = try source("mz-rando", server: reference.toServer())
        let kind = MapCatalog.kindOf(multiZoom, isImported: { _ in true })
        #expect(kind == .multiZoom)
    }

    @Test("a package of tiles with no server is local")
    func localGroup() throws {
        let local = try source("pack", server: LevelDef.localOnlyServer)
        let kind = MapCatalog.kindOf(local, isImported: { _ in true })
        #expect(kind == .local)
    }

    @Test("IGN is recognised as a word and not inside another word")
    func ignWord() throws {
        let a = try source("a", copyright: "(c) IGN 2026")
        let b = try source("b", dataSource: "https://data.geopf.fr")
        let c = try source("c", copyright: "Designed by Virgin")
        let provided: (String) -> Bool = { _ in false }
        #expect(MapCatalog.kindOf(a, isImported: provided) == .ign)
        #expect(MapCatalog.kindOf(b, isImported: provided) == .ign)
        #expect(MapCatalog.kindOf(c, isImported: provided) == .other)
    }

    @Test("the credit line shows the owner and the site of a provider group only")
    func creditLine() throws {
        let groups = try MapCatalog.groups(BuiltinSources.load(), isImported: { _ in false })
        let ign = try #require(groups.first(where: { $0.kind == .ign }))
        #expect(ign.credit == "© IGN · data.geopf.fr")
        let somebody = try source("x", copyright: "Somebody")
        let other = MapGroup(kind: .other, sources: [somebody])
        #expect(other.credit == nil)
    }

    @Test("a provided map hidden from the list comes back only when hidden maps are shown")
    func hiddenMaps() throws {
        let sources = try BuiltinSources.load()
        let hidden: Set<String> = ["ign-plan", "ign-pentes"]
        let provided: (String) -> Bool = { _ in false }
        let shown = MapCatalog.groups(sources, hidden: hidden, showHidden: false, isImported: provided)
        let ign = try #require(shown.first(where: { $0.kind == .ign }))
        #expect(ids(ign) == ["ign-maps", "ign-scan25", "ign-ortho"])
        let all = MapCatalog.groups(sources, hidden: hidden, showHidden: true, isImported: provided)
        let unfiltered = MapCatalog.groups(sources, isImported: provided)
        #expect(all == unfiltered)
        #expect(MapCatalog.isHidden("ign-plan", hidden: hidden, isImported: provided))
    }

    @Test("a map the user added is never hidden")
    func addedNeverHidden() throws {
        let mine = try source("mine", copyright: "IGN")
        let added: (String) -> Bool = { _ in true }
        let groups = MapCatalog.groups([mine], hidden: ["mine"], showHidden: false, isImported: added)
        #expect(groups.flatMap(\.sources).map(\.id) == ["mine"])
        #expect(!MapCatalog.isHidden("mine", hidden: ["mine"], isImported: added))
    }

    @Test("the search looks at the name and the section title, without case or accents")
    func search() throws {
        let groups = try MapCatalog.groups(BuiltinSources.load(), isImported: { _ in false })
        let titles: [GroupKind: String] = [
            .openStreetMap: "Cartes OpenStreetMap",
            .ign: "France · Cartes officielles IGN",
            .multiZoom: "Cartes multi-zoom",
            .other: "Autres cartes",
        ]
        let title: (GroupKind) -> String = { titles[$0] ?? "" }
        func found(_ query: String) -> [String] {
            MapCatalog.search(groups, query, title: title).flatMap { $0.sources.map(\.id) }
        }
        // "ign" is in the IGN section title: all its maps, plus the multi-zoom one named after IGN.
        #expect(found("ign") == ["ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes", "ign-topo25k"])
        #expect(found("SCAN 25") == ["ign-scan25"])
        #expect(found("topo openstreetmap") == ["opentopomap"])
        #expect(found("officiélles") == ["ign-maps", "ign-scan25", "ign-plan", "ign-ortho", "ign-pentes"])
        let blank = MapCatalog.search(groups, "  ", title: title)
        #expect(blank == groups)
        #expect(found("zzz").isEmpty)
    }

    @Test("copyright and host cleaning")
    func cleaning() {
        #expect(MapCatalog.cleanCopyright(" (c) IGN ") == "IGN")
        #expect(MapCatalog.cleanCopyright("© OpenStreetMap") == "OpenStreetMap")
        #expect(MapCatalog.cleanCopyright("(c)") == nil)
        #expect(MapCatalog.hostOf("https://www.openstreetmap.org/copyright") == "openstreetmap.org")
        #expect(MapCatalog.hostOf("not an address") == nil)
    }

    @Test("matches finds every word, whatever the case and the accents, and nothing for an empty query")
    func matches() {
        #expect(MapCatalog.matches("Pistes de ski (Waymarked Trails)", "ski PISTES"))
        #expect(MapCatalog.matches("IGN Plan v2", "plan ign"))
        #expect(MapCatalog.matches("Relief ombré", "OMBRE"))
        #expect(!MapCatalog.matches("IGN Plan v2", "plan osm"))
        #expect(!MapCatalog.matches("IGN Plan v2", "  "))
    }
}
