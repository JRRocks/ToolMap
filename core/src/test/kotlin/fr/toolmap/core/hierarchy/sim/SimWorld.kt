package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.p2p.MeshLink
import java.util.PriorityQueue
import java.util.SplittableRandom

/**
 * How rough the network is (P39.10). Each push of a frame from the server to one phone may be lost or come twice, and
 * takes its own time; a write may fail. With [reorder], two frames of the same writer may reach a phone in the other
 * order (the real server keeps the order of one writer; reordering is the harder case, kept apart to tell them).
 */
data class Faults(
    val writeFail: Double = 0.0,
    val loss: Double = 0.0,
    val dup: Double = 0.0,
    val reorder: Boolean = false,
    val minDelayMs: Long = 40,
    val maxDelayMs: Long = 300,
    val spike: Double = 0.0,
    val spikeMaxMs: Long = 0,
) {
    companion object {
        val CALM = Faults()
        val ROUGH =
            Faults(
                writeFail = 0.02,
                loss = 0.04,
                dup = 0.03,
                reorder = true,
                minDelayMs = 30,
                maxDelayMs = 1_500,
                spike = 0.02,
                spikeMaxMs = 25_000,
            )
        val ORDERED_ROUGH = ROUGH.copy(reorder = false)
    }
}

/** What the server did, for the costs and for the frame limits. */
class ServerStats {
    var controlWrites = 0L
    var controlBytes = 0L
    var positionWrites = 0L
    var positionBytes = 0L
    var heartbeatWrites = 0L
    var cleanupWrites = 0L
    var failedWrites = 0L
    var pushes = 0L
    var pushedBytes = 0L
    var lost = 0L
    var droppedNoLink = 0L
    var droppedOld = 0L
    var largestControl = 0
    var largestPosition = 0
}

/** A frame kept by the server: a control frame of a member, or the last position of its slot. */
class StoredFrame(
    val id: Long,
    val writer: Long,
    val bytes: ByteArray,
    val atMs: Long,
)

/** The relay's view of another participant: its last heartbeat, and whether its link is open (FirebaseRelay). */
class PeerView(
    val id: Long,
) {
    var lastBeat = Long.MIN_VALUE / 4
    var open = false
    var droppedAt = Long.MIN_VALUE / 4
}

/**
 * Something in the room of the operation: a phone running the operation, a phone that keeps a trace, or a phone that
 * only listens to what the room carries. [World] plays the coordination server and the relay between them.
 */
abstract class Participant(
    val world: World,
    val id: Long,
) {
    /** The stream of the room is up and writes go through. */
    var connected = false

    /** Increased at every new stream: what the server pushed to an older one is lost with it. */
    var session = 0

    /** Increased every time the app starts: its clock of seconds belongs to that run. */
    var run = 0

    /** Within its second, when this participant's clock ticks: phones are not in step. */
    var phaseMs = 0L
    val peers = LinkedHashMap<Long, PeerView>()
    var lastPositionFrame: ByteArray? = null
    var lastControlFrame: ByteArray? = null
    var pendingPosition: ByteArray? = null
    var positionQueued = false
    var lastLandedAt = Long.MIN_VALUE
    var lastSecond = 0L

    /** Frames written, by this participant, and their bytes. */
    var framesWritten = 0L
    var bytesWritten = 0L
    var framesReceived = 0L
    var bytesReceived = 0L

    /** The app runs: its clock of seconds goes, its relay evaluates who is present. */
    abstract val running: Boolean

    /** The server's own frames this participant writes are its heartbeats: only while connected and running. */
    open val beats: Boolean get() = running && connected

    abstract fun linkOpened(
        peer: Long,
        link: MeshLink,
    )

    abstract fun linkClosed(peer: Long)

    /** A frame the relay let through (link open, not too old). */
    abstract fun frame(
        peer: Long,
        bytes: ByteArray,
    )

    /** Every frame the stream brings, before the relay's checks: what a modified phone could keep. */
    open fun captured(
        writer: Long,
        bytes: ByteArray,
    ) = Unit

    /** Called once a second while [running]. */
    open fun second() = Unit

    /** Whether frames pushed to this participant are delivered at all (a phone that only listens has no relay). */
    open val listensOnly: Boolean get() = false

    fun link(peer: Long): MeshLink =
        object : MeshLink {
            override val isBroadcastMedium get() = true

            override fun send(
                frame: ByteArray,
                onPositionChannel: Boolean,
            ): Boolean {
                val view = peers[peer]
                if (view == null || !view.open) return false
                world.write(this@Participant, frame, onPositionChannel)
                return true
            }

            override fun close() = Unit
        }
}

