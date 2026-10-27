package fr.toolmap.debugkit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The screen kept on while the collector asks for it (P21.6). */
class ScreenAwakeTest {
    private var now = 5_000_000L

    private class FakeLock : ScreenLock {
        val holds = ArrayList<Long>()
        var wakeUps = 0
        var releases = 0

        override fun hold(ms: Long) {
            holds += ms
        }

        override fun wakeUp() {
            wakeUps++
        }

        override fun release() {
            releases++
        }
    }

    @Test
    fun `the screen is turned on once, then only kept on, until the collector says no more`() {
        val lock = FakeLock()
        val screen = ScreenAwake(lock) { now }
        screen.request(600)
        assertTrue(screen.on)
        assertEquals(1, lock.wakeUps)
        assertEquals(listOf(600_000L), lock.holds)
        // The same request heard again with the next announcements: kept on, never turned on again (a screen turned
        // off with the power button stays off).
        now += 5_000
        screen.request(595)
        now += 5_000
        screen.request(590)
        assertEquals(1, lock.wakeUps)
        assertEquals(listOf(600_000L, 595_000L, 590_000L), lock.holds)
        screen.request(0)
        assertFalse(screen.on)
        assertEquals(1, lock.releases)
        // Nothing asked any more: nothing to let go twice.
        screen.request(0)
        assertEquals(1, lock.releases)
    }

    @Test
    fun `when the time is over the screen goes, and a new command turns it on again`() {
        val lock = FakeLock()
        val screen = ScreenAwake(lock) { now }
        screen.request(10)
        now += 9_000
        screen.tick()
        assertTrue(screen.on)
        now += 1_000
        screen.tick()
        assertFalse(screen.on)
        assertEquals(1, lock.releases)
        screen.request(60)
        assertEquals(2, lock.wakeUps)
    }

    @Test
    fun `a request made here is not cancelled by the collector's announcements`() {
        val lock = FakeLock()
        val screen = ScreenAwake(lock) { now }
        screen.request(300, here = true)
        now += 5_000
        // The collector asks for nothing: what was asked here stays.
        screen.request(0)
        assertTrue(screen.on)
        assertEquals(0, lock.releases)
        // The collector asks for longer: the screen stays on until the later end, without being turned on again.
        screen.request(600)
        assertEquals(1, lock.wakeUps)
        assertEquals(600_000L, lock.holds.last())
        screen.request(0, here = true)
        assertTrue(screen.on, "the collector's request still holds")
        screen.request(0)
        assertFalse(screen.on)
        assertEquals(1, lock.releases)
    }

    @Test
    fun `the request travels with the announcements, and an older collector's means no`() {
        val acks = mapOf("3f2a" to Ack("e1", 99))
        val asked = DiagCodec.decode(DiagCodec.encode(DiagPayload.Acks(acks, awakeS = 1_800))) as DiagPayload.Acks
        assertEquals(1_800L, asked.awakeS)
        assertEquals(99L, asked.acks.getValue("3f2a").seq)
        val none = DiagCodec.decode(DiagCodec.encode(DiagPayload.Acks(acks))) as DiagPayload.Acks
        assertEquals(0L, none.awakeS)
    }
}
