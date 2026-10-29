// Port of core/src/test/kotlin/fr/toolmap/core/track/WaypointNamesTest.kt.

import Testing
@testable import ToolMapCore

struct WaypointNamesTests {
    @Test("the first waypoint is number 1")
    func first() {
        #expect(WaypointNames.nextNumber([], "Waypoint") == 1)
        #expect(WaypointNames.nextNumber([nil, "Refuge", "Source"], "Waypoint") == 1)
    }

    @Test("the number follows the highest one, gaps are not refilled")
    func highest() {
        #expect(WaypointNames.nextNumber(["Waypoint 3", "Waypoint 11", "Waypoint 7"], "Waypoint") == 12)
    }

    @Test("case and spaces are ignored, other names that start alike are not counted")
    func caseAndSpaces() {
        let names: [String?] = [" waypoint  4 ", "Waypoint 9 bis", "Waypoints 20", "Waypoint", "Waypoint 99999999999"]
        #expect(WaypointNames.nextNumber(names, "Waypoint") == 5)
    }

    @Test("a prefix with regex characters is taken literally")
    func literalPrefix() {
        #expect(WaypointNames.nextNumber(["Pt. (1) 2", "Ptx (1) 8"], "Pt. (1)") == 3)
    }
}
