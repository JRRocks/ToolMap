package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.FrameGenre
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What one arrival costs the phones of a room (P39.17, `docs/specs/group-hierarchy.md` § 26): an operation of 21
 * members in four groups (with their chiefs, deputies and administrators, every phone having sent a position, as the
 * demonstration of the debug build type), then a 22nd phone asks to come in and is let in. Counted for each phone,
 * from the request to a minute after the admission: the frames the server pushes to it, by genre.
 *
 * Until P39.17, a summary meant for one phone was answered by every phone that heard it, and the room carries
 * everything to everyone: run « CI » n° 243 (2026-11-20), the 22 phones of the demonstration in one process filled the
 * heap of the emulator. The counts of before and after are in `docs/JOURNAL.md` (session 125) and in § 26 of the spec.
 */
class OperationArrivalCostTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink
    private val server = RelayConfig.of("https://example.invalid", "key")

    @BeforeTest
    fun setUp() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun tearDown() {
        Problems.sink = previousSink
        assertTrue(problems.none { !it.expected }, "bugs reported: ${problems.filter { !it.expected }}")
    }

    @Test
    fun `an arrival in a room of 22 pushes fewer than 1 200 frames to each phone already in (7 360 before P39_17)`() {
        val w = World(22_000)
        val phones = List(ROOM) { i -> SimPhone(w, i, "P$i", KeyPool.keys(i, 70L + i), 0L, 500L + i, server) }
        val a = phones[0]
        a.create(Names.hide("Opération Mesure", SeededRandom(1)), Names.hide("Membre 1", SeededRandom(2)))
        w.runFor(2_000)
        val inside = phones.dropLast(1)
        for (p in inside.drop(1)) letIn(w, a, p)

        fun must(change: Change) = check(a.call { it.issue(change) } is IssueResult.Issued) { "$change refused" }
        val groups = (0 until 4).map { g -> 0x100L + g }
        for (g in groups) {
            val card = Names.hide("Groupe $g", SeededRandom(g))
            must(Change.CreateGroup(g, card.hidden))
            a.call { it.learnAndShare(card) }
        }
        for ((i, p) in inside.withIndex()) must(Change.AssignMember(p.id, groups[i % groups.size]))
        for ((gi, g) in groups.withIndex()) {
            val members = inside.filterIndexed { i, _ -> i % groups.size == gi }
            must(Change.SetPost(g, Post.LEADER, members[1].id))
            must(Change.SetPost(g, Post.DEPUTY, members[2].id))
        }
        must(Change.SetPost(HierarchyState.ROOT, Post.LEADER, inside[1].id))
        must(Change.AddAdmin(inside[4].id))
        must(Change.AddAdmin(inside[8].id))
        w.runFor(2 * 60_000L)
        // Every phone sends once, so that the keys of the operation are made before the measure.
        for (p in inside) p.call { it.sendPosition(PeerFix(45.0, 5.7, timeSec = p.clock() / 1000)) }
        w.runFor(60_000)
        assertTrue(inside.all { it.state?.members?.size == ROOM - 1 }, "the room of 21 is settled")

        val before = phones.associateWith { it.framesReceived }
        val genresBefore = phones.associateWith { HashMap(it.receivedByGenre) }
        val writesBefore = w.stats.controlWrites
        val newcomer = phones.last()
        letIn(w, a, newcomer)
        w.runFor(60_000)

        assertTrue(newcomer.isMember, "the 22nd phone is let in")
        for (p in phones) assertEquals(ROOM, p.state?.members?.size, "${p.label} knows the 22")
        val received = inside.map { it.framesReceived - before.getValue(it) }
        val mean = received.average()
        val byGenre =
            FrameGenre.entries.associateWith { g ->
                inside.sumOf { p ->
                    (p.receivedByGenre[g] ?: 0L) - (genresBefore.getValue(p)[g] ?: 0L)
                }.toDouble() / inside.size
            }
        val report =
            (
                "an arrival in a room of $ROOM: frames received per phone already in %.1f on average (%d to %d), " +
                    "by the newcomer %d; control writes on the server %d; per phone, by genre: %s"
            ).format(
                mean,
                received.min(),
                received.max(),
                newcomer.framesReceived - before.getValue(newcomer),
                w.stats.controlWrites - writesBefore,
                byGenre.filterValues { it > 0 }.entries.joinToString { (g, v) -> "%s %.1f".format(g.name, v) },
            )
        println(report)
        assertTrue(mean < MAX_FRAMES_PER_PHONE, report)
        // The summaries meant for one phone were heard by the others, who left them to it.
        assertTrue(inside.all { it.n.heardForOthers > 0 }, "summaries for others heard and left: $report")

        // Then the room is quiet while the newcomer waits for its place: nothing but the summaries of every few
        // minutes. Until P39.17, every 25 s the newcomer asked for keys it may not have, and every summary of the
        // room said a name was missing that nobody may read: 240 frames a minute to each phone, for good.
        assertTrue(newcomer.state?.isWaiting(newcomer.id) == true, "the newcomer waits for its place")
        val quietFrom = phones.associateWith { it.framesReceived }
        w.runFor(QUIET_MS)
        val perMinute = phones.map { (it.framesReceived - quietFrom.getValue(it)) * 60_000.0 / QUIET_MS }
        val quiet =
            "while a member waits: frames received per phone and per minute %.1f at most".format(
                perMinute.max(),
            )
        println(quiet)
        assertTrue(perMinute.max() < MAX_QUIET_PER_MINUTE, quiet)
    }

    /** [p] asks to come in with an invitation of the creator [a], who lets it in. */
    private fun letIn(
        w: World,
        a: SimPhone,
        p: SimPhone,
    ) {
        val invite = requireNotNull(a.call { it.invitation(a.clock() / 1000) })
        p.join(invite, Names.hide("Membre ${p.index + 1}", SeededRandom(p.index.toLong())))
        w.runFor(15_000)
        check(a.call { it.admit(p.id) } is IssueResult.Issued) { "${p.label} not let in" }
        w.runFor(10_000)
    }

    private companion object {
        const val ROOM = 22

        /** Frames a phone already in receives for one arrival (JVM, seed 22 000): 7 360 before P39.17, 872 after. */
        const val MAX_FRAMES_PER_PHONE = 1_200.0

        /** The quiet that follows, a member waiting: 240 frames a minute to each phone before P39.17. */
        const val QUIET_MS = 5 * 60_000L
        const val MAX_QUIET_PER_MINUTE = 20.0
    }
}
