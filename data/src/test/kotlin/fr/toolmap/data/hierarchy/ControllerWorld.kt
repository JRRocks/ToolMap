package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RelayPresence
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import java.util.PriorityQueue
import java.util.SplittableRandom
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * One virtual clock and one queue of tasks for every phone of a simulated world (P39.10): the threads of the
 * controllers ([Executor]) and the network ([ControllerServer]) take their turns in it, so that a scenario replays the
 * same way from its seed. A call made by the test itself (outside any task) runs at once, as the controller's thread
 * would run it while the test waits; what it posts waits its turn.
 */
internal class VirtualTime {
    var now: Long = START_MS
        private set
    private var order = 0L
    private var inTask = false

    inner class Task(
        val at: Long,
        val run: () -> Unit,
    ) : ScheduledFuture<Any?> {
        val order = this@VirtualTime.order++
        var cancelled = false
        var done = false

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val was = !cancelled && !done
            cancelled = true
            return was
        }

        override fun isCancelled() = cancelled

        override fun isDone() = done || cancelled

        override fun get(): Any? = null

        override fun get(
            timeout: Long,
            unit: TimeUnit,
        ): Any? = null

        override fun getDelay(unit: TimeUnit): Long = unit.convert(at - now, TimeUnit.MILLISECONDS)

        override fun compareTo(other: Delayed): Int =
            compareValues(getDelay(TimeUnit.MILLISECONDS), other.getDelay(TimeUnit.MILLISECONDS))
    }

    private val queue = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.order }))

    fun post(
        delayMs: Long,
        run: () -> Unit,
    ): Task = Task(now + maxOf(0L, delayMs), run).also(queue::add)

    /** Runs [block] as a task: what it posts waits its turn instead of running at once. */
    fun <T> asTask(block: () -> T): T {
        val was = inTask
        inTask = true
        try {
            return block()
        } finally {
            inTask = was
        }
    }

    fun runUntil(time: Long) {
        while (true) {
            val t = queue.peek() ?: break
            if (t.at > time) break
            queue.poll()
            if (t.cancelled) continue
            now = t.at
            asTask { t.run() }
            t.done = true
        }
        if (time > now) now = time
    }

    fun runFor(ms: Long) = runUntil(now + ms)

    /** The thread of one controller: shut down when its process dies, what it had posted is then lost. */
    inner class Executor :
        AbstractExecutorService(),
        ScheduledExecutorService {
        var shut = false
            private set

        override fun execute(command: Runnable) {
            if (shut) return
            if (!inTask) {
                asTask { command.run() }
                return
            }
            post(0) { if (!shut) command.run() }
        }

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = post(unit.toMillis(delay)) { if (!shut) command.run() }

        override fun <V : Any?> schedule(
            callable: Callable<V>,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<V> {
            @Suppress("UNCHECKED_CAST")
            return post(unit.toMillis(delay)) { if (!shut) callable.call() } as ScheduledFuture<V>
        }

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> = scheduleWithFixedDelay(command, initialDelay, period, unit)

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit,
        ): ScheduledFuture<*> {
            val step = unit.toMillis(delay)
            lateinit var handle: Task
            val repeating =
                object {
                    var current: Task? = null

                    fun arm(after: Long) {
                        current =
                            post(after) {
                                if (!shut && !handle.cancelled) {
                                    command.run()
                                    arm(step)
                                }
                            }
                    }
                }
            handle = Task(Long.MAX_VALUE, {})
            repeating.arm(unit.toMillis(initialDelay))
            return handle
        }

        override fun shutdown() {
            shut = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            shut = true
            return ArrayList()
        }

        override fun isShutdown() = shut

        override fun isTerminated() = shut

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ) = true
    }

    companion object {
        const val START_MS = 1_800_000_000_000L
    }
}

/**
 * The coordination server for whole controllers (P39.10), with the rules of the real one as the simulation of `:core`
 * plays them (`FirebaseRelay`, `firebase/database.rules.json`, spec § 17): one room per name; heartbeats, and the delays
 * to open and close a link, of the mode the transport is made for (`RelayPresence`: every 15 s, 40 s and 75 s for
 * « Entre amis », every minute, 160 s and 300 s for an operation); positions in the last-value slot
 * of 300 bytes; control frames of 4 500 bytes at most, kept until their writer deletes them 120 s after, dropped at
 * arrival after 150 s; a new stream gets the room as it is; closing while connected deletes the member's node.
 */
