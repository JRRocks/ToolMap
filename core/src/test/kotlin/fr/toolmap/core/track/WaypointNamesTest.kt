package fr.toolmap.core.track

import kotlin.test.Test
import kotlin.test.assertEquals

class WaypointNamesTest {
    @Test
    fun `the first waypoint is number 1`() {
        assertEquals(1, WaypointNames.nextNumber(emptyList(), "Waypoint"))
        assertEquals(1, WaypointNames.nextNumber(listOf(null, "Refuge", "Source"), "Waypoint"))
    }

    @Test
    fun `the number follows the highest one, gaps are not refilled`() {
        assertEquals(12, WaypointNames.nextNumber(listOf("Waypoint 3", "Waypoint 11", "Waypoint 7"), "Waypoint"))
    }

    @Test
    fun `case and spaces are ignored, other names that start alike are not counted`() {
        val names = listOf(" waypoint  4 ", "Waypoint 9 bis", "Waypoints 20", "Waypoint", "Waypoint 99999999999")
        assertEquals(5, WaypointNames.nextNumber(names, "Waypoint"))
    }

    @Test
    fun `a prefix with regex characters is taken literally`() {
        assertEquals(3, WaypointNames.nextNumber(listOf("Pt. (1) 2", "Ptx (1) 8"), "Pt. (1)"))
    }
}
