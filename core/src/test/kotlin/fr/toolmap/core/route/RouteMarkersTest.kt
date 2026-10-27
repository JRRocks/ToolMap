package fr.toolmap.core.route

import kotlin.test.Test
import kotlin.test.assertEquals

class RouteMarkersTest {
    private val a = RouteStop(45.0, 6.0)
    private val b = RouteStop(45.01, 6.0, StopKind.NAMED, "Refuge", "Lodge")
    private val c = RouteStop(45.02, 6.01)

    private fun plan(vararg stops: RouteStop): RoutePlan =
        RoutePlan(
            stops.toList(),
            stops.toList().zipWithNext {
                x,
                y,
                ->
                RouteLeg.straight(x, y, RoutingProfiles.HIKING)
            },
        )

    @Test
    fun `a saved route shows its start, its named stops and its end`() {
        val icons = RouteMarkers.saved(-3, plan(a, b, c)).map { it.iconId }
        assertEquals(RouteMarkers.LETTER_PREFIX + "A", icons.first())
        assertEquals(RouteMarkers.LETTER_PREFIX + "C", icons.last())
        assertEquals(3, icons.size)
    }

    @Test
    fun `a loop ends on « A » alone, without a second letter over it`() {
        val markers = RouteMarkers.saved(-3, plan(a, b, c, a.discreet()))
        assertEquals(listOf(RouteMarkers.LETTER_PREFIX + "A", null), markers.map { it.iconId })
        assertEquals(RouteMarkers.START_COLOR, markers.first().colorArgb)
    }
}
