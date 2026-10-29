// Port of core/src/test/kotlin/fr/toolmap/core/geo/GridZonesTest.kt.

import Testing
@testable import ToolMapCore

struct GridZonesTests {
    private func zone(_ name: String) throws -> GridZone {
        let number = try #require(Int(name.dropLast()))
        let band = try #require(name.last)
        return try #require(GridZones.get(number, band), "\(name)")
    }

    @Test("60 zones by 20 bands, less 32X, 34X and 36X")
    func count() {
        #expect(GridZones.all.count == 60 * 20 - 3)
        #expect(GridZones.get(32, "X") == nil)
        #expect(GridZones.get(34, "X") == nil)
        #expect(GridZones.get(36, "X") == nil)
        #expect(GridZones.get(61, "T") == nil)
        #expect(GridZones.get(31, "I") == nil)
        #expect(GridZones.get(31, "Y") == nil)
    }

    @Test("each band is paved from 180 W to 180 E without a gap or an overlap")
    func paved() throws {
        let byBand = Dictionary(grouping: GridZones.all, by: { $0.band })
        for (band, zones) in byBand {
            let sorted = zones.sorted { $0.west < $1.west }
            let first = try #require(sorted.first)
            let last = try #require(sorted.last)
            #expect(abs(first.west - -180.0) <= 1e-12, "band \(band)")
            #expect(abs(last.east - 180.0) <= 1e-12, "band \(band)")
            for (a, b) in zip(sorted, sorted.dropFirst()) {
                #expect(abs(a.east - b.west) <= 1e-12, "band \(band): \(a.name) \(b.name)")
            }
            #expect(zones.allSatisfy { $0.south == zones[0].south && $0.north == zones[0].north })
        }
    }

    @Test("the exceptions of Norway and Svalbard")
    func exceptions() throws {
        func edges(_ name: String) throws -> [Double] {
            let z = try zone(name)
            return [z.west, z.east]
        }
        #expect(try edges("31V") == [0.0, 3.0])
        #expect(try edges("32V") == [3.0, 12.0])
        #expect(try edges("33V") == [12.0, 18.0])
        #expect(try edges("31U") == [0.0, 6.0])
        #expect(try edges("31X") == [0.0, 9.0])
        #expect(try edges("33X") == [9.0, 21.0])
        #expect(try edges("35X") == [21.0, 33.0])
        #expect(try edges("37X") == [33.0, 42.0])
        #expect(try edges("38X") == [42.0, 48.0])
        let x31 = try zone("31X")
        #expect([x31.south, x31.north] == [72.0, 84.0])
        let c31 = try zone("31C")
        #expect([c31.south, c31.north] == [-80.0, -72.0])
    }

    @Test("band X keeps its north edge, the others leave it to the next band")
    func northEdge() throws {
        #expect(try zone("31X").contains(84.0, 1.0))
        #expect(try !zone("31W").contains(72.0, 1.0))
        #expect(try zone("31X").contains(72.0, 1.0))
        #expect(GridZones.of(84.0, 1.0)?.name == "31X")
        #expect(GridZones.of(84.01, 1.0) == nil)
        #expect(GridZones.of(-80.01, 1.0) == nil)
    }

    @Test("the zones a screen covers")
    func covering() {
        // Pontcharra, on the 6 E junction of the user's screenshots.
        #expect(GridZones.covering(Bounds(minX: 5.9, minY: 45.3, maxX: 6.1, maxY: 45.5)).map(\.name) == ["31T", "32T"])
        // West of Bergen, 32V reaches 3 E.
        #expect(GridZones.covering(Bounds(minX: 4.5, minY: 60.0, maxX: 6.0, maxY: 61.0)).map(\.name) == ["32V"])
        // Across the equator and a band edge.
        #expect(GridZones.covering(Bounds(minX: 6.2, minY: -0.5, maxX: 6.8, maxY: 0.5)).map(\.name) == ["32M", "32N"])
        #expect(GridZones.covering(Bounds(minX: 6.2, minY: 47.5, maxX: 6.8, maxY: 48.5)).map(\.name) == ["32T", "32U"])
        // Beyond the grid.
        #expect(GridZones.covering(Bounds(minX: 0.0, minY: 85.0, maxX: 1.0, maxY: 86.0)).isEmpty)
    }
}
