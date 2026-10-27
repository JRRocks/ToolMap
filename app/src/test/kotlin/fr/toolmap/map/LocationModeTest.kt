package fr.toolmap.map

import kotlin.test.Test
import kotlin.test.assertEquals

class LocationModeTest {
    @Test
    fun `a long press on the position button cycles free, following, following with the compass`() {
        assertEquals(LocationMode.FOLLOW, LocationMode.FREE.next())
        assertEquals(LocationMode.FOLLOW_COMPASS, LocationMode.FOLLOW.next())
        assertEquals(LocationMode.FREE, LocationMode.FOLLOW_COMPASS.next())
    }
}
