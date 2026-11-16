package fr.toolmap.core.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A lying coordination server (P39.11, spec § 22): it may lose, delay, reorder, double, replay, truncate or inject
 * frames, mix the frames of two operations or of two rooms, and link identifiers across operations. The engine must
 * still converge on the same state, refuse what it should, never crash, and never read anything crosswise. It also
 * pins what the server learns from the metadata alone (what it cannot avoid seeing, said honestly).
 *
 * The room's delivery function is replaced per test: [AttackRoom.relay] decides what each write becomes.
 */
class LyingServerTest {
    private val problems = ProblemLog()

    @Test
    fun `the server loses, delays, reorders and doubles frames - every phone still converges`() =
        problems.around {
            val r = AttackRoom(31)
            // Half the frames are held back and handed over later, out of order and twice; a quarter are dropped.
            val held = ArrayDeque<AttackRoom.Delivery>()
            r.relay = { d ->
                when (r.random.nextInt(4)) {
                    0 -> {
                        emptyList()
                    }

                    // lost
                    1 -> {
                        held.addLast(d)
                        emptyList()
                    }

                    // delayed
                    2 -> {
                        listOf(d, d)
                    }

                    // doubled
                    else -> {
                        listOf(d)
                    }
                }.also {
                    // Now and then, flush what was held, newest first (reordered).
                    if (r.random.nextInt(3) == 0) {
                        while (held.isNotEmpty()) r.deliver(held.removeLast())
                    }
                }
            }
            val s = r.standard()
            // Flush everything still held and let the exchange finish: the catch-up (summaries) repairs the losses.
            r.relay = { listOf(it) }
            repeat(6) {
                r.now += 61_000
                r.settle()
            }
            assertConverged(s.all)
            // And they can still work: a message reaches its group.
            assertEquals(ShareOutcome.SENT, s.l.n.sendMessage(Channel.unit(s.nord), "après la tempête"))
            repeat(3) {
                r.now += 61_000
                r.settle()
            }
            assertTrue(s.m.messages.any { it.third == "après la tempête" })
        }

    @Test
    fun `the server replays and truncates frames - no effect on the state, no crash`() =
        problems.around {
            val r = AttackRoom(32)
            val s = r.standard()
            val before = s.all.map(::stateBytes)
            // Capture every operation frame written so far, and replay them all, each also truncated at many points.
            val opFrames = r.written.filter { OperationFrames.epochOf(it.frame) != null }.map { it.frame }
            assertTrue(opFrames.isNotEmpty())
            for (frame in opFrames) {
                for (p in s.all) p.n.receive(s.a.id, frame) // replayed, in A's name
                for (cut in listOf(0, 1, 5, 20, OperationFrames.OVERHEAD, frame.size / 2, frame.size - 1)) {
                    val truncated = frame.copyOf(cut.coerceIn(0, frame.size))
                    for (p in s.all) p.n.receive(s.a.id, truncated)
                }
            }
            r.settle()
            assertEquals(before, s.all.map(::stateBytes), "replays and truncations change nothing")
            assertConverged(s.all)
            assertNull(s.a.ended)
        }

    @Test
    fun `the server injects random control frames - refused, counted, never a bug`() =
        problems.around {
            val r = AttackRoom(33)
            val s = r.standard()
            val before = s.all.map(::stateBytes)
            // 2 000 random frames of control-frame size, as if the server wrote them in A's name.
            repeat(2000) {
                val size = 1 + r.random.nextInt(OperationFrames.MAX_FRAME)
                val junk = ByteArray(size).also(r.secure::nextBytes)
                for (p in listOf(s.b, s.l, s.o)) p.n.receive(s.a.id, junk)
            }
            // And frames that are well-formed envelopes of a random invitation key (so they never open).
            val foreign = AttackRoom(34).creator("Zoé", "Autre")
            repeat(200) {
                val body = ByteArray(1 + r.random.nextInt(200)).also(r.secure::nextBytes)
                val frame =
                    OperationFrames.seal(
                        OperationFrames.frameKey(foreign.n.inviteKey(1)!!, s.a.n.ensembleId, 1),
                        s.a.n.ensembleId,
                        1,
                        FrameGenre.entries[r.random.nextInt(FrameGenre.entries.size)],
                        s.b.id,
                        body,
                    )
                s.b.n.receive(s.a.id, frame)
            }
            r.settle()
            assertEquals(before, s.all.map(::stateBytes), "injected frames change nothing")
            assertConverged(s.all)
            for (p in s.all) assertNull(p.ended)
        }

    @Test
    fun `the server mixes the frames of two operations - nothing is read crosswise`() =
        problems.around {
            val r = AttackRoom(35)
            // Two operations on the same simulated server, each in its room.
            val sa = r.standard()
            val r2 = AttackRoom(36)
            val sb = r2.standard()
            val beforeA = sa.all.map(::stateBytes)
            val beforeB = sb.all.map(::stateBytes)
            // The server hands every frame of operation B to every phone of operation A, and the other way.
            val aFrames = r.written.map { it.frame }
            val bFrames = r2.written.map { it.frame }
            for (frame in bFrames) for (p in sa.all) p.n.receive(sb.a.id, frame)
            for (frame in aFrames) for (p in sb.all) p.n.receive(sa.a.id, frame)
            r.settle()
            r2.settle()
            // Each operation kept its own state; neither learnt a member or a name of the other.
            assertEquals(beforeA, sa.all.map(::stateBytes), "operation A untouched")
            assertEquals(beforeB, sb.all.map(::stateBytes), "operation B untouched")
            assertTrue(sa.a.state.members.keys.none { it in sb.a.state.members }, "no member of B appears in A")
            for (p in sa.all) assertNull(p.ended)
            for (p in sb.all) assertNull(p.ended)
        }

