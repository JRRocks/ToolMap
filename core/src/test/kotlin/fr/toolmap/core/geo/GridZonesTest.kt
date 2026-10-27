package fr.toolmap.core.geo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GridZonesTest {
    private fun zone(name: String): GridZone {
        val z = name.dropLast(1).toInt()
        return assertNotNull(GridZones.get(z, name.last()), name)
    }

    @Test
    fun `60 zones by 20 bands, less 32X, 34X and 36X`() {
        assertEquals(60 * 20 - 3, GridZones.all.size)
        assertNull(GridZones.get(32, 'X'))
        assertNull(GridZones.get(34, 'X'))
        assertNull(GridZones.get(36, 'X'))
        assertNull(GridZones.get(61, 'T'))
        assertNull(GridZones.get(31, 'I'))
        assertNull(GridZones.get(31, 'Y'))
    }

    @Test
    fun `each band is paved from 180 W to 180 E without a gap or an overlap`() {
        for ((band, zones) in GridZones.all.groupBy { it.band }) {
            val sorted = zones.sortedBy { it.west }
            assertEquals(-180.0, sorted.first().west, 1e-12, "band $band")
            assertEquals(180.0, sorted.last().east, 1e-12, "band $band")
            for ((a, b) in sorted.zipWithNext()) assertEquals(a.east, b.west, 1e-12, "band $band: ${a.name} ${b.name}")
            assertTrue(zones.all { it.south == zones.first().south && it.north == zones.first().north })
        }
    }

    @Test
    fun `the exceptions of Norway and Svalbard`() {
        assertEquals(0.0 to 3.0, zone("31V").let { it.west to it.east })
        assertEquals(3.0 to 12.0, zone("32V").let { it.west to it.east })
        assertEquals(12.0 to 18.0, zone("33V").let { it.west to it.east })
        assertEquals(0.0 to 6.0, zone("31U").let { it.west to it.east })
        assertEquals(0.0 to 9.0, zone("31X").let { it.west to it.east })
        assertEquals(9.0 to 21.0, zone("33X").let { it.west to it.east })
        assertEquals(21.0 to 33.0, zone("35X").let { it.west to it.east })
        assertEquals(33.0 to 42.0, zone("37X").let { it.west to it.east })
        assertEquals(42.0 to 48.0, zone("38X").let { it.west to it.east })
        assertEquals(72.0 to 84.0, zone("31X").let { it.south to it.north })
        assertEquals(-80.0 to -72.0, zone("31C").let { it.south to it.north })
    }

    @Test
    fun `band X keeps its north edge, the others leave it to the next band`() {
        assertTrue(zone("31X").contains(84.0, 1.0))
        assertFalse(zone("31W").contains(72.0, 1.0))
        assertTrue(zone("31X").contains(72.0, 1.0))
        assertEquals("31X", GridZones.of(84.0, 1.0)?.name)
        assertNull(GridZones.of(84.01, 1.0))
        assertNull(GridZones.of(-80.01, 1.0))
    }

    @Test
    fun `the zones a screen covers`() {
        // Pontcharra, on the 6 E junction of the user's screenshots.
        assertEquals(listOf("31T", "32T"), GridZones.covering(Bounds(5.9, 45.3, 6.1, 45.5)).map { it.name })
        // West of Bergen, 32V reaches 3 E.
        assertEquals(listOf("32V"), GridZones.covering(Bounds(4.5, 60.0, 6.0, 61.0)).map { it.name })
        // Across the equator and a band edge.
        assertEquals(listOf("32M", "32N"), GridZones.covering(Bounds(6.2, -0.5, 6.8, 0.5)).map { it.name })
        assertEquals(listOf("32T", "32U"), GridZones.covering(Bounds(6.2, 47.5, 6.8, 48.5)).map { it.name })
        // Beyond the grid.
        assertTrue(GridZones.covering(Bounds(0.0, 85.0, 1.0, 86.0)).isEmpty())
    }
}
