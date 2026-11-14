package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a phone keeps of an ensemble between two runs, as bytes (P39.6, `HierarchySnapshot`). */
class HierarchySnapshotTest {
    /** C in G1 of [deployed], with the keys of everything it reads, the names, and a waypoint of its group. */
    private fun phone(): Pair<HierarchyReplica, Device> {
        val source = deployed()
        val c = Device(C, source)
        for (channel in c.state.channelsReadBy(C)) c.issue(channel)
        for (name in listOf("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot")) c.names.learn(card(name))
        c.names.learn(card("Hotel"))
        val record =
            SharedRecords.write(keysOf(C), source.ensembleId, ShareKind.WAYPOINT, Channel.unit(G1), 9, byteArrayOf(1))
        assertEquals(ObjectVerdict.STORED, c.objects.accept(record, c.replica::signingKeyOf))
        return source to c
    }

    @Test
    fun `round trip of every part, each into an empty target`() {
        val (_, c) = phone()
        val ops = HierarchySnapshot.decodeOps(HierarchySnapshot.encodeOps(c.replica.ops()), c.replica.ensembleId)
        assertEquals(c.replica.ops(), ops)
        val back = HierarchySnapshot.restoreReplica(c.replica.ensembleId, C, ops.shuffled(), keysOf(C))
        assertSame(c.replica, back)

        val ring = KeyRing(c.replica.ensembleId, keysOf(C))
        HierarchySnapshot.restoreKeys(ring, HierarchySnapshot.encodeKeys(c.ring))
        assertContentEquals(HierarchySnapshot.encodeKeys(c.ring), HierarchySnapshot.encodeKeys(ring))
        for (channel in c.state.channelsReadBy(C)) {
            assertEquals(c.ring.sealingKey(c.state, channel)?.id, ring.sealingKey(c.state, channel)?.id)
        }
        val names = NameBook()
        HierarchySnapshot.restoreNames(names, HierarchySnapshot.encodeNames(c.names))
        assertEquals("Hotel", names.nameOf(card("Hotel").hidden))
        val objects = SharedObjects(c.replica.ensembleId)
        HierarchySnapshot.restoreObjects(objects, HierarchySnapshot.encodeObjects(c.objects))
        assertContentEquals(HierarchySnapshot.encodeObjects(c.objects), HierarchySnapshot.encodeObjects(objects))
        assertEquals(c.objects.digest(Channel.unit(G1)), objects.digest(Channel.unit(G1)))
    }

    @Test
    fun `damaged bytes throw a WireException and leave the target untouched`() {
        val (_, c) = phone()
        val parts =
            listOf(
                HierarchySnapshot.encodeOps(c.replica.ops()),
                HierarchySnapshot.encodeKeys(c.ring),
                HierarchySnapshot.encodeNames(c.names),
                HierarchySnapshot.encodeObjects(c.objects),
            )
        for ((index, bytes) in parts.withIndex()) {
            val damaged =
                listOf(
                    bytes.copyOf(bytes.size / 2),
                    bytes.copyOf(bytes.size + 3),
                    bytes.copyOf().also { it[0] = 9 },
                    bytes.copyOf().also { it[1] = ((it[1] + 1) % 4 + 1).toByte() },
                    ByteArray(0),
                )
            for (bad in damaged) {
                val ring = KeyRing(c.replica.ensembleId, keysOf(C))
                val names = NameBook()
                val objects = SharedObjects(c.replica.ensembleId)
                assertFailsWith<WireException>("part $index") {
                    when (index) {
                        0 -> HierarchySnapshot.decodeOps(bad, c.replica.ensembleId)
                        1 -> HierarchySnapshot.restoreKeys(ring, bad)
                        2 -> HierarchySnapshot.restoreNames(names, bad)
                        else -> HierarchySnapshot.restoreObjects(objects, bad)
                    }
                }
                assertTrue(ring.held.isEmpty() && ring.checked.isEmpty() && ring.pending.isEmpty())
                assertTrue(names.cards.isEmpty())
                assertTrue(objects.held.isEmpty())
            }
        }
        // Of another ensemble.
        assertFailsWith<WireException> { HierarchySnapshot.decodeOps(parts[0], c.replica.ensembleId + 1) }
        assertFailsWith<WireException> {
            HierarchySnapshot.restoreKeys(KeyRing(c.replica.ensembleId + 1, keysOf(C)), parts[1])
        }
    }

    @Test
    fun `forgetting a channel no longer read drops its keys, its objects and the batches that give its key back`() {
        val (source, c) = phone()
        val group = Channel.unit(G1)
        val oldKey = assertNotNull(c.ring.sealingKey(c.state, group))
        source.must(Change.UnassignMember(C))
        c.replica.addAll(source.ops())
        HierarchySnapshot.forgetUnread(c.state, c.ring, c.objects)
        assertNull(c.ring.find(group, oldKey.id))
        assertFalse(c.ring.checked.values.any { it.channel == group })
        assertTrue(c.objects.shown(group).isEmpty())
        assertEquals("Charlie", c.names.nameOf(c.state, C))
        // A batch of a channel this phone never read, kept to pass on, stays.
        val (_, other) = phone()
        val e = Device(E, other.replica)
        val g2 = e.issue(G2)
        assertEquals(KeyOffer.NOT_FOR_ME, other.ring.offer(g2, other.replica))
        HierarchySnapshot.forgetUnread(other.state, other.ring, other.objects)
        assertTrue(other.ring.checked.values.any { it.channel == Channel.unit(G2) })
    }
}
