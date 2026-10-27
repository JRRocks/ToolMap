package fr.toolmap.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalWatchTest {
    @Test
    fun `a minute without a fix is lost, once`() {
        val watch = SignalWatch(lostAfterMillis = 60_000)
        watch.start(0)
        assertNull(watch.check(59_999))
        assertEquals(SignalWatch.Change.LOST, watch.check(60_000))
        assertNull(watch.check(90_000))
        assertTrue(watch.isLost)
    }

    @Test
    fun `a fix after the loss says the signal is back, once`() {
        val watch = SignalWatch(lostAfterMillis = 60_000)
        watch.start(0)
        watch.check(120_000)
        assertEquals(SignalWatch.Change.RECOVERED, watch.onFix(130_000))
        assertNull(watch.onFix(131_000))
        assertFalse(watch.isLost)
    }

    @Test
    fun `regular fixes keep the signal ok`() {
        val watch = SignalWatch(lostAfterMillis = 60_000)
        watch.start(0)
        for (t in 1..10) {
            assertNull(watch.onFix(t * 10_000L))
            assertNull(watch.check(t * 10_000L + 5_000))
        }
        assertFalse(watch.isLost)
    }
}
