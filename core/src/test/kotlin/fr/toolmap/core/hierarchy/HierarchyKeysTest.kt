package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key scheme that really closes out whoever waits for a group (`docs/specs/group-hierarchy.md` § 8, answer R1):
 * one content key per unit and per list of readers, wrapped to each reader and to nobody else.
 */
class HierarchyKeysTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        assertTrue(problems.all { it.expected }, "a key that does not open is not a bug: ${problems.map { it.where }}")
    }

    @Test
    fun `R1 really closed - a waiting member is wrapped no key and can open nothing that is shared`() {
        val w = world()
        val state = w[H].state
        assertTrue(state.isWaiting(H))
        // No list of readers holds it.
        assertTrue(state.allReaders().none { H in it })
        assertEquals(emptyList(), w[H].ring.missing(state))
        // Positions, sketches, waypoints, messages: sealed for the readers, opened by them.
        val frames =
            listOf(
                w[C].frame(ShareKind.POSITION, ROOT, "position de C"),
                w[C].frame(ShareKind.SKETCH, G1, "croquis du groupe 1"),
                w[D].frame(ShareKind.WAYPOINT, G1, "waypoint du groupe 1"),
                w[D].frame(ShareKind.MESSAGE, G1, "message du groupe 1"),
                w[B].frame(ShareKind.MESSAGE, ROOT, "message à tout l'ensemble"),
            )
        assertEquals("position de C", w[D].read(frames[0]))
        assertEquals("message du groupe 1", w[C].read(frames[3]))
        assertEquals("message à tout l'ensemble", w[F].read(frames[4]))
        // The phone that waits sees the same bytes pass and can do nothing with them.
        for (frame in frames) assertEquals(ShareRefusal.NO_KEY, w[H].refusal(frame))
        // It shares nothing either.
        for (kind in ShareKind.entries) {
            for (unit in listOf(ROOT, G1, G2)) {
                val refused = w[H].seal(kind, unit, "rien")
                assertEquals(ShareRefusal.NOT_ALLOWED, (refused as SealResult.Refused).reason)
            }
        }
        // And nobody hands out a key from the waiting room.
        assertNull(w[H].ring.issue(state, ROOT))
    }

    @Test
    fun `R1 really closed - no wrapped copy of a key opens with the keys of a waiting phone`() {
        val w = world()
        val batches = w[A].ring.batchesFor(w[A].state)
        assertEquals(setOf(ROOT, G1, G2), batches.map { it.unit }.toSet())
        for (batch in batches) {
            // The phone that waits takes the batch: checked, kept to pass on, and that is all.
            assertEquals(KeyOffer.DUPLICATE, w[H].take(batch))
            assertTrue(batch.wraps.none { it.recipient == H })
            assertNull(w[H].ring.find(batch.unit, batch.keyId))
            // A modified phone tries every copy as if it were its own: none opens.
            for (wrap in batch.wraps) {
                val stolen =
                    KeyBatch(
                        batch.ensembleId,
                        batch.unit,
                        batch.readersTag,
                        batch.keyId,
                        batch.issuer,
                        batch.ephemeral,
                        listOf(KeyBatch.Wrap(H, wrap.sealed)),
                        batch.signature,
                    )
                assertNull(ContentKeys.unwrap(stolen, keysOf(H)))
            }
            // While each reader opens its own copy, and only its own.
            for (wrap in batch.wraps) assertEquals(batch.keyId, ContentKeys.unwrap(batch, keysOf(wrap.recipient))?.id)
        }
        assertTrue(problems.isNotEmpty() && problems.all { it.where == "hierarchy.crypto.open" })
    }

    @Test
    fun `R1 once assigned a member gets the keys of its group and of the ensemble, not what was shared before`() {
        val w = world()
        val before =
            listOf(
                w[C].frame(ShareKind.POSITION, ROOT, "avant"),
                w[C].frame(ShareKind.MESSAGE, G1, "avant, dans le groupe"),
            )
        val oldRoot = w[C].ring.sealingKey(w[C].state, ROOT)!!.id
        val oldG2 = w[E].ring.sealingKey(w[E].state, G2)!!.id
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread()
        // The readers of the ensemble and of G1 changed: their keys are no longer the keys to seal with.
        assertEquals(listOf(G1, ROOT), w[C].ring.missing(w[C].state))
        assertEquals(ShareRefusal.NO_KEY, w[C].sealRefusal(ShareKind.POSITION, ROOT, "trop tôt"))
        // G2 did not change: same key, nothing to hand out.
        assertEquals(oldG2, w[E].ring.sealingKey(w[E].state, G2)?.id)
        assertEquals(listOf(ROOT), w[E].ring.missing(w[E].state))

        val root = w.handOut(A, ROOT)
        val g1 = w.handOut(C, G1)
        assertNotEquals(oldRoot, root.keyId)
        assertTrue(root.wraps.any { it.recipient == H } && g1.wraps.any { it.recipient == H })
        assertEquals(emptyList(), w[H].ring.missing(w[H].state))
        // The newcomer reads and shares from now on.
        assertEquals("après", w[H].read(w[C].frame(ShareKind.POSITION, ROOT, "après")))
        assertEquals("après, dans le groupe", w[H].read(w[D].frame(ShareKind.MESSAGE, G1, "après, dans le groupe")))
        assertEquals("de H", w[D].read(w[H].frame(ShareKind.SKETCH, G1, "de H")))
        // What was shared while it waited stays closed: those keys were never wrapped to it, and never will be.
        for (frame in before) assertEquals(ShareRefusal.NO_KEY, w[H].refusal(frame))
        for (batch in w[A].ring.batchesFor(w[A].state)) assertEquals(KeyOffer.DUPLICATE, w[H].take(batch))
        for (frame in before) assertEquals(ShareRefusal.NO_KEY, w[H].refusal(frame))
    }

    @Test
    fun `R9 R13 a member removed, gone, put back to waiting or whose group is deleted opens nothing shared after`() {
        val ways: List<Pair<String, (World) -> Unit>> =
            listOf(
                "removed by the creator" to { w -> w[A].replica.must(Change.RemoveMember(D)) },
                "left by itself" to { w -> w[D].replica.must(Change.RemoveMember(D)) },
                "put back to waiting" to { w -> w[B].replica.must(Change.UnassignMember(D)) },
                "group deleted" to { w -> w[B].replica.must(Change.DeleteGroup(G1)) },
            )
        for ((way, change) in ways) {
            for (heard in listOf(true, false)) {
                val case = "$way, its phone ${if (heard) "heard of it" else "did not hear of it"}"
                val w = world()
                assertEquals("avant", w[D].read(w[E].frame(ShareKind.POSITION, ROOT, "avant")), case)
                val last = w[D].frame(ShareKind.POSITION, ROOT, "dernière position de D")
                change(w)
                if (way == "left by itself") {
                    w.spread()
                } else if (heard) {
                    w.spread()
                } else {
                    w.spread(D)
                }
                // New readers, new keys; nothing is wrapped to the one who is out.
                val root = w.handOut(A, ROOT)
                assertTrue(root.wraps.none { it.recipient == D }, case)
                val expected = if (heard || way == "left by itself") KeyOffer.DUPLICATE else KeyOffer.UNKNOWN_READERS
                assertEquals(expected, w[D].take(root), case)
                assertNull(w[D].ring.find(ROOT, root.keyId), case)
                assertEquals(ShareRefusal.NO_KEY, w[D].refusal(w[E].frame(ShareKind.POSITION, ROOT, "après")), case)
                assertEquals(ShareRefusal.NO_KEY, w[D].refusal(w[B].frame(ShareKind.MESSAGE, ROOT, "après")), case)
                // The key it kept is of no use to speak either: its last frames are refused by those who heard.
                assertTrue(w[E].refusal(last) in setOf(ShareRefusal.NOT_ALLOWED, ShareRefusal.STALE_KEY), case)
                if (w[C].state.hasAccess(C)) {
                    val g1 = w.handOut(C, G1)
                    assertTrue(g1.wraps.none { it.recipient == D }, case)
                    assertEquals(ShareRefusal.NO_KEY, w[D].refusal(w[C].frame(ShareKind.MESSAGE, G1, "après")), case)
                }
            }
        }
    }

    @Test
    fun `R1 the creator, the administrators and the ensemble leader read the ensemble without a group`() {
        val w = world()
        for (member in listOf(A, B)) {
            assertNull(w[member].state.members.getValue(member).group)
            assertNotNull(w[member].ring.sealingKey(w[member].state, ROOT))
            assertEquals("pour tous", w[member].read(w[C].frame(ShareKind.POSITION, ROOT, "pour tous")))
        }
        // H, who waits, is named leader of the ensemble: it comes into the readers of the ensemble, with a new key.
        w[B].replica.must(leader(ROOT, H))
        w.spread()
        val root = w.handOut(A, ROOT)
        assertTrue(root.wraps.any { it.recipient == H })
        assertEquals("au nouveau leader", w[H].read(w[C].frame(ShareKind.POSITION, ROOT, "au nouveau leader")))
        assertEquals("du leader", w[F].read(w[H].frame(ShareKind.MESSAGE, ROOT, "du leader")))
        // The post is taken back: out of the readers, and a key more.
        w[B].replica.must(Change.ClearPost(ROOT, Post.LEADER))
        w.spread()
        w.handOut(B, ROOT)
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(w[C].frame(ShareKind.POSITION, ROOT, "sans lui")))
    }

    @Test
    fun `R7 a key per group - the thread of a group is closed to the other groups and to those above`() {
        val w = world()
        val message = w[C].frame(ShareKind.MESSAGE, G1, "entre nous, groupe 1")
        val sketch = w[D].frame(ShareKind.SKETCH, G1, "croquis du groupe 1")
        assertEquals("entre nous, groupe 1", w[D].read(message))
        assertEquals("croquis du groupe 1", w[C].read(sketch))
        // A member of another group, an administrator and the creator hold the key of the ensemble, not this one.
        for (member in listOf(E, F, A, B)) {
            assertNotNull(w[member].ring.sealingKey(w[member].state, ROOT))
            assertNull(w[member].ring.sealingKey(w[member].state, G1))
            assertEquals(ShareRefusal.NO_KEY, w[member].refusal(message), "$member")
            assertEquals(ShareRefusal.NO_KEY, w[member].refusal(sketch), "$member")
        }
        // The leader of the group sends its sketch one level up: sealed again, for the readers of the ensemble.
        w[B].replica.must(leader(G1, C))
        w.spread()
        assertEquals("croquis remonté", w[E].read(w[C].frame(ShareKind.SKETCH, ROOT, "croquis remonté")))
        // An administrator cannot hand out the key of a group it does not read.
        assertNull(w[B].ring.issue(w[B].state, G1))
        assertFailsWith<IllegalArgumentException> { ContentKeys.issue(w[B].state.readers(G1)!!, keysOf(B)) }
    }

    @Test
    fun `R17 with sub-groups a key reaches a unit and what is under it, never sideways`() {
        val sub = 0x201L
        val source =
            deployed(TWO_LEVELS).apply {
                must(Change.CreateGroup(sub, "Sous-groupe", parent = G1))
                must(Change.AssignMember(D, sub))
                must(leader(sub, D))
            }
        val w = World(source, listOf(A, C, D, E))
        w.handOut(A, ROOT)
        w.handOut(C, G1)
        w.handOut(D, sub)
        // D, in the sub-group, reads its sub-group, the group above and the ensemble.
        assertEquals(emptyList(), w[D].ring.missing(w[D].state))
        assertEquals("du groupe", w[D].read(w[C].frame(ShareKind.MESSAGE, G1, "du groupe")))
        // Its leader sends a sketch one level up: the group reads it, the ensemble does not.
        val up = w[D].frame(ShareKind.SKETCH, G1, "croquis du sous-groupe")
        assertEquals("croquis du sous-groupe", w[C].read(up))
        assertEquals(ShareRefusal.NO_KEY, w[E].refusal(up))
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].sealRefusal(ShareKind.SKETCH, ROOT, "trop haut"))
        // C, in the group itself, does not read the thread of the sub-group.
        assertEquals(ShareRefusal.NO_KEY, w[C].refusal(w[D].frame(ShareKind.MESSAGE, sub, "entre nous")))
    }

    @Test
    fun `a phone that was away catches up - the operations first, then the key batches any phone kept`() {
        val w = world()
        // F is away while H comes into its group and the keys change twice.
        w[B].replica.must(Change.AssignMember(H, G2))
        w.spread(F)
        w.handOut(A, ROOT, F)
        w.handOut(E, G2, F)
        w[B].replica.must(Change.RenameGroup(G2, "Arrière"))
        w[A].replica.must(Change.RemoveMember(C))
        w.spread(F)
        val root = w.handOut(B, ROOT, F)
        w.handOut(D, G1, F)
        val message = w[H].frame(ShareKind.MESSAGE, G2, "F, tu nous lis ?")
        val position = w[E].frame(ShareKind.POSITION, ROOT, "position de E")
        assertEquals(ShareRefusal.NO_KEY, w[F].refusal(message))
        // F comes back near D, who reads neither G2 nor anything of F's group: D kept the batches all the same.
        val kept = w[D].ring.batchesFor(w[D].state)
        assertEquals(setOf(ROOT, G1, G2), kept.map { it.unit }.toSet())
        assertTrue(root.keyId in kept.map { it.keyId })
        // The batches before the operations: F does not know those readers yet, they wait.
        for (batch in kept.filter { it.unit != G1 }) assertEquals(KeyOffer.UNKNOWN_READERS, w[F].take(batch))
        assertEquals(1, sync(w[F].replica, w[D].replica))
        assertEquals(2, w[F].ring.retry(w[F].replica))
        assertEquals(emptyList(), w[F].ring.missing(w[F].state))
        assertEquals("F, tu nous lis ?", w[F].read(message))
        assertEquals("position de E", w[F].read(position))
        assertEquals("oui", w[H].read(w[F].frame(ShareKind.MESSAGE, G2, "oui")))
        // In the other order (operations, then batches) nothing waits.
        val late = Device(F, w[D].replica)
        for (batch in kept.filter { it.unit != G1 }) assertEquals(KeyOffer.ACCEPTED, late.take(batch))
        assertEquals(KeyOffer.NOT_FOR_ME, late.take(kept.single { it.unit == G1 }))
        assertEquals("position de E", late.read(position))
    }

    @Test
    fun `two phones hand out a key at the same time - every phone seals with the same one and opens both`() {
        val w = world()
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread()
        // A and B both see that the ensemble has no key for its new readers, and both make one.
        val fromA = w[A].issue(ROOT)
        val early = w[A].frame(ShareKind.POSITION, ROOT, "scellé avec la clé de A")
        val fromB = w[B].issue(ROOT)
        val both = listOf(fromA, fromB)
        for ((index, device) in w.devices.values.withIndex()) {
            for (batch in if (index % 2 == 0) both else both.reversed()) device.take(batch)
        }
        val chosen = w.devices.values.map { it.ring.sealingKey(it.state, ROOT)?.id }.toSet()
        assertEquals(1, chosen.size)
        val smallest = both.map { it.keyId }.minWithOrNull { a, b -> java.lang.Long.compareUnsigned(a, b) }
        assertEquals(smallest, chosen.single())
        // What was sealed with either key by one of the readers of now is read.
        assertEquals("scellé avec la clé de A", w[H].read(early))
        assertEquals("ensuite", w[C].read(w[B].frame(ShareKind.POSITION, ROOT, "ensuite")))
    }

    @Test
    fun `a key batch is refused unless made by a reader, signed, and wrapped to exactly the readers`() {
        val w = world()
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread()
        val readers = w[C].state.readers(G1)!!
        val good = ContentKeys.issue(readers, keysOf(C)).batch
        assertEquals(KeyOffer.ACCEPTED, ContentKeys.check(good, readers))

        fun copy(
            issuer: Long = good.issuer,
            keyId: Long = good.keyId,
            wraps: List<KeyBatch.Wrap> = good.wraps,
            signature: ByteArray = good.signature,
            ensembleId: Long = good.ensembleId,
        ) = KeyBatch(ensembleId, good.unit, good.readersTag, keyId, issuer, good.ephemeral, wraps, signature)

        // Not signed by its issuer: signature damaged, or content changed after it was signed.
        val damaged = good.signature.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(KeyOffer.FORGED, w[D].take(copy(signature = damaged)))
        assertEquals(KeyOffer.FORGED, w[D].take(copy(keyId = good.keyId + 1)))
        assertEquals(KeyOffer.FORGED, w[D].take(copy(wraps = good.wraps.dropLast(1))))
        // Made by someone who does not read the unit: an administrator outside the group, a member who waits.
        assertEquals(KeyOffer.ISSUER_NOT_A_READER, w[D].take(ContentKeys.signed(copy(issuer = B), keysOf(B))))
        val outsider = ContentKeys.signed(copy(issuer = OUTSIDER), keysOf(OUTSIDER))
        assertEquals(KeyOffer.ISSUER_NOT_A_READER, w[D].take(outsider))
        // Signed by a reader, but not wrapped to exactly the readers: one left out, or one added from outside.
        val without = ContentKeys.signed(copy(wraps = good.wraps.filter { it.recipient != H }), keysOf(C))
        assertEquals(KeyOffer.WRONG_RECIPIENTS, w[D].take(without))
        val extra = (good.wraps + KeyBatch.Wrap(E, good.wraps.first().sealed)).sortedBy { it.recipient.toULong() }
        assertEquals(KeyOffer.WRONG_RECIPIENTS, w[D].take(ContentKeys.signed(copy(wraps = extra), keysOf(C))))
        // Signed, to the right readers, but the copy of this phone does not open, or is not the key announced.
        val garbled = good.wraps.map { if (it.recipient == D) KeyBatch.Wrap(D, it.sealed.reversedArray()) else it }
        assertEquals(KeyOffer.DAMAGED, w[D].take(ContentKeys.signed(copy(wraps = garbled), keysOf(C))))
        assertEquals(KeyOffer.DAMAGED, w[D].take(ContentKeys.signed(copy(keyId = good.keyId + 1), keysOf(C))))
        // For readers that never were: G1 plus an administrator who is not in it. It waits, for ever.
        val state = w[C].state
        val withB = (state.readersOf(G1) + state.members.getValue(B)).sortedBy { it.id.toULong() }
        val invented = Readers(state.ensembleId, G1, withB)
        val fake = ContentKeys.issue(invented, keysOf(B)).batch
        assertEquals(KeyOffer.UNKNOWN_READERS, w[D].take(fake))
        assertEquals(0, w[D].ring.retry(w[D].replica))
        // For another ensemble.
        assertEquals(KeyOffer.WRONG_ENSEMBLE, w[D].take(copy(ensembleId = good.ensembleId + 1)))
        assertEquals(KeyOffer.WRONG_ENSEMBLE, ContentKeys.check(copy(ensembleId = good.ensembleId + 1), readers))
        // None of these gave D a key; the good one does, once.
        assertNull(w[D].ring.sealingKey(w[D].state, G1))
        assertEquals(KeyOffer.ACCEPTED, w[D].take(good))
        assertEquals(KeyOffer.DUPLICATE, w[D].take(good))
        assertEquals(good.keyId, w[D].ring.sealingKey(w[D].state, G1)?.id)
        // A phone that left forgets everything.
        w[D].ring.clear()
        assertNull(w[D].ring.find(G1, good.keyId))
    }

    @Test
    fun `a key batch survives the format, and what is not one is refused`() {
        val w = world()
        val batch = w[A].ring.batchesFor(w[A].state).first { it.unit == ROOT }
        val bytes = HierarchyWire.encodeKeyBatch(batch)
        assertEquals(HierarchyWire.Kind.KEYS, HierarchyWire.kindOf(bytes))
        // Header, ensemble, unit, readers, key, issuer, ephemeral key, count, one copy per reader, signature.
        assertEquals(2 + 8 + 8 + 16 + 8 + 8 + 65 + 2 + 6 * (8 + 48) + 64, bytes.size)
        val back = HierarchyWire.decodeKeyBatch(bytes)
        assertContentEquals(bytes, HierarchyWire.encodeKeyBatch(back))
        assertEquals(KeyOffer.ACCEPTED, ContentKeys.check(back, w[A].state.readers(ROOT)!!))

        fun refused(wrong: KeyBatch) {
            val written = HierarchyWire.encodeKeyBatch(wrong)
            assertFailsWith<WireException> { HierarchyWire.decodeKeyBatch(written) }
        }

        fun copy(
            wraps: List<KeyBatch.Wrap> = batch.wraps,
            issuer: Long = batch.issuer,
            ephemeral: ByteArray = batch.ephemeral,
        ) = with(batch) { KeyBatch(ensembleId, unit, readersTag, keyId, issuer, ephemeral, wraps, signature) }
        refused(copy(wraps = emptyList()))
        refused(copy(wraps = batch.wraps.reversed()))
        refused(copy(wraps = batch.wraps + batch.wraps.last()))
        refused(copy(issuer = 0))
        refused(copy(ephemeral = ByteArray(P256.POINT_BYTES)))
        assertFailsWith<WireException> { HierarchyWire.decodeKeyBatch(bytes + 0) }
        assertFailsWith<WireException> { HierarchyWire.decodeKeyBatch(bytes.copyOf(bytes.size - 1)) }
    }

    @Test
    fun `the readers of a unit have one name, which changes with every change of the list`() {
        val a = deployed()
        val state = a.state
        val root = state.readers(ROOT)!!
        assertEquals(listOf(A, B, C, D, E, F), root.members.map { it.id })
        assertEquals(Readers.TAG_BYTES, root.tag.size)
        assertEquals(root, a.copyFor(B).state.readers(ROOT))
        assertEquals(listOf(ROOT, G1, G2), state.allReaders().map { it.unit })
        assertFalse(root.tag.contentEquals(state.readers(G1)!!.tag))
        assertNull(state.readers(G3), "no such unit")
        assertNull(a.copyFor(A).apply { must(Change.CreateGroup(G3, "Vide")) }.state.readers(G3), "nobody in it")
        // Someone comes in, someone goes out: another name. Back to the same list: the same name again.
        val grown = a.copyFor(A).apply { must(admit(H)) }
        assertEquals(root, grown.state.readers(ROOT), "who waits is not a reader")
        grown.must(Change.AssignMember(H, G1))
        assertNotEquals(root, grown.state.readers(ROOT))
        grown.must(Change.UnassignMember(H))
        assertEquals(root, grown.state.readers(ROOT))
        // The copy of the operations remembers the lists it went through: a late batch is checked against them.
        assertNotNull(grown.readers(ROOT, root.tag))
        val withH = a.copyFor(A)
        withH.must(admit(H))
        withH.must(Change.AssignMember(H, G1))
        assertEquals(7, grown.readers(ROOT, withH.state.readers(ROOT)!!.tag)?.members?.size)
        assertNull(grown.readers(G1, root.tag))
        assertNull(grown.readers(ROOT, ByteArray(Readers.TAG_BYTES)))
    }

    @Test
    fun `who hands out the key of a unit first - its leader, its deputy, then the others`() {
        val state =
            deployed()
                .apply {
                    must(leader(ROOT, E))
                    must(deputy(ROOT, F))
                    must(leader(G1, D))
                    must(Change.AddAdmin(D))
                    must(admit(H))
                }.state
        // The ensemble: its leader, its deputy, the creator, the administrators, then everyone by identifier.
        assertEquals(listOf(E, F, A, B, D, C), state.keyIssuers(ROOT))
        assertEquals(listOf(D, C), state.keyIssuers(G1))
        assertEquals(listOf(E, F), state.keyIssuers(G2))
        assertEquals(emptyList(), state.keyIssuers(G3))
        assertFalse(H in state.keyIssuers(ROOT))
    }

    @Test
    fun `R10 a dissolved ensemble has no readers - nothing is sealed, nothing is handed out`() {
        val w = world()
        val before = w[C].frame(ShareKind.POSITION, ROOT, "avant la fin")
        w[A].replica.must(Change.Dissolve)
        w.spread()
        for (device in w.devices.values) {
            assertEquals(emptyList(), device.ring.missing(device.state))
            assertNull(device.ring.sealingKey(device.state, ROOT))
            assertNull(device.ring.issue(device.state, ROOT))
            assertEquals(emptyList(), device.ring.batchesFor(device.state))
            assertEquals(ShareRefusal.NOT_ALLOWED, device.sealRefusal(ShareKind.POSITION, ROOT, "après la fin"))
        }
        // What was sealed before is no longer shown: nobody reads the ensemble any more.
        assertEquals(ShareRefusal.NOT_A_READER, w[D].refusal(before))
    }
}
