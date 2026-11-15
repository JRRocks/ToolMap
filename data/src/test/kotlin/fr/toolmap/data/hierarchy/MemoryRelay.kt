package fr.toolmap.data.hierarchy

import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity

/**
 * The coordination server in memory, as in [OperationControllerTest]: one room per name, the members present, every
 * frame written delivered to every other member present. It also remembers every frame written, by room, for a test
 * that plays one again, and how many members ever came into each room.
 */
internal class MemoryRelay : RelayTransportFactory {
    private val rooms = HashMap<String, MutableList<Member>>()

    /** Every frame written, by room, with who wrote it. */
    val written = HashMap<String, MutableList<Pair<Long, ByteArray>>>()

    /** How many transports were ever started in each room. */
    val arrivals = HashMap<String, Int>()

    inner class Member(
        val room: String,
        val listener: LinkListener,
    ) : RelayTransport {
        override var identity: TransportIdentity? = null
        override var onState: ((ServerState) -> Unit)? = null
        override var state: ServerState = ServerState.CONNECTING
        var closed = false

        val id: Long get() = identity!!.memberId

        private fun others() = synchronized(rooms) { rooms[room].orEmpty().filter { it !== this && !it.closed } }

        private fun linkTo(peer: Member): MeshLink =
            object : MeshLink {
                override val isBroadcastMedium get() = true

                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean =
                    synchronized(rooms) {
                        if (closed || peer.closed) return false
                        written.getOrPut(room) { ArrayList() } += id to frame
                        peer.deliver(id, frame)
                        true
                    }

                override fun close() = Unit
            }

        /** The members this one was told of a link to; a frame of another waits until it is. */
        private val told = HashSet<Long>()
        private val early = HashMap<Long, MutableList<ByteArray>>()

        /** Under the lock of the rooms: [frame] of [from], heard once this member knows their link. */
        private fun deliver(
            from: Long,
            frame: ByteArray,
        ) {
            if (from in told) listener.onFrame(from, frame) else early.getOrPut(from) { ArrayList() } += frame
        }

        /** Under the lock of the rooms: this member is told of its link to [peer], then hears what came before. */
        private fun tell(peer: Member) {
            listener.onLinkOpen(peer.id, "", 0, linkTo(peer))
            told += peer.id
            early.remove(peer.id)?.forEach { listener.onFrame(peer.id, it) }
        }

        override fun start() {
            // As on the real server, no phone hears a peer before it is told of their link: both are told under the
            // lock, and a frame written meanwhile waits for it (P39.10: a phone told first could write at once, from
            // its own thread, and its frame reached the other before its link).
            synchronized(rooms) {
                rooms.getOrPut(room) { ArrayList() } += this
                arrivals[room] = (arrivals[room] ?: 0) + 1
                state = ServerState.ONLINE
                onState?.invoke(state)
                for (other in others()) {
                    other.tell(this)
                    tell(other)
                }
            }
        }

        override fun ban(peer: Long) = Unit

        override fun close() {
            synchronized(rooms) {
                closed = true
                rooms[room]?.remove(this)
                for (other in others()) {
                    other.told.remove(id)
                    other.early.remove(id)
                    other.listener.onLinkClosed(id)
                }
            }
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

    /** The members present now in [room]. */
    fun present(room: String): Int = synchronized(rooms) { rooms[room].orEmpty().count { !it.closed } }

    /** Whether [member] is present now in [room]. */
    fun isPresent(
        room: String,
        member: Long,
    ): Boolean = synchronized(rooms) { rooms[room].orEmpty().any { !it.closed && it.id == member } }

    /** Plays [frame] again in [room], as [from] wrote it, to every member present but [from]. */
    fun replay(
        room: String,
        from: Long,
        frame: ByteArray,
    ) {
        val members = synchronized(rooms) { rooms[room].orEmpty().filter { !it.closed && it.id != from } }
        for (m in members) m.listener.onFrame(from, frame)
    }

    override fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
    ): RelayTransport = Member(RelayPaths.room(groupId), listener)
}
