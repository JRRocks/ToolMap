// Port of core/src/test/kotlin/fr/toolmap/core/track/LibraryOrderTest.kt.

import Testing
@testable import ToolMapCore

struct LibraryOrderTests {
    private let lauvitel = LibraryKey(kind: .track, name: "Tour du lac Lauvitel", timeMillis: 1800, distanceMeters: 11900.0)
    private let muzelle = LibraryKey(kind: .route, name: "Col de la Muzelle", timeMillis: 1500, distanceMeters: 14200.0)
    private let refuge = LibraryKey(kind: .waypoint, name: "Refuge de la Muzelle", timeMillis: 1500, distanceMeters: nil)
    private let etang = LibraryKey(kind: .track, name: "Étang de Berre", timeMillis: 1200, distanceMeters: 7400.0)
    private let unnamed = LibraryKey(kind: .track, name: nil, timeMillis: 2000, distanceMeters: 3000.0)

    private var all: [LibraryKey] { [refuge, etang, unnamed, lauvitel, muzelle] }

    @Test("by date the most recent comes first")
    func byDate() {
        #expect(LibraryOrder.sorted(all, by: .date) == [unnamed, lauvitel, refuge, muzelle, etang])
        #expect(all.sorted(by: LibraryOrder.comparator(.date)) == [unnamed, lauvitel, refuge, muzelle, etang])
    }

    @Test("by name accents and case are ignored and unnamed items go last")
    func byName() {
        // « Étang » sorts with the E, before « Refuge », not after every unaccented name.
        #expect(LibraryOrder.sorted(all, by: .name) == [muzelle, etang, refuge, lauvitel, unnamed])
    }

    @Test("by distance the longest comes first and waypoints go last")
    func byDistance() {
        #expect(LibraryOrder.sorted(all, by: .distance) == [muzelle, lauvitel, etang, unnamed, refuge])
    }

    @Test("the filter keeps one kind of item, or all of them")
    func filter() {
        for kind in LibraryKind.allCases {
            #expect(LibraryOrder.accepts(.all, kind))
        }
        #expect(LibraryOrder.accepts(.tracks, .track))
        #expect(!LibraryOrder.accepts(.tracks, .route))
        #expect(LibraryOrder.accepts(.routes, .route))
        #expect(!LibraryOrder.accepts(.routes, .waypoint))
        #expect(LibraryOrder.accepts(.waypoints, .waypoint))
        #expect(!LibraryOrder.accepts(.waypoints, .track))
    }

    @Test("the search wants every word, case and accents ignored")
    func search() {
        #expect(LibraryOrder.matches("Étang de Berre", "etang"))
        #expect(LibraryOrder.matches("Col de la Muzelle", "MUZELLE col"))
        #expect(!LibraryOrder.matches("Col de la Muzelle", "muzelle refuge"))
        #expect(!LibraryOrder.matches(nil, "col"))
        #expect(LibraryOrder.matches(nil, "  "))
    }

    @Test("the sort and the filter are kept with the settings")
    func settings() throws {
        #expect(AppSettings().library == LibraryViewSettings(sort: .date, filter: .all))
        let s = AppSettings(library: LibraryViewSettings(sort: .distance, filter: .waypoints))
        let text = try AppSettingsCodec.encode(s)
        #expect(AppSettingsCodec.decodeOrDefault(text) == s)
    }
}