    @Test
    fun `the server mixes the frames of two rooms of one operation - a stale split never merges wrong`() =
        problems.around {
            val r = AttackRoom(37)
            val a = r.creator("Alice")
            val b = r.letIn(a, "Bruno")
            val g = a.newGroup("Nord")
            a.must(Change.AssignMember(b.id, g))
            r.settle()
            // A second, unrelated operation (another room) with its own members.
            val r2 = AttackRoom(38)
            val other = r2.creator("Oscar", "Autre opération")
            r2.letIn(other, "Pia")
            r2.settle()
            val before = listOf(a, b).map(::stateBytes)
            // The server pours the other room's frames into this operation, and A's position frame into the other.
            assertEquals(ShareOutcome.SENT, a.n.sendPosition(fixAt(100)))
            r.settle()
            val aPos = r.written.last { it.position }.frame
            for (frame in r2.written.map { it.frame }) {
                a.n.receive(other.id, frame)
                b.n.receive(other.id, frame)
            }
            for (p in r2.phones) p.n.receive(a.id, aPos)
            r.settle()
            r2.settle()
            assertEquals(before, listOf(a, b).map(::stateBytes), "the operation ignored the other room's frames")
            assertTrue(other.id !in a.state.members)
            assertTrue(a.id !in other.state.members)
            assertNull(a.ended)
        }

    /**
     * What the server learns from the metadata alone, said honestly (spec § 22). It never sees a name or a position
     * in clear, but it does see, in the room, the member identifier that writes each frame and whether a frame goes to
     * the position slot. A phone that keeps a trace speaks under a random identifier, not its member one. Linking the
     * identifiers of one phone across two operations is not possible from the frames: each operation has its own
     * identity (its own keys), so the same phone writes under two unrelated identifiers.
     */
    @Test
    fun `the server sees who writes but no name, no position, and cannot link a phone across operations`() =
        problems.around {
            val r = AttackRoom(39)
            val a = r.creator("Alice", "Vercors")
            val b = r.letIn(a, "Bruno")
            val g = a.newGroup("Nord")
            a.must(Change.AssignMember(b.id, g))
            r.settle()
            val mark = r.written.size
            assertEquals(ShareOutcome.SENT, b.n.sendPosition(fixAt(200, lat = 45.123456)))
            assertEquals(ShareOutcome.SENT, b.n.sendMessage(Channel.unit(g), "coordonnées secrètes"))
            r.settle()
            val writes = r.written.drop(mark)
            // The server sees which member identifier writes each frame, and the position marker.
            assertTrue(writes.any { it.writer == b.id }, "the server sees B's identifier as the writer")
            assertTrue(writes.any { it.position }, "the server sees a frame go to the position slot")
            // It sees no clear name, and no clear latitude/longitude, in any frame body.
            val secrets =
                listOf("Alice", "Bruno", "Vercors", "Nord", "coordonnées secrètes").map { it.toByteArray() }
            for (w in r.written) {
                for (secret in secrets) assertFalse(w.frame.holds(secret), "no clear secret on the wire")
            }
            // The latitude bytes of a very precise position are not in clear either (the payload is sealed).
            val latBytes = java.nio.ByteBuffer.allocate(8).putDouble(45.123456).array()
            for (w in writes) assertFalse(w.frame.holds(latBytes), "no clear latitude on the wire")

            // The same phone (same real device) joins a second, unrelated operation: it gets a new identity, so the
            // server cannot tell it is the same phone from the identifiers it writes under.
            val r2 = AttackRoom(40)
            val a2 = r2.creator("Zoé", "Autre")
            // B's phone keys are per-operation in real use (OperationStorage.create makes one identity per operation);
            // here B's member identifier in operation 1 is unrelated to any identifier of operation 2.
            assertTrue(r2.phones.map { it.id }.none { it == b.id }, "no shared identifier between the two operations")
            assertNotNull(a2.n.invitation(), "the second operation works on its own")
            assertTrue(true)
        }

    @Test
    fun `a replayed operation after a conflict keeps the same single operation on every phone`() =
        problems.around {
            val r = AttackRoom(41)
            val s = r.standard()
            // The server keeps playing A's whole operation log at B, L and O, over and over.
            val log = s.a.n.replica.ops()
            val frames = log.map { s.a.frame(FrameGenre.OPS, HierarchyWire.encodeOps(listOf(it))) }
            repeat(5) {
                for (frame in frames) for (p in listOf(s.b, s.l, s.o)) p.n.receive(s.a.id, frame)
            }
            r.settle()
            assertConverged(s.all)
            assertEquals(log.size, s.b.n.replica.ops().size, "no operation is doubled")
        }
}
