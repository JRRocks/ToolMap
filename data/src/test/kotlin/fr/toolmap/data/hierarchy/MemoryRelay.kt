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
                ): Boolean {
                    if (closed || peer.closed) return false
                    synchronized(rooms) { written.getOrPut(room) { ArrayList() } += id to frame }
                    peer.listener.onFrame(id, frame)
                    return true
                }

                override fun close() = Unit
            }

        override fun start() {
            synchronized(rooms) {
                rooms.getOrPut(room) { ArrayList() } += this
                arrivals[room] = (arrivals[room] ?: 0) + 1
            }
            state = ServerState.ONLINE
            onState?.invoke(state)
            for (other in others()) {
                other.listener.onLinkOpen(id, "", 0, other.linkTo(this))
                listener.onLinkOpen(other.id, "", 0, linkTo(other))
            }
        }

        override fun ban(peer: Long) = Unit

        override fun close() {
            closed = true
            synchronized(rooms) { rooms[room]?.remove(this) }
            for (other in others()) other.listener.onLinkClosed(id)
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
