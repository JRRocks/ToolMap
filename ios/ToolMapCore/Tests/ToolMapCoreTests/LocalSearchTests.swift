// Port of core/src/test/kotlin/fr/toolmap/core/search/LocalSearchTest.kt (LocalSearchTest and SearchMergeTest; the
// case of CoordinateQuery is in CoordinateQueryTests.swift) and of search/OsmTypesTest.kt.

import Testing
@testable import ToolMapCore

struct LocalSearchTests {
    private func item(_ id: Int64, _ name: String?, _ note: String? = nil, _ kind: LibraryKind = .waypoint) -> LibraryItem {
        LibraryItem(kind: kind, id: id, name: name, note: note, lat: 45.0 + Double(id) / 100.0, lon: 6.0)
    }

    private var library: [LibraryItem] {
        [
            item(1, "Refuge de l'Écrin", "Gardé en été\nRéservation obligatoire"),
            item(2, "Col du Lautaret"),
            item(3, "Sommet des Écrins", nil, .track),
            item(4, "Pique-nique", "près du refuge de l'Écrin"),
            item(5, nil, "sans nom"),
            item(6, "   "),
            item(7, "écrin", nil, .route),
        ]
    }

    private func names(_ query: String) -> [Int64] {
        LocalSearch.search(query, library).compactMap(\.libraryId)
    }

    @Test("accents and case do not matter in either direction")
    func accentsAndCase() {
        #expect(names("ECRIN") == [7, 1, 3, 4], "exact name, then prefix, then word, then note")
        #expect(names("ecrin") == names("Écrin"))
        #expect(names("LAUTARET") == [2])
    }

    @Test("every word typed must be found, in the name or in the note")
    func everyWord() {
        #expect(names("refuge ecrin") == [1, 4], "the name of the first, the note of the second")
        #expect(names("refuge gardé") == [1], "one word from the name and one from the note")
        #expect(names("reservation obligatoire") == [1], "the note is searched too")
        #expect(names("refuge lautaret").isEmpty)
    }

    @Test("ranking puts the exact name first, then names starting with it, then words, then notes")
    func ranking() {
        let items = [
            item(1, "Pont du col"),
            item(2, "Col"),
            item(3, "Col de la Croix"),
            item(4, "Sentier", "mène au col"),
            item(5, "Le col rouge"),
        ]
        let order = LocalSearch.search("col", items).compactMap(\.libraryId)
        #expect(order == [2, 3, 5, 1, 4])
    }

    @Test("unnamed items and blank queries give nothing")
    func nothing() {
        #expect(names("").isEmpty)
        #expect(names("   ").isEmpty)
        #expect(LocalSearch.search("sans nom", library).isEmpty, "an unnamed item cannot be shown")
    }

    @Test("a result carries what the screen needs to open the item")
    func resultContent() throws {
        let line = LibraryItem(
            kind: .track,
            id: 9,
            name: "Traversée",
            note: "Trois jours\nEn autonomie",
            lat: 45.1,
            lon: 6.1,
            bounds: Bounds(minX: 6.0, minY: 45.0, maxX: 6.2, maxY: 45.2)
        )
        let results = LocalSearch.search("traversee", [line])
        try #require(results.count == 1)
        let result = results[0]
        #expect(result.title == "Traversée")
        #expect(result.subtitle == "Trois jours", "only the first line of the note")
        #expect(result.libraryKind == .track)
        #expect(result.libraryId == 9)
        #expect(result.source == .library)
        #expect(result.bounds == Bounds(minX: 6.0, minY: 45.0, maxX: 6.2, maxY: 45.2))
        #expect(LocalSearch.search("x", [line]).first == nil)
    }

    @Test("the number of results is limited")
    func limited() {
        let many = (0 ..< 100).map { item(Int64($0), "Point \($0)") }
        #expect(LocalSearch.search("point", many).count == 30)
        #expect(LocalSearch.search("point", many, limit: 5).count == 5)
    }

    @Test("fold removes accents, case and runs of spaces")
    func fold() {
        #expect(SearchText.fold("  À   La  GARE ") == "a la gare")
        #expect(SearchText.fold("SSss") == "ssss")
        #expect(SearchText.tokens("Col  du Lautaret") == ["col", "du", "lautaret"])
    }
}

struct SearchMergeTests {
    private func result(_ title: String, _ lat: Double, _ lon: Double, _ source: SearchSource) -> SearchResult {
        SearchResult(title: title, subtitle: "", lat: lat, lon: lon, source: source)
    }

    @Test("the same place from two sources is shown once, the first source wins")
    func samePlace() {
        let ign = result("le Grand Veymont", 44.869704, 5.526604, .ign)
        let osm = result("Grand Veymont", 44.8697393, 5.5267039, .osm)
        let merged = SearchMerge.merge([[ign], [osm]], limit: 10)
        #expect(merged == [ign])
    }

    @Test("same name far apart or near with another name are different places")
    func differentPlaces() {
        let a = result("Col de la Croix", 45.0, 6.0, .ign)
        let farther = result("Col de la Croix", 45.5, 6.0, .osm)
        let other = result("Refuge", 45.0, 6.0, .osm)
        #expect(SearchMerge.merge([[a], [farther, other]], limit: 10).count == 3)
    }

    @Test("articles and accents do not make two names different")
    func articles() {
        let a = result("L'Écrin", 45.0, 6.0, .ign)
        let b = result("Ecrin", 45.0001, 6.0, .osm)
        #expect(SearchMerge.merge([[a], [b]], limit: 10) == [a])
    }

    @Test("the list is cut at the limit and keeps the order of the groups")
    func cutAtLimit() {
        let one = (0 ..< 5).map { result("A\($0)", 45.0 + Double($0), 6.0, .ign) }
        let two = (0 ..< 5).map { result("B\($0)", 45.0 + Double($0), 7.0, .osm) }
        let merged = SearchMerge.merge([one, two], limit: 7)
        #expect(merged.map(\.title) == ["A0", "A1", "A2", "A3", "A4", "B0", "B1"])
    }
}

struct OsmTypesTests {
    @Test("the known types of OpenStreetMap are said in French")
    func known() {
        #expect(OsmTypes.french("castle") == "Château fort")
        #expect(OsmTypes.french("village") == "Village")
        #expect(OsmTypes.french("peak") == "Sommet")
        #expect(OsmTypes.french("alpine_hut") == "Refuge")
    }

    @Test("an unknown type is shown as it comes, readable")
    func unknown() {
        #expect(OsmTypes.french("bird_hide") == "Bird hide")
        #expect(OsmTypes.french(" Peak ") == "Sommet")
        #expect(OsmTypes.french("") == nil)
        #expect(OsmTypes.french(nil) == nil)
    }
}
