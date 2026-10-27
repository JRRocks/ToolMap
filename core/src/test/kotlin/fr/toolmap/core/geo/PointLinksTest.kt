package fr.toolmap.core.geo

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class PointLinksTest {
    @Test
    fun `the generic link is a pin at the position, with a point as decimal separator`() {
        assertEquals("geo:48.500000,2.250000?q=48.500000,2.250000", PointLinks.geo(48.5, 2.25))
    }

    @Test
    fun `the decimals stay points on a phone set to French`() {
        val phone = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            assertEquals("geo:-33.868800,151.209300?q=-33.868800,151.209300", PointLinks.geo(-33.8688, 151.2093))
        } finally {
            Locale.setDefault(phone)
        }
    }

    @Test
    fun `Waze gets its own link, which starts the guidance to the position`() {
        assertEquals(
            PointTarget("https://waze.com/ul?ll=48.500000,2.250000&navigate=yes", "com.waze"),
            PointLinks.target(PointLinks.WAZE_PACKAGE, 48.5, 2.25),
        )
    }

    @Test
    fun `another app gets the generic link, and the system's choice gets it without a package`() {
        assertEquals(
            PointTarget("geo:48.500000,2.250000?q=48.500000,2.250000", "org.osmand"),
            PointLinks.target("org.osmand", 48.5, 2.25),
        )
        assertEquals(
            PointTarget("geo:48.500000,2.250000?q=48.500000,2.250000", null),
            PointLinks.target(null, 48.5, 2.25),
        )
    }
}
