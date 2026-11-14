package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.WireException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a member finds when it arrives or comes back (`docs/specs/group-hierarchy.md` § 8.7, the user's answer S6): the
 * waypoints and sketches still shown on the phones of the others, within what its place lets it see; neither old
 * messages nor old positions.
 */
class SharedObjectsTest {
    /** A phone that may share there seals again everything it holds for [channel]; [to] takes it in. */
    private fun World.resend(
        from: Long,
        channel: Channel,
        to: Long,
    ): List<ObjectVerdict> =
        this[from].objects.resend(channel).map { record ->
            this[to].takeObject(this[from].frame(record.kind, channel, SharedRecords.encode(record)))
        }

    @Test
    fun `S6 a member just placed is sent the waypoints and sketches still shown, within what its place lets it see`() {
        val w = commanded()
        // Before H is placed: objects in the group it will join, in the others, and at every level.
        val rendezvous = w.post(E, ShareKind.WAYPOINT, Channel.unit(G2), 1, "rendez-vous du groupe 2")
        w.post(F, ShareKind.SKETCH, Channel.unit(G2), 1, "croquis de F")
        w.post(B, ShareKind.WAYPOINT, Channel.ENSEMBLE, 1, "poste de commandement")
        w.post(B, ShareKind.SKETCH, Channel.down(G2), 2, "zone du groupe 2")
        w.post(C, ShareKind.WAYPOINT, Channel.unit(G1), 1, "du groupe 1")
        w.post(E, ShareKind.SKETCH, Channel.up(G2), 2, "remonté par le chef du groupe 2")
        val oldMessage = w[E].frame(ShareKind.MESSAGE, Channel.unit(G2), "ancien message")
        val oldPosition = w[E].frame(ShareKind.POSITION, Channel.ENSEMBLE, "ancienne position")
        assertEquals(listOf("rendez-vous du groupe 2", "croquis de F"), w[F].shown(Channel.unit(G2)))
        assertEquals(emptyList(), w[H].objects.shown(w[H].state, H), "who waits is shown nothing")

        val before = w[B].state
        w[B].replica.must(Change.AssignMember(H, G2))
        w.spread()
        w.handOutMissing()
        // What its place lets it see: the ensemble, its group, what comes down to its group.
        val state = w[H].state
        // Every phone reads in its own copy who arrived where: that is whom to send again to.
        assertEquals(
            mapOf(Channel.ENSEMBLE to listOf(H), Channel.unit(G2) to listOf(H), Channel.down(G2) to listOf(H)),
            state.arrivals(before),
        )
        assertEquals(emptyMap(), state.arrivals(state))
        assertEquals(listOf(Channel.ENSEMBLE, Channel.unit(G2), Channel.down(G2)), state.channelsReadBy(H))
        assertEquals(emptyList(), w[H].objects.shown(state, H), "nothing yet: the old frames were sealed for others")
        // Each channel is sent again by someone who may share there: any member for the group, those who command
        // the ensemble for the rest.
        assertEquals(listOf(E, F, H), state.resenders(ShareKind.WAYPOINT, Channel.unit(G2)))
        assertEquals(listOf(A, B), state.resenders(ShareKind.WAYPOINT, Channel.ENSEMBLE))
        assertEquals(listOf(A, B), state.resenders(ShareKind.SKETCH, Channel.down(G2)))
        assertEquals(listOf(ObjectVerdict.STORED, ObjectVerdict.STORED), w.resend(F, Channel.unit(G2), H))
        assertEquals(listOf(ObjectVerdict.STORED), w.resend(B, Channel.ENSEMBLE, H))
        assertEquals(listOf(ObjectVerdict.STORED), w.resend(A, Channel.down(G2), H))
        assertEquals(
            setOf("poste de commandement", "rendez-vous du groupe 2", "croquis de F", "zone du groupe 2"),
            w[H].objects.shown(state, H).map { String(it.body) }.toSet(),
        )
        // The record is the one its author signed: F sent it again, E is still the one who made it.
        val received = w[H].objects.find(rendezvous.ref)!!
        assertEquals(E, received.author)
        assertContentEquals(rendezvous.signature, received.signature)
        // Sent twice, nothing changes.
        assertEquals(listOf(ObjectVerdict.DUPLICATE, ObjectVerdict.DUPLICATE), w.resend(E, Channel.unit(G2), H))
        // Not what its place does not let it see: the other group, what its leader sent up.
        for (channel in listOf(Channel.unit(G1), Channel.up(G2))) {
            val record = w[if (channel.scope == G1) C else E].objects.resend(channel).single()
            val frame = w[if (channel.scope == G1) C else E].frame(record.kind, channel, SharedRecords.encode(record))
            assertEquals(ShareRefusal.NO_KEY, w[H].refusal(frame), "$channel")
        }
        // A plain member cannot send again what was shared with everyone: it may not share there.
        val everyone = w[F].objects.resend(Channel.ENSEMBLE).single()
        val refused = w[F].seal(ShareKind.WAYPOINT, Channel.ENSEMBLE, SharedRecords.encode(everyone))
        assertEquals(ShareRefusal.NOT_ALLOWED, (refused as SealResult.Refused).reason)
        // Neither old messages nor old positions: nobody keeps them to send again, and the old frames stay closed.
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(oldMessage))
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(oldPosition))
        val message = w[E].share(w[E].frame(ShareKind.MESSAGE, Channel.unit(G2), "un message n'est pas un objet"))
        assertEquals(ObjectVerdict.MALFORMED, w[E].objects.accept(message, w[E].replica::signingKeyOf))
        // The two phones now hold the same thing for the group: nothing more to send.
        assertEquals(w[E].objects.digest(Channel.unit(G2)), w[H].objects.digest(Channel.unit(G2)))
        assertTrue(w[E].objects.digest(Channel.unit(G2)) != w[E].objects.digest(Channel.down(G2)))
    }

    @Test
    fun `S6 a member who comes back learns what changed and what was taken away meanwhile`() {
        val w = commanded()
        val first = w.post(E, ShareKind.WAYPOINT, Channel.unit(G2), 1, "rendez-vous à 14 h")
        val sketch = w.post(F, ShareKind.SKETCH, Channel.unit(G2), 7, "croquis de F")
        val post = w.post(B, ShareKind.WAYPOINT, Channel.ENSEMBLE, 1, "poste de commandement")
        assertEquals(listOf("rendez-vous à 14 h", "croquis de F"), w[F].shown(Channel.unit(G2)))
        // F is away. The waypoint changes, the leader of the group takes the sketch of F away, the administrator
        // takes its own waypoint away and makes another one.
        val second = w.post(E, ShareKind.WAYPOINT, Channel.unit(G2), 1, "rendez-vous à 16 h", F)
        assertEquals(2, second.revision)
        w.deliver(E, SharedRecords.remove(keysOf(E), w.source.ensembleId, sketch), F)
        w.deliver(B, SharedRecords.remove(keysOf(B), w.source.ensembleId, post), F)
        w.post(B, ShareKind.WAYPOINT, Channel.ENSEMBLE, 2, "nouveau poste de commandement", F)
        assertEquals(listOf("rendez-vous à 16 h"), w[E].shown(Channel.unit(G2)))
        assertEquals(listOf("rendez-vous à 14 h", "croquis de F"), w[F].shown(Channel.unit(G2)), "F did not hear")
        assertTrue(w[E].objects.digest(Channel.unit(G2)) != w[F].objects.digest(Channel.unit(G2)))
        // F comes back: the phones present send again what they hold, removals included.
        assertEquals(listOf(ObjectVerdict.STORED, ObjectVerdict.STORED), w.resend(E, Channel.unit(G2), F))
        assertEquals(listOf(ObjectVerdict.STORED, ObjectVerdict.STORED), w.resend(B, Channel.ENSEMBLE, F))
        assertEquals(listOf("rendez-vous à 16 h"), w[F].shown(Channel.unit(G2)))
        assertEquals(listOf("nouveau poste de commandement"), w[F].shown(Channel.ENSEMBLE))
        assertEquals(w[E].objects.digest(Channel.unit(G2)), w[F].objects.digest(Channel.unit(G2)))
        // The other way round, F has nothing newer: what it sends again is older, and changes nothing on E.
        w[F].objects.clear()
        w[F].objects.accept(first, w[F].replica::signingKeyOf)
        assertEquals(listOf(ObjectVerdict.OLDER), w.resend(F, Channel.unit(G2), E))
        assertEquals(listOf("rendez-vous à 16 h"), w[E].shown(Channel.unit(G2)))
        // What was taken away does not come back from a phone that still had it.
        w[F].objects.accept(sketch, w[F].replica::signingKeyOf)
        assertTrue(ObjectVerdict.OLDER in w.resend(F, Channel.unit(G2), E))
        assertNull(w[E].shown(Channel.unit(G2)).firstOrNull { it == "croquis de F" })
        // Its author makes it again: a newer state than its removal.
        val removal = w[E].objects.find(sketch.ref)!!
        assertTrue(removal.deleted && removal.editor == E && removal.author == F)
        val again =
            SharedRecords.write(
                keysOf(F),
                w.source.ensembleId,
                ShareKind.SKETCH,
                Channel.unit(G2),
                7,
                "refait".toByteArray(),
                removal,
            )
        w.deliver(F, again)
        assertEquals(listOf("rendez-vous à 16 h", "refait"), w[E].shown(Channel.unit(G2)))
    }

    @Test
    fun `S6 what someone shared stays shown after it left, and whoever may share there takes it away`() {
        val w = commanded()
        val waypoint = w.post(E, ShareKind.WAYPOINT, Channel.unit(G2), 1, "point laissé par E")
        // E leaves the ensemble; H is then placed in its group.
        w[E].replica.must(Change.RemoveMember(E))
        w[B].replica.must(Change.AssignMember(H, G2))
        w.spread()
        w.handOutMissing(E)
        assertNull(w[F].state.members[E])
        // F still shows it and sends it again; H checks the signature of E with the key E was let in with.
        assertEquals(listOf("point laissé par E"), w[F].shown(Channel.unit(G2)))
        assertEquals(listOf(ObjectVerdict.STORED), w.resend(F, Channel.unit(G2), H))
        assertEquals(E, w[H].objects.find(waypoint.ref)?.author)
        // Its author is gone: a member of the group takes it away for everyone.
        w.deliver(H, SharedRecords.remove(keysOf(H), w.source.ensembleId, waypoint))
        assertEquals(emptyList(), w[F].shown(Channel.unit(G2)))
        assertEquals(emptyList(), w[H].shown(Channel.unit(G2)))
        // A member moved out of a group is no longer shown what was shared there.
        w.post(C, ShareKind.SKETCH, Channel.unit(G1), 3, "croquis du groupe 1")
        assertEquals(listOf("croquis du groupe 1"), w[D].shown(Channel.unit(G1)))
        w[B].replica.must(Change.AssignMember(D, G2))
        w.spread(E)
        assertEquals(emptyList(), w[D].objects.shown(w[D].state, D).filter { it.channel == Channel.unit(G1) })
        w[D].objects.prune(w[D].state, D)
        assertEquals(emptyList(), w[D].shown(Channel.unit(G1)))
        w[D].objects.clear()
        assertEquals(emptyList(), w[D].objects.resend(Channel.unit(G2)))
    }

    @Test
    fun `a record that is not what its signature says is refused`() {
        val w = commanded()
        val id = w.source.ensembleId
        val channel = Channel.unit(G2)
        val real = SharedRecords.write(keysOf(E), id, ShareKind.WAYPOINT, channel, 1, "vrai".toByteArray())
        val keyOf = w[F].replica::signingKeyOf

        fun copy(
            author: Long = real.author,
            editor: Long = real.editor,
            revision: Long = real.revision,
            deleted: Boolean = false,
            body: ByteArray = real.body,
            kind: ShareKind = real.kind,
            where: Channel = real.channel,
            signature: ByteArray = real.signature,
        ) = SharedRecord(kind, where, author, real.objectId, revision, editor, deleted, body, signature)
        assertEquals(ObjectVerdict.STORED, w[F].objects.accept(real, keyOf))
        assertEquals(ObjectVerdict.DUPLICATE, w[F].objects.accept(real, keyOf))
        val fresh = SharedObjects(id)
        // Changed on the way: the body, the revision, the kind, the place, who made it.
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(body = "faux".toByteArray()), keyOf))
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(revision = 9), keyOf))
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(kind = ShareKind.SKETCH), keyOf))
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(where = Channel.ENSEMBLE), keyOf))
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(author = F), keyOf))
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(deleted = true, body = ByteArray(0)), keyOf))
        // F writes a content in the name of E: only its author changes an object.
        val byF = SharedRecords.write(keysOf(F), id, ShareKind.WAYPOINT, channel, 1, "de F".toByteArray())
        assertEquals(ObjectVerdict.FORGED, fresh.accept(copy(editor = F, signature = byF.signature), keyOf))
        // Signed by someone the ensemble never let in; for another ensemble.
        val stranger = SharedRecords.write(keysOf(OUTSIDER), id, ShareKind.WAYPOINT, channel, 1, "x".toByteArray())
        assertEquals(ObjectVerdict.UNKNOWN_EDITOR, fresh.accept(stranger, keyOf))
        val elsewhere = SharedRecords.write(keysOf(E), id + 1, ShareKind.WAYPOINT, channel, 1, "vrai".toByteArray())
        assertEquals(ObjectVerdict.FORGED, fresh.accept(elsewhere, keyOf))
        assertEquals(emptyList(), fresh.resend(channel))
        // Not a kind that stays; a share whose payload is no record.
        assertFailsWith<IllegalArgumentException> {
            SharedRecords.write(keysOf(E), id, ShareKind.MESSAGE, channel, 1, ByteArray(0))
        }
        assertEquals(ObjectVerdict.MALFORMED, fresh.accept(copy(kind = ShareKind.POSITION), keyOf))
        assertEquals(ObjectVerdict.MALFORMED, fresh.accept(Share(ShareKind.SKETCH, channel, E, ByteArray(40)), keyOf))
        // Sealed in another place than the one it was signed for: the frame opens, the record is refused.
        w.handOutMissing()
        val moved = w[E].frame(ShareKind.WAYPOINT, Channel.up(G2), SharedRecords.encode(real))
        assertEquals(ObjectVerdict.FORGED, w[B].takeObject(moved))
        // At the same revision a removal wins over a content, on every phone, whatever came first.
        val content = SharedRecords.write(keysOf(E), id, ShareKind.WAYPOINT, channel, 1, "suite".toByteArray(), real)
        val removal = SharedRecords.remove(keysOf(F), id, real)
        for (order in listOf(listOf(content, removal), listOf(removal, content))) {
            val phone = SharedObjects(id).apply { accept(real, keyOf) }
            for (record in order) phone.accept(record, keyOf)
            assertEquals(emptyList(), phone.shown(channel))
            assertEquals(F, phone.find(real.ref)?.editor)
        }
    }

    @Test
    fun `a record survives its format, and bytes that are no record are refused without anything else`() {
        val id = 77L
        val channel = Channel.down(G1)
        val record = SharedRecords.write(keysOf(B), id, ShareKind.SKETCH, channel, -5, "zone à couvrir".toByteArray())
        val payload = SharedRecords.encode(record)
        assertEquals(SharedRecords.OVERHEAD_BYTES + "zone à couvrir".toByteArray().size, payload.size)
        assertEquals(8 + 8 + 4 + 8 + 1 + 64, SharedRecords.OVERHEAD_BYTES)
        val back = SharedRecords.decode(ShareKind.SKETCH, channel, payload)
        assertContentEquals(payload, SharedRecords.encode(back))
        assertTrue(SharedRecords.verify(id, back, publicOf(B).signing))
        val gone = SharedRecords.remove(keysOf(A), id, record)
        val goneBack = SharedRecords.decode(ShareKind.SKETCH, channel, SharedRecords.encode(gone))
        assertTrue(goneBack.deleted && goneBack.editor == A && goneBack.author == B && goneBack.revision == 2L)
        assertTrue(SharedRecords.verify(id, goneBack, publicOf(A).signing))
        // The largest body fits one share; one byte more does not.
        val large = ByteArray(SharedRecords.MAX_BODY)
        val full = SharedRecords.encode(SharedRecords.write(keysOf(B), id, ShareKind.SKETCH, channel, 1, large))
        assertEquals(ShareSeal.MAX_PAYLOAD, full.size)
        assertFailsWith<IllegalArgumentException> {
            SharedRecords.write(keysOf(B), id, ShareKind.SKETCH, channel, 1, ByteArray(SharedRecords.MAX_BODY + 1))
        }
        // Cut anywhere before the body, a field that cannot be: refused.
        for (length in 0 until SharedRecords.OVERHEAD_BYTES) {
            assertFailsWith<WireException>("cut at $length") {
                SharedRecords.decode(ShareKind.SKETCH, channel, payload.copyOf(length))
            }
        }
        val zeroAuthor = payload.copyOf().also { it.fill(0, 0, 8) }
        val zeroObject = payload.copyOf().also { it.fill(0, 8, 16) }
        val zeroRevision = payload.copyOf().also { it.fill(0, 16, 20) }
        val unknownFlag = payload.copyOf().also { it[28] = 2 }
        val removedWithBody = payload.copyOf().also { it[28] = 1 }
        for (bad in listOf(zeroAuthor, zeroObject, zeroRevision, unknownFlag, removedWithBody)) {
            assertFailsWith<WireException> { SharedRecords.decode(ShareKind.SKETCH, channel, bad) }
        }
        // Noise: a record or a refusal, and a store never keeps what is not signed by someone it knows.
        val random = Random(20261114)
        val store = SharedObjects(id)
        repeat(2000) {
            val noise = random.nextBytes(random.nextInt(160))
            val verdict = store.accept(Share(ShareKind.SKETCH, channel, B, noise)) { publicOf(B).signing }
            assertTrue(verdict == ObjectVerdict.MALFORMED || verdict == ObjectVerdict.FORGED, "$verdict")
        }
        assertEquals(emptyList(), store.resend(channel))
    }

    @Test
    fun `a channel keeps a bounded number of objects, the oldest removals go first`() {
        val id = 5L
        val channel = Channel.unit(G1)
        val store = SharedObjects(id)
        val keyOf: (Long) -> ByteArray? = { publicOf(it).signing }
        val records =
            List(SharedObjects.MAX_PER_CHANNEL) { n ->
                SharedRecords.write(keysOf(C), id, ShareKind.WAYPOINT, channel, n + 1L, byteArrayOf(n.toByte()))
            }
        for (record in records) assertEquals(ObjectVerdict.STORED, store.accept(record, keyOf))
        val extra = SharedRecords.write(keysOf(C), id, ShareKind.WAYPOINT, channel, 5000, ByteArray(0))
        assertEquals(ObjectVerdict.FULL, store.accept(extra, keyOf))
        // Changing or removing an object that is there always goes through.
        assertEquals(ObjectVerdict.STORED, store.accept(SharedRecords.remove(keysOf(C), id, records[3]), keyOf))
        assertEquals(SharedObjects.MAX_PER_CHANNEL - 1, store.shown(channel).size)
        // A removal makes room for a new object: the removal is what is forgotten.
        assertEquals(ObjectVerdict.STORED, store.accept(extra, keyOf))
        assertNull(store.find(records[3].ref))
        assertEquals(SharedObjects.MAX_PER_CHANNEL, store.shown(channel).size)
    }
}
