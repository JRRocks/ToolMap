// Port of core/src/test/kotlin/fr/toolmap/core/track/WaypointSymbolsTest.kt. The last Kotlin test (the 33 activities
// of `record/TrackActivity`) belongs to the recorder, not ported here.

import Testing
@testable import ToolMapCore

struct WaypointSymbolsTests {
    private let icons = WaypointCatalog.categories.flatMap(\.icons)

    @Test("unknown or missing names get the default waypoint")
    func defaults() {
        #expect(WaypointSymbols.colorOf(nil) == WaypointSymbols.defaultColor)
        #expect(WaypointSymbols.colorOf("Something Else") == WaypointSymbols.defaultColor)
        #expect(WaypointSymbols.canonical("Whatever") == "Waypoint")
        #expect(WaypointSymbols.iconOf(nil).id == WaypointSymbols.defaultId)
    }

    @Test("the catalogue has the 16 categories and every icon is unique")
    func catalogue() {
        #expect(WaypointCatalog.categories.count == 16)
        #expect(icons.count >= 300)
        #expect(Set(icons.map(\.id)).count == icons.count)
        #expect(Set(icons.map { $0.key.lowercased() }).count == icons.count)
        #expect(icons.allSatisfy { !$0.category.isEmpty })
    }

    @Test("every key resolves to its own icon, case and spaces ignored")
    func keys() {
        for icon in icons {
            #expect(WaypointSymbols.iconOf(icon.key).id == icon.id)
            #expect(WaypointSymbols.canonical("  " + icon.key.uppercased() + " ") == icon.key)
        }
    }

    @Test("old Garmin names point at the equivalent icon")
    func aliases() {
        #expect(WaypointSymbols.iconOf(" summit ").id == "peak")
        #expect(WaypointSymbols.canonical("SUMMIT") == "Peak")
        #expect(WaypointSymbols.iconOf("Campground").id == "camping")
        for (alias, id) in WaypointCatalog.aliases {
            #expect(WaypointSymbols.iconById(id) != nil, "alias \(alias) -> \(id)")
            #expect(WaypointSymbols.iconOf(alias).id == id)
        }
    }

    @Test("an icon has the colour of its category")
    func colour() {
        #expect(WaypointSymbols.colorOf("Peak") == Int32(bitPattern: 0xFF5A_7A00))
    }
}