/** A rule of the server or of the operation broken by what a phone wrote: kept for the checks. */
class FrameViolation(
    val writer: Long,
    val what: String,
    val bytes: Int,
)

/**
 * The simulated world (P39.10): a virtual clock, a queue of events, and the coordination server with the rules of the
 * real one (`FirebaseRelay`, `firebase/database.rules.json`, spec § 17):
 *
 * - one room; every participant writes a heartbeat every 15 s; a relay opens the link of a participant whose last
 *   heartbeat is 40 s old at most, and closes it after 75 s without one (evaluated every 5 s and at each heartbeat);
 * - a frame whose first byte is 4 goes to the writer's position slot, the last one only (300 bytes at most); one
 *   position write at a time, the newest waiting one goes; a position waits during a cut and goes at reconnection;
 * - any other frame is a control frame (4 500 bytes at most on the server; the operation keeps them under 4 096),
 *   kept by the server until its writer deletes it, 120 s after (every 60 s while it is connected), and dropped at
 *   arrival when older than 150 s;
 * - a frame from a participant whose link is not open is dropped ("nolink"); one's own frames never come back;
 * - a new stream (a phone that connects again) first gets the room as it is: heartbeats, then the control frames kept
 *   and the positions of the slots;
 * - a participant that closes its transport while connected deletes its node of the room: the others see it gone.
 */
