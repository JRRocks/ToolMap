package fr.toolmap.debugkit

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import fr.toolmap.core.eventlog.EventLog
import kotlin.math.abs

/** The screen locks [ScreenAwake] works with: [PowerScreenLock] on the phone, a fake in the tests. */
internal interface ScreenLock {
    /** Keeps the screen on, once it is on, for [ms] from now (replacing the previous time). */
    fun hold(ms: Long)

    /** Turns the screen on if it is off. */
    fun wakeUp()

    fun release()
}

/**
 * Keeps the phone's screen on while the collector asks for it (P21.6, `tools/livetest/cmd.sh awake <minutes>`), the
 * app in the background too. The screen is turned on when a request starts; the same request, repeated with each
 * announcement of the collector (every 5 s), only keeps it on: a screen turned off with the power button stays off.
 * A request can also be made on the device itself (`cmd.sh awake <minutes> here`, phone on USB): the collector's
 * announcements leave it alone. Times are counted on [elapsedMs] (since boot): a jump of the clock changes nothing.
 */
internal class ScreenAwake(
    private val lock: ScreenLock,
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
) {
    /** End of the collector's request and of the one made here; 0: none. */
    private var collectorUntil = 0L
    private var hereUntil = 0L

    /** The screen is kept on now. */
    val on: Boolean @Synchronized get() = collectorUntil != 0L || hereUntil != 0L

    /** The screen on for [seconds] more (0: no longer), asked by the collector or [here]. */
    @Synchronized
    fun request(
        seconds: Long,
        here: Boolean = false,
    ) {
        val now = elapsedMs()
        val until = if (seconds > 0) now + seconds * MS_PER_S else 0L
        val before = if (here) hereUntil else collectorUntil
        if (until == 0L && before == 0L) return
        val wasOn = on
        if (here) hereUntil = until else collectorUntil = until
        // Another command, not the same one heard again a few seconds later.
        val changed = before == 0L || until == 0L || abs(until - before) >= NEW_REQUEST_MS
        val by = if (here) "here" else "collector"
        if (!on) {
            lock.release()
            EventLog.event("debug.awake", "on" to false, "why" to "asked", "by" to by)
            return
        }
        lock.hold(maxOf(collectorUntil, hereUntil) - now)
        if (!wasOn) lock.wakeUp()
        if (changed) EventLog.event("debug.awake", "on" to true, "leftS" to seconds, "by" to by)
    }

    /** Called every second: notes when the time is over (the lock has gone by itself). */
    @Synchronized
    fun tick() {
        if (!on) return
        val now = elapsedMs()
        if (collectorUntil != 0L && now >= collectorUntil) collectorUntil = 0L
        if (hereUntil != 0L && now >= hereUntil) hereUntil = 0L
        if (on) return
        lock.release()
        EventLog.event("debug.awake", "on" to false, "why" to "time")
    }

    private companion object {
        const val MS_PER_S = 1000L

        /** A request whose end moves by this much is a new command (whole seconds and transit time move it by ~1 s). */
        const val NEW_REQUEST_MS = 30_000L
    }
}

/**
 * Android's screen locks: deprecated but still honoured, the app in the background too (unlike a window flag), and
 * only in the debug build. Turning the screen on ([PowerManager.ACQUIRE_CAUSES_WAKEUP]) may later need the
 * `TURN_SCREEN_ON` permission; without it, the screen is only kept on once it is on.
 */
@Suppress("DEPRECATION")
internal class PowerScreenLock(
    context: Context,
) : ScreenLock {
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keep =
        power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "toolmap:debug-awake").apply {
            setReferenceCounted(false)
        }
    private val wake =
        power
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "toolmap:debug-wake",
            )
            .apply { setReferenceCounted(false) }

    override fun hold(ms: Long) = keep.acquire(ms)

    override fun wakeUp() = wake.acquire(WAKE_MS)

    override fun release() {
        if (keep.isHeld) keep.release()
    }

    private companion object {
        /** Long enough for [keep] to take over. */
        const val WAKE_MS = 1_000L
    }
}
