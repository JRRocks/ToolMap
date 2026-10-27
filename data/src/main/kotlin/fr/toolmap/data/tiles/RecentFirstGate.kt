package fr.toolmap.data.tiles

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Limits the simultaneous downloads of one source (AQX `<max-threads>`), and decides who goes next (ADR-058).
 *
 * Tiles asked for last are served first: the user has moved the map since, so the oldest waiting tiles are the ones
 * most likely to be off screen already. A waiting call whose request was cancelled leaves the queue without ever
 * reaching the network ([isCancelled] is looked at every [POLL_NANOS] while waiting).
 */
internal class RecentFirstGate(
    permits: Int,
) {
    enum class Outcome { ACQUIRED, TIMED_OUT, CANCELLED }

    private val lock = ReentrantLock()
    private val waiters = ArrayDeque<Condition>()
    private var free = permits

    /** The number of calls waiting for a turn (for tests). */
    val waiting: Int get() = lock.withLock { waiters.size }

    fun acquire(
        timeoutNanos: Long,
        isCancelled: () -> Boolean,
    ): Outcome {
        lock.withLock {
            if (isCancelled()) return Outcome.CANCELLED
            if (free > 0) {
                free--
                return Outcome.ACQUIRED
            }
            val me = lock.newCondition()
            waiters.addFirst(me)
            var remaining = timeoutNanos
            while (true) {
                if (free > 0 && waiters.first() === me) {
                    waiters.removeFirst()
                    free--
                    return Outcome.ACQUIRED
                }
                val outcome =
                    when {
                        isCancelled() -> Outcome.CANCELLED
                        remaining <= 0 -> Outcome.TIMED_OUT
                        else -> null
                    }
                if (outcome != null) {
                    waiters.remove(me)
                    // The turn offered to this call, if any, goes to the next one.
                    if (free > 0) waiters.firstOrNull()?.signal()
                    return outcome
                }
                val slice = minOf(remaining, POLL_NANOS)
                val left = me.awaitNanos(slice)
                remaining -= slice - left
            }
        }
    }

    fun release() {
        lock.withLock {
            free++
            waiters.firstOrNull()?.signal()
        }
    }

    private companion object {
        val POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(50)
    }
}
