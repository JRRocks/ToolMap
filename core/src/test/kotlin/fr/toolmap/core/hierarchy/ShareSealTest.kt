package fr.toolmap.core.hierarchy

import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** What is shared, sealed for the readers of a channel and signed by its author (spec `group-hierarchy.md` § 8.5). */
class ShareSealTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        assertTrue(
            problems.all {
                it.expected
            },
            "a frame that does not open is not a bug: ${problems.map { it.where }}",
        )
    }

    private fun SealResult.refusal(): ShareRefusal = assertIs<SealResult.Refused>(this).reason

    /** The rules of an ensemble that signs everything but positions. */
    private val unsignedPositions = ShareKind.entries.toSet() - ShareKind.POSITION

    /**
     * What a modified phone would do: seal [text] in the name of [author] with a key [forger] holds, signed by the
     * forger itself when [sign] (it has no other key to sign with).
     */
    private fun forge(
        forger: Device,
        author: Long,
        kind: ShareKind,
        unit: Long,
        text: String,
        sign: Boolean,
    ): ByteArray {
        val key = forger.ring.sealingKey(forger.state, unit)!!
        val header = HierarchyWire.shareHeader(forger.state.ensembleId, Channel.unit(unit), key.id, kind, author)
        val nonce = Random.nextBytes(Aead.NONCE_BYTES)
        val payload = text.toByteArray()
        val plain = if (sign) payload + keysOf(forger.id).sign(header + nonce + payload) else payload
        return header + nonce + Aead.seal(key.sealingKey(author), nonce, header, plain)
    }

    @Test
    fun `every kind of share is sealed for its readers and read back by them`() {
        val w = world()
        w[B].replica.must(leader(G1, C))
        w.spread()
        w.handOutMissing()
        // Who shares what, where, and one of those who read it.
        val shares =
            listOf(
                listOf(D, ShareKind.POSITION, Channel.ENSEMBLE, F),
                listOf(D, ShareKind.SKETCH, Channel.unit(G1), C),
                listOf(D, ShareKind.WAYPOINT, Channel.unit(G1), C),
                listOf(D, ShareKind.MESSAGE, Channel.unit(G1), C),
                listOf(D, ShareKind.ALERT, Channel.unit(G1), C),
                listOf(D, ShareKind.ROSTER, Channel.ENSEMBLE, E),
                listOf(C, ShareKind.SKETCH, Channel.up(G1), A),
                listOf(C, ShareKind.WAYPOINT, Channel.up(G1), B),
                listOf(C, ShareKind.MESSAGE, Channel.up(G1), A),
                listOf(C, ShareKind.ALERT, Channel.LEADERS, B),
                listOf(B, ShareKind.MESSAGE, Channel.ENSEMBLE, F),
                listOf(B, ShareKind.SKETCH, Channel.down(G2), E),
                listOf(B, ShareKind.MESSAGE, Channel.up(G1), C),
                listOf(H, ShareKind.POSITION, Channel.waiting(H), B),
            )
        assertEquals(ShareKind.entries.toSet(), shares.map { it[1] }.toSet(), "every kind")
        assertEquals(Lane.entries.toSet(), shares.map { (it[2] as Channel).lane }.toSet(), "every lane")
        for ((who, what, where, whom) in shares) {
            val author = who as Long
            val kind = what as ShareKind
            val channel = where as Channel
            val reader = whom as Long
            val text = "$kind de $author"
            val frame = w[author].frame(kind, channel, text)
            assertEquals(HierarchyWire.Kind.SHARE, HierarchyWire.kindOf(frame))
            // Clear header, nonce, payload, signature of the author, tag.
            assertEquals(36 + 12 + text.toByteArray().size + P256.SIGNATURE_BYTES + 16, frame.size, "$kind")
            val opened = assertIs<OpenResult.Opened>(w[reader].open(frame), "$kind in $channel").share
            assertEquals(kind, opened.kind)
            assertEquals(channel, opened.channel)
            assertEquals(author, opened.author)
            assertContentEquals(text.toByteArray(), opened.payload)
            assertEquals(text, w[author].read(frame), "its author reads it too")
            // The same share sealed twice does not give the same bytes.
            assertTrue(!frame.contentEquals(w[author].frame(kind, channel, text)))
        }
        assertEquals("", w[C].read(w[D].frame(ShareKind.MESSAGE, G1, "")))
        val large = "x".repeat(ShareSeal.MAX_PAYLOAD)
        assertEquals(large, w[C].read(w[D].frame(ShareKind.SKETCH, G1, large)))
        assertEquals(ShareRefusal.TOO_LARGE, w[D].seal(ShareKind.SKETCH, G1, large + "x").refusal())
    }

    @Test
    fun `R4 R6 what a member may not share is not sealed, and is refused by the others if it is sealed all the same`() {
        val w = world()
        w[B].replica.must(leader(G1, C))
        w[B].replica.must(leader(ROOT, E))
        w.spread()
        w.handOutMissing()
        // Not sealed by an honest phone.
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.MESSAGE, ROOT, "à tous").refusal())
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.SKETCH, ROOT, "à tous").refusal())
        assertEquals(ShareRefusal.NOT_ALLOWED, w[C].seal(ShareKind.MESSAGE, ROOT, "du leader de groupe").refusal())
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.MESSAGE, G2, "chez les voisins").refusal())
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.POSITION, G1, "position au groupe seul").refusal())
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.MESSAGE, G3, "nulle part").refusal())
        // A modified phone holds the key of the ensemble (positions go there) and seals what it may not: it signs
        // with its own key, since it has no other, and every phone refuses it for what its author may do.
        val message = forge(w[D], D, ShareKind.MESSAGE, ROOT, "D écrit à tout l'ensemble", sign = true)
        val sketch = forge(w[D], D, ShareKind.SKETCH, ROOT, "D dessine pour tout l'ensemble", sign = true)
        // Q3, S2: the leader of a group no longer sends to everyone either.
        val fromLeader = forge(w[C], C, ShareKind.SKETCH, ROOT, "le chef de groupe dessine pour tous", sign = true)
        for (reader in listOf(A, B, C, E)) {
            assertEquals(ShareRefusal.NOT_ALLOWED, w[reader].refusal(message))
            assertEquals(ShareRefusal.NOT_ALLOWED, w[reader].refusal(sketch))
            assertEquals(ShareRefusal.NOT_ALLOWED, w[reader].refusal(fromLeader))
        }
        // The ones who may: the leader of the ensemble to everyone, the leader of the group up to it.
        assertEquals("du leader", w[D].read(w[E].frame(ShareKind.MESSAGE, ROOT, "du leader")))
        assertEquals("pour tous", w[D].read(w[E].frame(ShareKind.SKETCH, ROOT, "pour tous")))
        val up = w[C].frame(ShareKind.SKETCH, Channel.up(G1), "croquis remonté")
        assertEquals("croquis remonté", w[E].read(up))
        assertEquals(ShareRefusal.NO_KEY, w[F].refusal(up))
    }

    @Test
    fun `R18 a reader cannot share in the name of another - every share is signed by its author`() {
        val w = world()
        w[B].replica.must(leader(ROOT, E))
        w.spread()
        // D, a plain member, holds the key of the ensemble and gives a false position of C.
        val position = forge(w[D], C, ShareKind.POSITION, ROOT, "fausse position de C", sign = true)
        for (reader in listOf(A, C, F)) assertEquals(ShareRefusal.FORGED, w[reader].refusal(position))
        for (kind in listOf(ShareKind.MESSAGE, ShareKind.SKETCH, ShareKind.WAYPOINT)) {
            // The same phone writes in the name of the leader of the ensemble.
            val signedByForger = forge(w[D], E, kind, ROOT, "ordre du leader", sign = true)
            val unsigned =
                forge(
                    w[D],
                    E,
                    kind,
                    ROOT,
                    "ordre du leader, sans signature, d'au moins soixante-quatre octets de long",
                    sign = false,
                )
            val short = forge(w[D], E, kind, ROOT, "court", sign = false)
            for (reader in listOf(A, C, F)) {
                assertEquals(ShareRefusal.FORGED, w[reader].refusal(signedByForger), "$kind")
                assertEquals(ShareRefusal.FORGED, w[reader].refusal(unsigned), "$kind")
                assertEquals(ShareRefusal.FORGED, w[reader].refusal(short), "$kind")
            }
            // In its own group too: C cannot write as D.
            val inGroup = forge(w[C], D, kind, G1, "D n'a jamais dit cela", sign = true)
            assertEquals(ShareRefusal.FORGED, w[D].refusal(inGroup), "$kind")
        }
        val real = w[E].frame(ShareKind.MESSAGE, ROOT, "rendez-vous au col")
        assertEquals("rendez-vous au col", w[C].read(real))
    }

    @Test
    fun `which shares are signed is data - an ensemble that leaves positions unsigned lets a reader forge one`() {
        // At the start everything is signed: a false position is refused, and each position costs 64 bytes more.
        val strict = world()
        assertEquals(ShareKind.entries.toSet(), strict[A].state.rules.signedShares)
        val refused = forge(strict[D], C, ShareKind.POSITION, ROOT, "fausse position de C", sign = false)
        assertEquals(ShareRefusal.FORGED, strict[E].refusal(refused))
        val real = strict[C].frame(ShareKind.POSITION, ROOT, "vraie position")
        assertEquals(36 + 12 + "vraie position".length + 64 + 16, real.size)
        assertEquals("vraie position", strict[E].read(real))
        // The choice the user did not make (S5): lighter positions, and this known limit. Still data of an ensemble.
        val light = world(HierarchyRules(signedShares = unsignedPositions))
        assertEquals(36 + 12 + "position".length + 16, light[C].frame(ShareKind.POSITION, ROOT, "position").size)
        val forged = forge(light[D], C, ShareKind.POSITION, ROOT, "fausse position de C", sign = false)
        assertEquals("fausse position de C", light[E].read(forged))
        // Messages, sketches and waypoints stay signed there.
        assertEquals(
            ShareRefusal.FORGED,
            light[D].refusal(forge(light[C], D, ShareKind.MESSAGE, G1, "faux", sign = true)),
        )
    }

    @Test
    fun `a phone that had not heard of a change - its signed shares are still read, unsigned ones are not`() {
        val w = world(HierarchyRules(signedShares = unsignedPositions))
        // H comes into G1; C, out of reach, has not heard and goes on with the keys it has.
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread(C)
        w.handOut(A, ROOT, C)
        w.handOut(D, G1, C)
        val message = w[C].frame(ShareKind.MESSAGE, G1, "C, en retard, écrit au groupe")
        val position = w[C].frame(ShareKind.POSITION, ROOT, "position de C, en retard")
        // D holds the former key: the message is signed by C, who may write there: read.
        assertEquals("C, en retard, écrit au groupe", w[D].read(message))
        // In this ensemble positions are not signed: only the key says who sealed one, and the former key is also
        // held by whoever was put out since. Refused until C catches up and seals with the key of now.
        assertEquals(ShareRefusal.STALE_KEY, w[D].refusal(position))
        // Where positions are signed (the start), the late position of C is read like its message.
        val signed = world()
        signed[B].replica.must(Change.AssignMember(H, G1))
        signed.spread(C)
        signed.handOut(A, ROOT, C)
        assertEquals("en retard", signed[D].read(signed[C].frame(ShareKind.POSITION, ROOT, "en retard")))
        // H never held the former keys.
        assertEquals(ShareRefusal.NO_KEY, w[H].refusal(message))
        w.spread()
        for (batch in w[D].ring.batchesFor(w[D].state)) w[C].take(batch)
        assertEquals("à jour", w[H].read(w[C].frame(ShareKind.POSITION, ROOT, "à jour")))
    }

    @Test
    fun `the rights are the ones of now - who lost its post or its group no longer shares with a key it kept`() {
        val w = world()
        w[B].replica.must(leader(G1, C))
        w[B].replica.must(deputy(G1, D))
        w.spread()
        w.handOutMissing()
        val sketch = w[C].frame(ShareKind.SKETCH, Channel.up(G1), "croquis du leader de G1")
        assertEquals("croquis du leader de G1", w[A].read(sketch))
        // C loses its post: the same frame, arriving late on another phone, is refused.
        w[B].replica.must(Change.ClearPost(G1, Post.LEADER))
        w.spread()
        assertEquals(ShareRefusal.NOT_ALLOWED, w[B].refusal(sketch))
        // D is moved to G2 and keeps the key of G1 it had: a late message of G1 is not for it any more.
        val late = w[C].frame(ShareKind.MESSAGE, G1, "pour le groupe 1")
        w[B].replica.must(Change.AssignMember(D, G2))
        w.spread()
        assertEquals(ShareRefusal.NOT_A_READER, w[D].refusal(late))
        // And what D sealed for G1 before is refused by C: D does not write there any more.
        w.handOut(C, G1)
        assertEquals(ShareRefusal.NOT_ALLOWED, w[D].seal(ShareKind.MESSAGE, G1, "encore moi").refusal())
    }

    @Test
    fun `without the key of now nothing is sealed`() {
        val w = world()
        w[B].replica.must(Change.AssignMember(H, G1))
        w.spread()
        assertEquals(ShareRefusal.NO_KEY, w[C].seal(ShareKind.POSITION, ROOT, "pas encore").refusal())
        assertEquals(ShareRefusal.NO_KEY, w[H].seal(ShareKind.MESSAGE, G1, "pas encore").refusal())
        // The phone that needs it makes it, when nobody did before it.
        w.handOut(H, G1)
        val frame = w[H].frame(ShareKind.MESSAGE, G1, "maintenant")
        assertEquals("maintenant", w[C].read(frame))
        // A ring of another ensemble is of no use.
        val other = KeyRing(w[C].state.ensembleId + 1, keysOf(C))
        assertEquals(
            ShareRefusal.WRONG_ENSEMBLE,
            ShareSeal.seal(w[C].state, other, keysOf(C), ShareKind.MESSAGE, G1, ByteArray(0)).refusal(),
        )
        assertEquals(
            ShareRefusal.WRONG_ENSEMBLE,
            assertIs<OpenResult.Refused>(ShareSeal.open(w[C].state, other, frame)).reason,
        )
    }

    @Test
    fun `a damaged or made-up frame is refused, never anything else than a refusal`() {
        val w = world()
        val frame = w[D].frame(ShareKind.MESSAGE, G1, "message intact")
        assertEquals("message intact", w[C].read(frame))
        // Too short, another version, another kind of block, a kind of share that does not exist, no author.
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(ByteArray(0)))
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf(35)))
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf(36 + 12 + 15)))
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf().also { it[0] = 1 }))
        assertEquals(
            ShareRefusal.MALFORMED,
            w[C].refusal(
                frame.copyOf().also {
                    it[1] =
                        HierarchyWire.Kind.OP.code.toByte()
                },
            ),
        )
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf().also { it[27] = 9 }))
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf().also { it.fill(0, 28, 36) }))
        // A lane that does not exist; the lane up from nothing.
        assertEquals(ShareRefusal.MALFORMED, w[C].refusal(frame.copyOf().also { it[10] = 9 }))
        assertEquals(
            ShareRefusal.MALFORMED,
            w[C].refusal(frame.copyOf().also { it[10] = Lane.UP.code.toByte() }.also { it.fill(0, 11, 19) }),
        )
        // For another ensemble; under a key this phone does not hold.
        assertEquals(ShareRefusal.WRONG_ENSEMBLE, w[C].refusal(frame.copyOf().also { it[5] = (it[5] + 1).toByte() }))
        assertEquals(ShareRefusal.NO_KEY, w[C].refusal(frame.copyOf().also { it[21] = (it[21] + 1).toByte() }))
        assertEquals(ShareRefusal.NO_KEY, w[C].refusal(frame.copyOf().also { it[12] = (it[12] + 1).toByte() }))
        // The same group, another lane: another key.
        assertEquals(ShareRefusal.NO_KEY, w[C].refusal(frame.copyOf().also { it[10] = Lane.UP.code.toByte() }))
        // The kind or the author changed in the clear part, the nonce, the sealed part: the key does not open it.
        for (at in listOf(27, 31, 41, 51, frame.size - 1)) {
            val damaged = frame.copyOf().also { it[at] = (it[at].toInt() xor 1).toByte() }
            val reason = w[C].refusal(damaged)
            assertTrue(reason == ShareRefusal.UNDECIPHERABLE || reason == ShareRefusal.MALFORMED, "byte $at: $reason")
        }
        // Any byte changed, any cut, noise: refused.
        val random = Random(20261113)
        for (at in frame.indices) {
            val damaged = frame.copyOf().also { it[at] = (it[at].toInt() xor (1 + random.nextInt(255))).toByte() }
            assertIs<OpenResult.Refused>(w[C].open(damaged), "byte $at")
        }
        for (length in 0 until frame.size) {
            assertIs<OpenResult.Refused>(
                w[C].open(frame.copyOf(length)),
                "cut at $length",
            )
        }
        repeat(500) { assertIs<OpenResult.Refused>(w[C].open(frame.copyOf(2) + random.nextBytes(random.nextInt(150)))) }
    }
}
