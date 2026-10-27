package fr.toolmap.data.tiles

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentFirstGateTest {
    private val second = TimeUnit.SECONDS.toNanos(1)

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < end) Thread.sleep(5)
        check(condition()) { "condition not reached" }
    }

    @Test
    fun `up to the permits go through at once`() {
        val gate = RecentFirstGate(2)
        assertEquals(RecentFirstGate.Outcome.ACQUIRED, gate.acquire(second) { false })
        assertEquals(RecentFirstGate.Outcome.ACQUIRED, gate.acquire(second) { false })
        assertEquals(RecentFirstGate.Outcome.TIMED_OUT, gate.acquire(TimeUnit.MILLISECONDS.toNanos(60)) { false })
        assertEquals(0, gate.waiting)
    }

    @Test
    fun `the call that waited the least goes first`() {
        val gate = RecentFirstGate(1)
        gate.acquire(second) { false }
        val order = CopyOnWriteArrayList<String>()
        val threads =
            listOf("first", "second", "third").mapIndexed { index, name ->
                thread {
                    gate.acquire(5 * second) { false }
                    order += name
                    Thread.sleep(20)
                    gate.release()
                }.also { waitUntil { gate.waiting == index + 1 } }
            }
        gate.release()
        threads.forEach { it.join(5_000) }
        assertEquals(listOf("third", "second", "first"), order)
    }

    @Test
    fun `a cancelled call leaves the queue and never takes a turn`() {
        val gate = RecentFirstGate(1)
        gate.acquire(second) { false }
        val cancelled = AtomicBoolean(false)
        var outcome: RecentFirstGate.Outcome? = null
        val waiter = thread { outcome = gate.acquire(5 * second) { cancelled.get() } }
        waitUntil { gate.waiting == 1 }
        cancelled.set(true)
        waiter.join(5_000)
        assertEquals(RecentFirstGate.Outcome.CANCELLED, outcome)
        assertEquals(0, gate.waiting)
        gate.release()
        assertEquals(RecentFirstGate.Outcome.ACQUIRED, gate.acquire(second) { false }, "the permit is free again")
    }

    @Test
    fun `a call cancelled before it starts does not wait`() {
        val gate = RecentFirstGate(1)
        assertEquals(RecentFirstGate.Outcome.CANCELLED, gate.acquire(second) { true })
        assertEquals(RecentFirstGate.Outcome.ACQUIRED, gate.acquire(second) { false })
    }
}
