package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.KeyBatch
import fr.toolmap.core.hierarchy.KeyOffer
import fr.toolmap.core.hierarchy.KeyRing
import fr.toolmap.core.hierarchy.NameCard
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.ObjectVerdict
import fr.toolmap.core.hierarchy.OpAuth
import fr.toolmap.core.hierarchy.PhoneKeys
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.SharedRecord
import fr.toolmap.core.hierarchy.SharedRecords
import fr.toolmap.core.hierarchy.memberId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A realistic operation, written by the engine as the phones will write it: [creatorKeys] creates it, lets in an
 * administrator (B) and four members (C, D, E, F), makes two groups, places everyone, names a leader, a deputy and a
 * leader of the whole operation, hands out the content keys of every channel the creator reads, learns every name,
 * and shares waypoints and sketches (one taken away again). It also holds a key batch it only keeps to pass on, and
 * one for readers it does not know yet.
 */
internal class OperationWorld(
    val creatorKeys: PhoneKeys,
    /** The keys of C, a member of G1 and its leader: a [StoredOperation]'s, when a test stores C's phone too. */
    val cKeys: PhoneKeys = OpAuth.memoryKeys(),
    title: String = "Sortie du jour",
) {
    val bKeys = OpAuth.memoryKeys()
    val dKeys = OpAuth.memoryKeys()
    val eKeys = OpAuth.memoryKeys()
    val fKeys = OpAuth.memoryKeys()
    val gKeys = OpAuth.memoryKeys()
    val creator = creatorKeys.memberId
    val b = bKeys.memberId
    val c = cKeys.memberId
    val d = dKeys.memberId
    val e = eKeys.memberId
    val f = fKeys.memberId

    val cards: Map<String, NameCard> =
        listOf(title, "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Groupe 1", "Groupe 2")
            .associateWith { Names.hide(it) }

    /** A card that came before its operation: the book keeps it. */
    val early: NameCard = Names.hide("Golf")

    val replica: HierarchyReplica =
        HierarchyReplica.create(creatorKeys, hidden("Alpha"), hidden(title), nonce = System.nanoTime())
    val contents: OperationContents = OperationContents.around(replica, creatorKeys)

    /** What C's phone holds of the operation (C is the leader of G1). */
    val cContents: OperationContents

    /** The batch of G2's key, issued by E: the creator is no reader of G2, it keeps the batch to pass it on. */
    val relayed: KeyBatch

    val waypoint: SharedRecord
    val sketch: SharedRecord

    init {
        must(Change.AdmitMember(b, hidden("Bravo"), bKeys.public))
        must(Change.AdmitMember(c, hidden("Charlie"), cKeys.public))
        must(Change.AdmitMember(d, hidden("Delta"), dKeys.public))
        must(Change.AdmitMember(e, hidden("Echo"), eKeys.public))
        must(Change.AdmitMember(f, hidden("Foxtrot"), fKeys.public))
        must(Change.AddAdmin(b))
        must(Change.CreateGroup(G1, hidden("Groupe 1")))
        must(Change.CreateGroup(G2, hidden("Groupe 2")))
        must(Change.AssignMember(creator, G1))
        must(Change.AssignMember(c, G1))
        must(Change.AssignMember(d, G1))
        must(Change.AssignMember(e, G2))
        must(Change.AssignMember(f, G2))
        must(Change.SetPost(G1, Post.LEADER, c))
        must(Change.SetPost(G1, Post.DEPUTY, d))
        must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, creator))
        // Everyone is placed but B, an administrator without a group.
        val state = replica.state
        for (channel in state.channelsReadBy(creator)) {
            requireNotNull(contents.ring.issue(state, channel)) { "no key for $channel" }
        }
        cards.values.forEach(contents.names::learn)
        contents.names.learn(early)

        // E hands out the key of its own group; the creator checks it and keeps it for whoever catches up.
        val eReplica = HierarchyReplica.restore(replica.ensembleId, e, replica.ops(), eKeys)
        relayed = requireNotNull(KeyRing(replica.ensembleId, eKeys).issue(eReplica.state, Channel.unit(G2)))
        assertEquals(KeyOffer.NOT_FOR_ME, contents.ring.offer(relayed, replica))

        // B lets in G (an operation this phone has not received yet) and hands out the key of G's waiting lane.
        val bReplica = HierarchyReplica.restore(replica.ensembleId, b, replica.ops(), bKeys)
        assertIs<IssueResult.Issued>(bReplica.issue(Change.AdmitMember(gKeys.memberId, early.hidden, gKeys.public)))
        val lane = Channel.waiting(gKeys.memberId)
        val ahead = requireNotNull(KeyRing(replica.ensembleId, bKeys).issue(bReplica.state, lane))
        assertEquals(KeyOffer.UNKNOWN_READERS, contents.ring.offer(ahead, replica))

        waypoint = record(creatorKeys, ShareKind.WAYPOINT, Channel.unit(G1), 1, "rendez-vous")
        sketch = record(creatorKeys, ShareKind.SKETCH, Channel.ENSEMBLE, 2, "itinéraire")
        val gone = record(creatorKeys, ShareKind.WAYPOINT, Channel.ENSEMBLE, 3, "ancien point")
        stored(SharedRecords.remove(creatorKeys, replica.ensembleId, gone))
        record(cKeys, ShareKind.WAYPOINT, Channel.unit(G1), 4, "point de C")

        // C's phone: every operation, the keys it is a reader of, the names, what is shown in its channels.
        val cReplica = HierarchyReplica.restore(replica.ensembleId, c, replica.ops(), cKeys)
        cContents = OperationContents.around(cReplica, cKeys)
        for (batch in contents.ring.batchesFor(state)) cContents.ring.offer(batch, cReplica)
        for (channel in cReplica.state.channelsReadBy(c)) {
            if (cContents.ring.sealingKey(cReplica.state, channel) == null) {
                cContents.ring.issue(cReplica.state, channel)
            }
        }
        cards.values.forEach(cContents.names::learn)
        for (one in listOf(waypoint, sketch)) {
            assertEquals(ObjectVerdict.STORED, cContents.objects.accept(one, cReplica::signingKeyOf))
        }
    }

    val ensembleId: Long get() = replica.ensembleId

    fun hidden(name: String) = requireNotNull(cards[name]) { name }.hidden

    fun must(change: Change) {
        assertIs<IssueResult.Issued>(replica.issue(change), "$change")
    }

    private fun record(
        keys: PhoneKeys,
        kind: ShareKind,
        channel: Channel,
        id: Long,
        body: String,
    ): SharedRecord =
        SharedRecords.write(keys, replica.ensembleId, kind, channel, id, body.toByteArray()).also(::stored)

    private fun stored(record: SharedRecord) {
        assertEquals(ObjectVerdict.STORED, contents.objects.accept(record, replica::signingKeyOf))
    }

    companion object {
        const val G1 = 0x101L
        const val G2 = 0x102L
    }
}