class World(
    val seed: Long,
) {
    var now: Long = START_MS
        private set
    var faults: Faults = Faults.CALM

    /** The randomness of the network; [reseed] gives each step of a scenario its own. */
    var noise = SplittableRandom(seed)
        private set

    fun reseed(stepSeed: Long) {
        noise = SplittableRandom(stepSeed)
    }

    private class Event(
        val at: Long,
        val order: Long,
        val run: () -> Unit,
    )

    private val queue =
        PriorityQueue<Event>(compareBy<Event>({ it.at }, { it.order }))
    private var order = 0L
    val participants = LinkedHashMap<Long, Participant>()
    private val beatsAt = HashMap<Long, Long>()
    private val slots = HashMap<Long, StoredFrame>()
    private val control = LinkedHashMap<Long, ArrayDeque<StoredFrame>>()
    private var frameIds = 0L
    private val lastArrival = HashMap<Pair<Long, Long>, Long>()
    val stats = ServerStats()
    val violations = ArrayList<FrameViolation>()

    /** Set while a check opens frames it is not given: the refusals it causes are not the phones'. */
    var muted = false

    /** What the simulation itself found odd (a frame of an honest phone that does not decode...). */
    val anomalies = ArrayList<String>()

    /** Set during the probes after the calm: what the phones hear is then kept in full. */
    var probing = false

    /** The phone whose node runs now: problems are told with it. */
    var current: String? = null

    fun at(
        time: Long,
        run: () -> Unit,
    ) {
        queue.add(Event(maxOf(time, now), order++, run))
    }

    fun after(
        delayMs: Long,
        run: () -> Unit,
    ) = at(now + delayMs, run)

    /** Runs every event due until [time], then sets the clock there. */
    fun runUntil(time: Long) {
        while (true) {
            val e = queue.peek() ?: break
            if (e.at > time) break
            queue.poll()
            now = e.at
            e.run()
        }
        if (time > now) now = time
    }

    fun runFor(ms: Long) = runUntil(now + ms)

    fun chance(p: Double): Boolean = p > 0 && noise.nextDouble() < p

    fun between(
        min: Long,
        max: Long,
    ): Long = if (max <= min) min else min + noise.nextLong(max - min + 1)

    private fun delay(): Long {
        val f = faults
        var d = between(f.minDelayMs, f.maxDelayMs)
        if (chance(f.spike)) d += between(0, f.spikeMaxMs)
        return d
    }

    // ---- presence ------------------------------------------------------------------------------------------------

    /** [p] joins the room (its app started): its clock of seconds starts. */
    fun enter(p: Participant) {
        participants[p.id] = p
        p.run++
        p.phaseMs = noise.nextLong(1_000)
        p.lastSecond = 0
        val run = p.run
        at(now + p.phaseMs) { secondOf(p, run) }
    }

    private fun secondOf(
        p: Participant,
        run: Int,
    ) {
        if (p.run != run || !p.running) return
        p.lastSecond++
        val s = p.lastSecond
        p.second()
        if (!p.running || p.run != run) return
        if (s % PRESENCE_EVERY_S == 0L) evaluate(p)
        if (p.connected && s % HEARTBEAT_S == 0L) heartbeat(p)
        if (p.connected && s % CLEANUP_S == 0L) cleanUp(p)
        at(now + 1_000) { secondOf(p, run) }
    }

    /** [p]'s stream comes up: it beats at once, then gets the room as it is. */
    fun connect(p: Participant) {
        if (p.connected) return
        p.connected = true
        p.session++
        participants[p.id] = p
        if (p.beats) heartbeat(p)
        if (p.pendingPosition != null) queuePosition(p)
        val session = p.session
        after(delay()) { snapshot(p, session) }
    }

    /**
     * [p] only listens to what the room carries, without beating nor writing: what a modified phone does once it is
     * out of the operation (the room is readable by whoever knows its name).
     */
    fun listen(p: Participant) {
        participants[p.id] = p
        if (p.connected) return
        p.connected = true
        p.session++
        val session = p.session
        after(delay()) { snapshot(p, session) }
    }

    /** [p] loses the network: its stream is gone; its relay sees no heartbeat any more. */
    fun disconnect(p: Participant) {
        if (!p.connected) return
        p.connected = false
        p.session++
    }

    /**
     * [p] closes its transport: connected, it deletes its node of the room (FirebaseRelay.close), and the others see
     * it gone at once; offline, its node stays and the others drop it when its heartbeat is too old.
     */
    fun close(p: Participant) {
        if (p.connected) {
            beatsAt.remove(p.id)
            slots.remove(p.id)
            control.remove(p.id)
            for (r in participants.values) {
                if (r === p || !r.connected) continue
                val session = r.session
                after(delay()) { gone(r, p.id, session) }
            }
        }
        disconnect(p)
        p.pendingPosition = null
    }

    /** [p]'s app stops (the phone is off, or the app is killed): no more seconds, no more stream. */
    fun stop(p: Participant) {
        disconnect(p)
        p.run++
        p.peers.clear()
        p.positionQueued = false
        p.pendingPosition = null
    }

    private fun gone(
        r: Participant,
        who: Long,
        session: Int,
    ) {
        if (!r.connected || r.session != session) return
        val view = r.peers[who] ?: return
        view.lastBeat = Long.MIN_VALUE / 4
        if (view.open) {
            view.open = false
            view.droppedAt = now
            if (r.running) r.linkClosed(who)
        }
    }

    private fun heartbeat(p: Participant) {
        stats.heartbeatWrites++
        val at = now
        beatsAt[p.id] = at
        for (r in participants.values) {
            if (r === p || !r.connected) continue
            val session = r.session
            after(delay()) { beatArrives(r, p.id, at, session) }
        }
        if (p.pendingPosition != null) queuePosition(p)
    }

    private fun beatArrives(
        r: Participant,
        who: Long,
        at: Long,
        session: Int,
    ) {
        if (!r.connected || r.session != session) return
        val view = r.peers.getOrPut(who) { PeerView(who) }
        if (at > view.lastBeat) view.lastBeat = at
        evaluate(r)
    }

    /** What FirebaseRelay.evaluate does: open the links of who is present, close those of who stopped beating. */
    fun evaluate(r: Participant) {
        if (!r.running || r.listensOnly) return
        val closing = ArrayList<Long>()
        val opening = ArrayList<Long>()
        for (view in r.peers.values) {
            val age = now - view.lastBeat
            if (!view.open) {
                if (age <= OPEN_WITHIN_MS && view.lastBeat > view.droppedAt) {
                    view.open = true
                    opening += view.id
                }
            } else if (age > CLOSE_AFTER_MS) {
                view.open = false
                view.droppedAt = now
                closing += view.id
            }
        }
        for (id in closing) r.linkClosed(id)
        for (id in opening) if (r.peers[id]?.open == true && r.running) r.linkOpened(id, r.link(id))
    }

    private fun snapshot(
        r: Participant,
        session: Int,
    ) {
        if (!r.connected || r.session != session) return
        if (r.listensOnly) {
            for (frames in control.values.toList()) for (f in frames.toList()) arrive(r, f, position = false)
            for (f in slots.values.toList()) arrive(r, f, position = true)
            return
        }
        for ((who, at) in beatsAt.entries.toList()) {
            if (who == r.id) continue
            val view = r.peers.getOrPut(who) { PeerView(who) }
            if (at > view.lastBeat) view.lastBeat = at
        }
        evaluate(r)
        for ((writer, frames) in control.entries.toList()) {
            if (writer == r.id) continue
            for (f in frames.toList()) arrive(r, f, position = false)
        }
        for ((writer, f) in slots.entries.toList()) if (writer != r.id) arrive(r, f, position = true)
    }

    // ---- writing -------------------------------------------------------------------------------------------------

    /** What FirebaseRelay.send does with a frame a link of [p] was given. */
    fun write(
        p: Participant,
        frame: ByteArray,
        onPositionChannel: Boolean,
    ) {
        val toSlot = onPositionChannel && frame.isNotEmpty() && frame[0].toInt() == OperationFrames.POSITION_MARKER
        // The node gives every link the same array: the server writes it once for the whole room.
        if (toSlot) {
            if (frame === p.lastPositionFrame) return
            p.lastPositionFrame = frame
        } else {
            if (frame === p.lastControlFrame) return
            p.lastControlFrame = frame
        }
        p.framesWritten++
        p.bytesWritten += frame.size
        // A phone knows what it wrote: its own keys go into what a modified phone could keep.
        p.captured(p.id, frame)
        if (toSlot) {
            if (frame.size >
                POSITION_SLOT_BYTES
            ) {
                violations += FrameViolation(p.id, "position over 300 bytes", frame.size)
            }
            stats.largestPosition = maxOf(stats.largestPosition, frame.size)
            p.pendingPosition = frame
            if (p.connected) queuePosition(p)
            return
        }
        stats.largestControl = maxOf(stats.largestControl, frame.size)
        if (frame.size > OperationFrames.MAX_FRAME) {
            violations += FrameViolation(p.id, "control frame over the operation's 4 096 bytes", frame.size)
        }
        if (frame.size > CONTROL_SERVER_BYTES) {
            // Refused by the rules of the server: never written.
            stats.failedWrites++
            return
        }
        if (!p.connected || chance(faults.writeFail)) {
            stats.failedWrites++
            return
        }
        var landAt = now + delay()
        if (!faults.reorder) landAt = maxOf(landAt, p.lastLandedAt + 1)
        p.lastLandedAt = landAt
        at(landAt) { land(p, frame) }
    }

    private fun land(
        p: Participant,
        frame: ByteArray,
    ) {
        stats.controlWrites++
        stats.controlBytes += frame.size
        val stored = StoredFrame(++frameIds, p.id, frame, now)
        control.getOrPut(p.id) { ArrayDeque() }.addLast(stored)
        push(stored, position = false)
    }

    private fun queuePosition(p: Participant) {
        if (p.positionQueued) return
        p.positionQueued = true
        val session = p.session
        after(delay()) {
            if (p.session == session) {
                p.positionQueued = false
                val frame = p.pendingPosition
                if (frame != null && p.connected) {
                    if (chance(faults.writeFail)) {
                        // The write failed: the position waits for the next heartbeat.
                        stats.failedWrites++
                    } else {
                        p.pendingPosition = null
                        stats.positionWrites++
                        stats.positionBytes += frame.size
                        val stored = StoredFrame(++frameIds, p.id, frame, now)
                        slots[p.id] = stored
                        push(stored, position = true)
                    }
                }
            }
        }
    }

    private fun push(
        stored: StoredFrame,
        position: Boolean,
    ) {
        for (r in participants.values) {
            if (r.id == stored.writer || !r.connected) continue
            if (chance(faults.loss)) {
                stats.lost++
                continue
            }
            val session = r.session
            var d = delay()
            if (!faults.reorder) {
                // The server's stream gives one writer's frames to one phone in the order they were written.
                val key = stored.writer to r.id
                val at = maxOf(now + d, (lastArrival[key] ?: Long.MIN_VALUE) + 1)
                lastArrival[key] = at
                d = at - now
            }
            after(d) { arrive(r, stored, position, session) }
            if (chance(faults.dup)) after(d + between(0, 30_000)) { arrive(r, stored, position, session) }
        }
    }

    private fun arrive(
        r: Participant,
        f: StoredFrame,
        position: Boolean,
        session: Int = r.session,
    ) {
        if (!r.connected || r.session != session) return
        stats.pushes++
        stats.pushedBytes += f.bytes.size
        r.framesReceived++
        r.bytesReceived += f.bytes.size
        r.captured(f.writer, f.bytes)
        if (r.listensOnly || !r.running) return
        if (r.peers[f.writer]?.open != true) {
            stats.droppedNoLink++
            return
        }
        if (!position && now - f.atMs > CONTROL_MAX_AGE_MS) {
            stats.droppedOld++
            return
        }
        r.frame(f.writer, f.bytes)
    }

    /** What FirebaseRelay.cleanUp does every minute: the writer deletes its own control frames older than 120 s. */
    private fun cleanUp(p: Participant) {
        val mine = control[p.id] ?: return
        var deleted = false
        while (mine.isNotEmpty() && now - mine.first().atMs > OWN_FRAME_TTL_MS) {
            mine.removeFirst()
            deleted = true
        }
        if (deleted) stats.cleanupWrites++
    }

    /** What a new process does at start (FirebaseRelay.purgeOldFrames): its own old frames deleted. */
    fun purgeOld(p: Participant) {
        val mine = control[p.id] ?: return
        mine.removeAll { now - it.atMs > OWN_FRAME_TTL_MS }
    }

    /** The control frames the server keeps now, every writer: for the checks of what the server holds. */
    fun kept(): List<StoredFrame> = control.values.flatten() + slots.values

    companion object {
        const val START_MS = 1_800_000_000_000L
        const val HEARTBEAT_S = 15L
        const val PRESENCE_EVERY_S = 5L
        const val CLEANUP_S = 60L
        const val OPEN_WITHIN_MS = 40_000L
        const val CLOSE_AFTER_MS = 75_000L
        const val CONTROL_MAX_AGE_MS = 150_000L
        const val OWN_FRAME_TTL_MS = 120_000L
        const val POSITION_SLOT_BYTES = 300
        const val CONTROL_SERVER_BYTES = 4_500
    }
}