internal class ControllerServer(
    private val time: VirtualTime,
    seed: Long,
) : RelayTransportFactory {
    private val noise = SplittableRandom(seed)
    private val rooms = HashMap<String, Room>()
    var loss = 0.0
    var maxDelayMs = 300L
    var failedWrites = 0
    var largestControl = 0
    var largestPosition = 0
    val oversized = ArrayList<String>()

    private fun delay() = 30L + noise.nextLong(maxOf(1L, maxDelayMs - 29))

    private class Kept(
        val id: Long,
        val writer: Long,
        val bytes: ByteArray,
        val at: Long,
    )

    private inner class Room {
        val members = LinkedHashMap<Long, Member>()
        val beats = HashMap<Long, Long>()
        val slots = HashMap<Long, Kept>()
        val control = LinkedHashMap<Long, ArrayDeque<Kept>>()
        var ids = 0L
    }

    private class Peer(
        val id: Long,
    ) {
        var lastBeat = Long.MIN_VALUE / 4
        var link: MeshLink? = null
        var droppedAt = Long.MIN_VALUE / 4
    }

    /** One phone's network: what its controller's transports go through; off, they neither write nor hear. */
    inner class PhoneNet : RelayTransportFactory {
        var up = true
        val members = ArrayList<Member>()

        override fun create(
            config: RelayConfig,
            groupId: ByteArray,
            listener: LinkListener,
        ): RelayTransport = create(config, groupId, listener, RelayPresence.SHARE)

        override fun create(
            config: RelayConfig,
            groupId: ByteArray,
            listener: LinkListener,
            presence: RelayPresence,
        ): RelayTransport = Member(RelayPaths.room(groupId), listener, this, presence).also { members += it }

        /** The process dies: its transports stop at once, without deleting anything on the server. */
        fun kill() {
            for (m in members) m.dead = true
            members.clear()
        }
    }

    inner class Member(
        private val roomName: String,
        private val listener: LinkListener,
        private val net: PhoneNet,
        private val presence: RelayPresence,
    ) : RelayTransport {
        override var identity: TransportIdentity? = null
        override var onState: ((ServerState) -> Unit)? = null
        override var state: ServerState = ServerState.CONNECTING
        private val peers = LinkedHashMap<Long, Peer>()
        private var closed = false
        private var connected = false
        private var session = 0
        private var ticker: VirtualTime.Task? = null
        private var seconds = 0L
        private var lastPosition: ByteArray? = null
        private var lastControl: ByteArray? = null
        private var pendingPosition: ByteArray? = null
        private var positionQueued = false

        /** The process died: no more seconds, no delete; the others drop it when its heartbeat is too old. */
        var dead = false

        val id: Long get() = identity!!.memberId
        private val room: Room get() = rooms.getOrPut(roomName) { Room() }

        override fun start() {
            room.members[id] = this
            ticker = time.post(noise.nextLong(1_000)) { second() }
            connectIfUp()
        }

        private fun alive() = !closed && !dead

        private fun connectIfUp() {
            if (!alive() || connected || !net.up) return
            connected = true
            session++
            state = ServerState.ONLINE
            onState?.invoke(state)
            beat()
            val s = session
            time.post(delay()) { snapshot(s) }
        }

        private fun second() {
            if (!alive()) return
            seconds++
            if (!connected && net.up) connectIfUp()
            if (connected && !net.up) {
                connected = false
                session++
                state = ServerState.UNREACHABLE
                onState?.invoke(state)
            }
            if (seconds % 5 == 0L) evaluate()
            if (connected && seconds % presence.heartbeatS == 0L) beat()
            if (connected && seconds % 60 == 0L) {
                val mine = room.control[id]
                while (mine != null && mine.isNotEmpty() && time.now - mine.first().at > 120_000L) mine.removeFirst()
            }
            ticker = time.post(1_000) { second() }
        }

        private fun beat() {
            val at = time.now
            room.beats[id] = at
            for (other in room.members.values) {
                if (other === this || !other.connected) continue
                val s = other.session
                time.post(delay()) { other.beatArrives(id, at, s) }
            }
            if (pendingPosition != null) queuePosition()
        }

        fun beatArrives(
            who: Long,
            at: Long,
            s: Int,
        ) {
            if (!connected || s != session || !alive()) return
            val p = peers.getOrPut(who) { Peer(who) }
            if (at > p.lastBeat) p.lastBeat = at
            evaluate()
        }

        fun goneArrives(
            who: Long,
            s: Int,
        ) {
            if (!connected || s != session || !alive()) return
            val p = peers[who] ?: return
            p.lastBeat = Long.MIN_VALUE / 4
            if (p.link != null) {
                p.link = null
                p.droppedAt = time.now
                listener.onLinkClosed(who)
            }
        }

        private fun evaluate() {
            if (!alive()) return
            val now = time.now
            val opened = ArrayList<Peer>()
            for (p in peers.values) {
                val age = now - p.lastBeat
                if (p.link == null) {
                    if (age <= presence.openWithinMs && p.lastBeat > p.droppedAt) opened += p
                } else if (age > presence.closeAfterMs) {
                    p.link = null
                    p.droppedAt = now
                    listener.onLinkClosed(p.id)
                }
            }
            for (p in opened) {
                val link = linkTo(p)
                p.link = link
                listener.onLinkOpen(p.id, "", 0, link)
            }
        }

        private fun linkTo(p: Peer): MeshLink =
            object : MeshLink {
                override val isBroadcastMedium get() = true

                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean {
                    if (!alive() || p.link !== this) return false
                    write(frame, onPositionChannel)
                    return true
                }

                override fun close() = Unit
            }

        private fun snapshot(s: Int) {
            if (!connected || s != session || !alive()) return
            for ((who, at) in room.beats.entries.toList()) {
                if (who == id) continue
                val p = peers.getOrPut(who) { Peer(who) }
                if (at > p.lastBeat) p.lastBeat = at
            }
            evaluate()
            for ((writer, frames) in room.control.entries.toList()) {
                if (writer != id) for (f in frames.toList()) arrive(f, false, s)
            }
            for ((writer, f) in room.slots.entries.toList()) if (writer != id) arrive(f, true, s)
        }

        private fun write(
            frame: ByteArray,
            position: Boolean,
        ) {
            val toSlot = position && frame[0].toInt() == OperationFrames.POSITION_MARKER
            if (toSlot) {
                if (frame === lastPosition) return
                lastPosition = frame
                largestPosition = maxOf(largestPosition, frame.size)
                if (frame.size > 300) oversized += "position of ${frame.size} bytes"
                pendingPosition = frame
                if (connected) queuePosition()
                return
            }
            if (frame === lastControl) return
            lastControl = frame
            largestControl = maxOf(largestControl, frame.size)
            if (frame.size > OperationFrames.MAX_FRAME) oversized += "control frame of ${frame.size} bytes"
            if (!connected || frame.size > 4_500) {
                failedWrites++
                return
            }
            time.post(delay()) {
                val kept = Kept(++room.ids, id, frame, time.now)
                room.control.getOrPut(id) { ArrayDeque() }.addLast(kept)
                push(kept, false)
            }
        }

        private fun queuePosition() {
            if (positionQueued) return
            positionQueued = true
            val s = session
            time.post(delay()) {
                if (s == session) {
                    positionQueued = false
                    val f = pendingPosition
                    if (f != null && connected && alive()) {
                        pendingPosition = null
                        val kept = Kept(++room.ids, id, f, time.now)
                        room.slots[id] = kept
                        push(kept, true)
                    }
                }
            }
        }

        private fun push(
            kept: Kept,
            position: Boolean,
        ) {
            for (other in room.members.values) {
                if (other === this || !other.connected) continue
                if (loss > 0 && noise.nextDouble() < loss) continue
                val s = other.session
                time.post(delay()) { other.arriveFrom(kept, position, s) }
            }
        }

        private fun arriveFrom(
            kept: Kept,
            position: Boolean,
            s: Int,
        ) = arrive(kept, position, s)

        private fun arrive(
            kept: Kept,
            position: Boolean,
            s: Int,
        ) {
            if (!connected || s != session || !alive()) return
            if (peers[kept.writer]?.link == null) return
            if (!position && time.now - kept.at > 150_000L) return
            listener.onFrame(kept.writer, kept.bytes)
        }

        override fun ban(peer: Long) = Unit

        override fun close() {
            if (closed) return
            if (connected && !dead) {
                room.beats.remove(id)
                room.slots.remove(id)
                room.control.remove(id)
                for (other in room.members.values) {
                    if (other === this || !other.connected) continue
                    val s = other.session
                    time.post(delay()) { other.goneArrives(id, s) }
                }
            }
            closed = true
            connected = false
            ticker?.cancel(false)
            room.members.remove(id)
        }

        override fun offer(
            peer: Long,
            done: (PairingPayload?) -> Unit,
        ) = done(null)

        override fun answer(
            offer: PairingPayload,
            done: (PairingPayload?) -> Unit,
        ) = done(null)

        override fun accept(answer: PairingPayload) = Unit

        override fun drop(peer: Long) = Unit
    }

    /** Not used: each phone goes through its own [PhoneNet]. */
    override fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
    ): RelayTransport = PhoneNet().create(config, groupId, listener)

    override fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
        presence: RelayPresence,
    ): RelayTransport = PhoneNet().create(config, groupId, listener, presence)
}
