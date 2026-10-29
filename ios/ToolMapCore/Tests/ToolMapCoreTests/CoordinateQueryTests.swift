// Port of the case "a position typed in the search box is a result, a place name is not" of
// core/src/test/kotlin/fr/toolmap/core/search/LocalSearchTest.kt (it needs the port of geo/CoordinateParser.kt).

import Testing
@testable import ToolMapCore

struct CoordinateQueryTests {
    @Test("a position typed in the search box is a result, a place name is not")
    func positionTyped() throws {
        let dd = try #require(CoordinateQuery.resolve("44.8386, 5.5108"))
        #expect(abs(dd.lat - 44.8386) <= 1e-9)
        #expect(abs(dd.lon - 5.5108) <= 1e-9)
        #expect(dd.source == .coordinates)
        let utm = try #require(CoordinateQuery.resolve("31T 452482 E 5011717 N"))
        #expect(abs(utm.lat - 45.2) <= 0.1)
        #expect(CoordinateQuery.resolve("Grand Veymont") == nil)
        #expect(CoordinateQuery.resolve("") == nil)
        #expect(CoordinateQuery.resolve("95, 5") == nil, "an impossible latitude is not a place")
    }
}
