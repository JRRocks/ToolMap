package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The defects the simulation found (P39.10, `docs/specs/group-hierarchy.md` § 21), each written again as a short
 * scenario of its own on the same simulated server: they stay fixed.
 */
class OperationSimulationFindingsTest {
    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        assertTrue(problems.none { !it.expected }, "bugs reported: $problems")
    }

    private val server = RelayConfig.of("https://example.invalid", "key")

    private fun world(n: Int): Pair<World, List<SimPhone>> {
        val w = World(n.toLong())
        return w to List(n) { i -> SimPhone(w, i, "P$i", KeyPool.keys(40 + i, 7L + i), 0L, 100L + i, server) }
    }

    private fun card(name: String) = Names.hide(name, SeededRandom(name.hashCode().toLong()))

    private fun SimPhone.must(change: Change) {
        val r = call { it.issue(change) }
        assertIs<IssueResult.Issued>(r, "$label: $change")
    }

    /**
     * Defect 1 (P39.10): a phone asks to come in with an invitation; before it is let in, someone leaves and the
     * invitation key is renewed; then it is let in. The operation that lets it in travels under the new key, which
     * it does not have; the others hand it the new key when it speaks under the old one, but [InviteKeys.open]
     * refused any grant whose recipients are not all members in the state of the phone that receives it, and this
     * phone does not know yet that it is one: it stayed outside for good, asking every 20 s.
     */
    @Test
    fun `a phone let in after the invitation key was renewed learns it is in`() {
        val (w, phones) = world(3)
        val (a, b, c) = phones
        a.create(card("Opération Belledonne"), card("Alice"))
        w.runFor(2_000)
        b.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Bruno"))
        w.runFor(30_000)
        assertIs<IssueResult.Issued>(a.call { it.admit(b.id) })
        w.runFor(30_000)
        assertTrue(b.isMember)
        // C asks with an invitation of the first epoch; its request waits on the creator's phone.
        c.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Chloé"))
        w.runFor(30_000)
        assertTrue(a.n.pendingJoins.any { it.member == c.id })
        // B leaves: the creator renews the invitation key; then it lets C in.
        b.must(Change.RemoveMember(b.id))
        w.runFor(30_000)
        assertEquals(2, a.n.epoch, "renewed once B left")
        assertIs<IssueResult.Issued>(a.call { it.admit(c.id) })
        w.runFor(5 * 60_000L)
        assertTrue(c.isMember, "C learns it was let in")
        assertEquals(a.n.epoch, c.n.epoch)
        assertEquals(a.n.replica.summary(), c.n.replica.summary())
    }

    /** A creator, an administrator and members, all placed in one group; everyone has caught up. */
    private fun placed(
        w: World,
        phones: List<SimPhone>,
    ): Long {
        val a = phones[0]
        a.create(card("Opération Chartreuse"), card("Alice"))
        w.runFor(2_000)
        for (p in phones.drop(1)) {
            p.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Membre ${p.index}"))
            w.runFor(30_000)
            assertIs<IssueResult.Issued>(a.call { it.admit(p.id) })
            w.runFor(10_000)
        }
        val g = 0x777L
        val c = card("Groupe Nord")
        a.must(Change.CreateGroup(g, c.hidden))
        a.call { it.learnAndShare(c) }
        for (p in phones) a.must(Change.AssignMember(p.id, g))
        a.must(Change.AddAdmin(phones[1].id))
        w.runFor(3 * 60_000L)
        for (p in phones) assertEquals(a.n.replica.summary(), p.n.replica.summary(), "${p.label} caught up")
        return g
    }

    /**
     * When a member leaves, the creator renews the invitation key at once and an administrator 1.5 s later if it
     * has seen nothing come meanwhile: on a network slower than that, both renew, and two keys of one epoch go round.
     * Whatever happens then, the phones still in the operation must end up on one key, and hear each other.
     */
    @Test
    fun `two renewals of the invitation key at once still leave one operation`() {
        val (w, phones) = world(5)
        placed(w, phones)
        val (a, b) = phones
        // Every frame now takes 2 to 2.5 s to reach a phone: more than the 1.5 s an administrator waits.
        w.faults = Faults(minDelayMs = 2_000, maxDelayMs = 2_500)
        phones[4].must(Change.RemoveMember(phones[4].id))
        w.runFor(60_000)
        w.faults = Faults.CALM
        w.runFor(10 * 60_000L)
        val inside = phones.take(4)
        for (p in inside) {
            assertEquals(a.n.epoch, p.n.epoch, "${p.label} at the same epoch as the creator")
            assertTrue(
                a.n.inviteKey(a.n.epoch).contentEquals(p.n.inviteKey(p.n.epoch)),
                "${p.label} holds the creator's key of epoch ${a.n.epoch}",
            )
        }
        // And they hear each other: a message of the administrator reaches the creator.
        val before = a.heardMessages.size
        assertTrue(b.call { it.sendMessage(fr.toolmap.core.hierarchy.Channel.ENSEMBLE, "après le départ") } != null)
        w.runFor(60_000)
        assertEquals(before + 1, a.heardMessages.size, "the creator hears the administrator")
    }

    /**
     * Who waits sends its name to the administrators and the creator, in its own lane, once let in (Q10, Q11, § 17
     * reading 8): « les administrateurs savent qui ils placent ». An administrator named after that must learn it too.
     */
    @Test
    fun `an administrator named while someone waits learns the name of who waits`() {
        val (w, phones) = world(3)
        val (a, b, c) = phones
        a.create(card("Opération Vercors"), card("Alice"))
        w.runFor(2_000)
        val g = 0x31L
        val group = card("Groupe Sud")
        a.must(Change.CreateGroup(g, group.hidden))
        a.call { it.learnAndShare(group) }
        b.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Bruno"))
        w.runFor(30_000)
        assertIs<IssueResult.Issued>(a.call { it.admit(b.id) })
        w.runFor(10_000)
        a.must(Change.AssignMember(b.id, g))
        w.runFor(30_000)
        c.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Chloé"))
        w.runFor(30_000)
        assertIs<IssueResult.Issued>(a.call { it.admit(c.id) })
        w.runFor(60_000)
        assertTrue(c.n.state.isWaiting(c.id))
        assertEquals("Chloé", a.n.names.nameOf(a.n.state, c.id), "the creator reads the name of who waits")
        // B becomes an administrator: it reads C's lane from now on, and must know whom it would place.
        a.must(Change.AddAdmin(b.id))
        w.runFor(3 * 60_000L)
        assertTrue(c.n.state.isWaiting(c.id))
        assertEquals("Chloé", b.n.names.nameOf(b.n.state, c.id), "the new administrator reads the name of who waits")
    }

    /** The same name, for an administrator whose phone was off when the one who waits told it (seed 0x4). */
    @Test
    fun `an administrator away when someone was let in learns the name of who waits when it is back`() {
        val (w, phones) = world(3)
        val (a, b, c) = phones
        a.create(card("Opération Belledonne"), card("Alice"))
        w.runFor(2_000)
        b.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Bruno"))
        w.runFor(30_000)
        assertIs<IssueResult.Issued>(a.call { it.admit(b.id) })
        w.runFor(10_000)
        a.must(Change.AddAdmin(b.id))
        w.runFor(30_000)
        c.join(assertNotNull(a.call { it.invitation(a.clock() / 1000) }), card("Chloé"))
        w.runFor(30_000)
        // B is off for an hour while C is let in and tells its name in its lane.
        b.stopApp(killed = false)
        assertIs<IssueResult.Issued>(a.call { it.admit(c.id) })
        w.runFor(3_600_000L)
        assertEquals("Chloé", a.n.names.nameOf(a.n.state, c.id))
        b.startApp()
        w.runFor(5 * 60_000L)
        assertTrue(c.n.state.isWaiting(c.id))
        assertEquals("Chloé", b.n.names.nameOf(b.n.state, c.id), "the administrator back reads the name of who waits")
    }
}
