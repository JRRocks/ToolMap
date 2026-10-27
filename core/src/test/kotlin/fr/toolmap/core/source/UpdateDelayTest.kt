package fr.toolmap.core.source

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UpdateDelayTest {
    @Test
    fun `units convert to milliseconds`() {
        assertEquals(TimeUnit.DAYS.toMillis(3), UpdateDelay.toMillis("3D"))
        assertEquals(TimeUnit.DAYS.toMillis(14), UpdateDelay.toMillis("2W"))
        assertEquals(TimeUnit.DAYS.toMillis(90), UpdateDelay.toMillis("3M"))
        assertEquals(TimeUnit.DAYS.toMillis(365), UpdateDelay.toMillis("1Y"))
    }

    @Test
    fun `case and spaces are tolerated`() {
        assertEquals(TimeUnit.DAYS.toMillis(14), UpdateDelay.toMillis(" 2w "))
        assertEquals(TimeUnit.DAYS.toMillis(30), UpdateDelay.toMillis("1 m"))
    }

    @Test
    fun `zero, missing or unreadable means never expires`() {
        assertNull(UpdateDelay.toMillis("0D"))
        assertNull(UpdateDelay.toMillis(null))
        assertNull(UpdateDelay.toMillis(""))
        assertNull(UpdateDelay.toMillis("soon"))
        assertNull(UpdateDelay.toMillis("-2W"))
        assertNull(UpdateDelay.toMillis("2X"))
    }
}
