package fr.toolmap.data.hierarchy

import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.TransportIdentity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The coordination server in memory of the tests ([MemoryRelay]) keeps the promise of the real one: a phone never
 * hears a peer before it is told of their link (P39.10). Without it, `OperationTraceTest` failed now and then: a frame
 * of a phone reached the listener of a trace before its link, and the answer went nowhere (run « CI » n° 223).
 */
class MemoryRelayTest {
    /** Writes back on a link as soon as it is told of it, from the same thread, and notes what it hears, in order. */
    private class Eager : LinkListener {
        val heard = ArrayList<String>()

        override fun onLinkOpen(
            peer: Long,
            name: String,
            color: Int,
            link: MeshLink,
        ) {
            heard += "link $peer"
            link.send(byteArrayOf(1, 2, 3), false)
        }

        override fun onLinkClosed(peer: Long) {
            heard += "closed $peer"
        }

        override fun onFrame(
            peer: Long,
            bytes: ByteArray,
        ) {
            heard += "frame $peer"
        }
    }

    @Test
    fun `a member never hears a peer before it is told of their link`() {
        val relay = MemoryRelay()
        val group = ByteArray(16) { 7 }
        val server = RelayConfig.of("https://example.invalid", "key")
        val first = Eager()
        val second = Eager()
        val a = relay.create(server, group, first).apply { identity = TransportIdentity(group, 1L, "", 0) }
        val b = relay.create(server, group, second).apply { identity = TransportIdentity(group, 2L, "", 0) }
        a.start()
        b.start()
        assertEquals(listOf("link 2", "frame 2"), first.heard)
        assertEquals(listOf("link 1", "frame 1"), second.heard)
        b.close()
        assertEquals(listOf("link 2", "frame 2", "closed 2"), first.heard)
    }
}