/** Same operations, state, refusals, keys, batches, names and objects: what a reload must give back. */
internal fun assertSameContents(
    expected: OperationContents,
    actual: OperationContents,
) {
    assertEquals(expected.ensembleId, actual.ensembleId)
    assertEquals(expected.replica.self, actual.replica.self)
    assertEquals(expected.replica.state, actual.replica.state)
    assertContentEquals(
        HierarchyWire.encodeState(expected.replica.state),
        HierarchyWire.encodeState(actual.replica.state),
    )
    assertEquals(expected.replica.summary(), actual.replica.summary())
    assertEquals(
        expected.replica.history().map { it.op to it.rejection },
        actual.replica.history().map { it.op to it.rejection },
    )
    assertEquals(expected.replica.ops(), actual.replica.ops())
    assertContentEquals(HierarchySnapshot.encodeKeys(expected.ring), HierarchySnapshot.encodeKeys(actual.ring))
    assertContentEquals(HierarchySnapshot.encodeNames(expected.names), HierarchySnapshot.encodeNames(actual.names))
    assertContentEquals(
        HierarchySnapshot.encodeObjects(expected.objects),
        HierarchySnapshot.encodeObjects(actual.objects),
    )
    val state = actual.replica.state
    for (channel in state.channels()) {
        assertEquals(expected.ring.sealingKey(state, channel)?.id, actual.ring.sealingKey(state, channel)?.id)
    }
    assertNull(state.inconsistency())
}
