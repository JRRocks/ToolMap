package fr.toolmap.core.hierarchy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The names between phones (2026-11-16): who reads a requested name, how an answer spreads, the renamed operation
 * reaching who waits for a group and who comes in with an invitation given before. On the simulated room of
 * [AttackRoom], all phones honest.
 */
class OperationNamesTest {
    private val problems = ProblemLog()

    private fun AttackPhone.ask(name: String): NameCard {
        val card = Names.hide(name)
        must(Change.RequestName(id, card.hidden))
        n.learnAndShare(card)
        return card
    }

    private fun AttackPhone.knows(card: NameCard): Boolean = n.names.nameOf(card.hidden) != null

    /** Every share the room carried, opened with what [reader] holds now: header and plain bytes. */
    private fun AttackRoom.sharesOpenedBy(
        reader: AttackPhone,
        keys: MutableMap<Pair<Channel, Long>, ContentKey>,
    ): List<Pair<HierarchyWire.ShareHeader, ByteArray>> {
        reader.hoardKeys(keys)
        val invites = HashMap<Int, ByteArray>()
        reader.hoardInviteKeys(invites)
        return openEnvelopes(written, invites, reader.n.ensembleId)
            .filter { it.genre == FrameGenre.SHARE }
            .mapNotNull { openRaw(it.body, keys) }
    }

    @Test
    fun `a placed member asks for a name, the administrators read it, one accepts and every phone learns it`() =
        problems.around {
            val r = AttackRoom(61)
            val s = r.standard()
            val card = s.p.ask("Paulo")
            r.settle()
            assertConverged(s.all)
            for (x in s.all) assertEquals(card.hidden, x.state.nameRequests[s.p.id], "$x holds the request")
            for (x in listOf(s.a, s.b, s.p)) assertTrue(x.knows(card), "$x reads the name P asks for")
            for (x in s.all) assertEquals("Paul", x.nameOf(s.p.id), "the old name stays until the answer, on $x")

            assertTrue(s.b.n.issue(Change.AnswerName(s.p.id, card.hidden, accept = true)) is IssueResult.Issued)
            r.settle()
            assertConverged(s.all)
            for (x in s.all) {
                assertEquals("Paulo", x.nameOf(s.p.id), "$x shows the new name")
                assertNull(x.state.nameRequests[s.p.id])
                assertEquals(NameAnswer(s.b.id, card.hidden, accepted = true), x.state.nameAnswers[s.p.id])
            }
            assertEquals("Paulo", s.p.n.linkState().ownCard?.name, "P keeps its new name for its next start")
        }

    @Test
    fun `a waiting member's request stays in its lane - administrators read it, the others never, on the bytes`() =
        problems.around {
            val r = AttackRoom(62)
            val s = r.standard()
            val w = r.letIn(s.a, "Walid")
            r.settle()
            assertTrue(s.a.state.isWaiting(w.id))
            val others = listOf(s.l, s.m, s.o, s.p)
            val keys = HashMap<Pair<Channel, Long>, ContentKey>()
            val mark = r.written.size

            // W asks; the creator and the administrator read it, no one else.
            val first = w.ask("Wally")
            r.settle()
            assertConverged(s.all + w)
            for (x in listOf(s.a, s.b, w)) assertTrue(x.knows(first), "$x reads the name W asks for")
            for (x in others) assertFalse(x.knows(first), "$x does not read the name W asks for")
            assertTrue(w.state.namesFor(w.id).containsAll(w.n.names.cards.keys), "W keeps only what it may read")

            // B refuses: every phone sees the answer, W reads which name was refused.
            assertTrue(s.b.n.issue(Change.AnswerName(w.id, first.hidden, accept = false)) is IssueResult.Issued)
            r.settle()
            assertEquals(NameAnswer(s.b.id, first.hidden, accepted = false), w.state.nameAnswers[w.id])
            assertEquals("Wally", w.n.names.nameOf(w.state.nameAnswers.getValue(w.id).name))
            assertEquals("Walid", w.nameOf(w.id), "a refusal keeps the name")
            assertEquals("Walid", s.a.nameOf(w.id))

            // W asks again, A accepts: W and the administrators show it, the others still read nothing of W.
            val second = w.ask("Walt")
            r.settle()
            assertNull(w.state.nameAnswers[w.id], "a new request clears the last answer")
            assertTrue(s.a.n.issue(Change.AnswerName(w.id, second.hidden, accept = true)) is IssueResult.Issued)
            r.settle()
            assertConverged(s.all + w)
            for (x in listOf(s.a, s.b, w)) assertEquals("Walt", x.nameOf(w.id), "$x shows W's new name")
            for (x in others) assertNull(x.nameOf(w.id), "$x does not read W's name while it waits")
            assertTrue(w.state.namesFor(w.id).containsAll(w.n.names.cards.keys), "W keeps only what it may read")

            // On the bytes, with every key the creator holds: the names of W travel in W's lane only.
            val shares = r.sharesOpenedBy(s.a, keys).filter { it.first.ensembleId == s.a.n.ensembleId }
            val lane = Channel.waiting(w.id)
            for (name in listOf("Wally", "Walt")) {
                assertTrue(
                    shares.any { it.first.channel == lane && it.second.holds(name.toByteArray()) },
                    "« $name » travels in W's lane",
                )
                assertTrue(
                    shares.none { it.first.channel != lane && it.second.holds(name.toByteArray()) },
                    "« $name » travels nowhere else",
                )
            }
            assertTrue(r.written.size > mark)

            // Placed in Nord, W's name reaches every phone.
            s.a.must(Change.AssignMember(w.id, s.nord))
            r.settle()
            assertConverged(s.all + w)
            for (x in s.all + w) assertEquals("Walt", x.nameOf(w.id), "$x shows W's name once placed")
        }

    @Test
    fun `two administrators answer at once - the first in the common order decides, on every phone`() =
        problems.around {
            val r = AttackRoom(63)
            val s = r.standard()
            val card = s.p.ask("Paulo")
            r.settle()
            // Both answer before hearing of the other: same clock, the order goes by author.
            assertTrue(s.a.n.issue(Change.AnswerName(s.p.id, card.hidden, accept = true)) is IssueResult.Issued)
            assertTrue(s.b.n.issue(Change.AnswerName(s.p.id, card.hidden, accept = false)) is IssueResult.Issued)
            r.settle()
            assertConverged(s.all)
            val answers =
                s.a.n.replica
                    .history()
                    .filter { it.op.change is Change.AnswerName }
            assertEquals(2, answers.size)
            assertEquals(answers[0].op.clock, answers[1].op.clock)
            val winner = if (java.lang.Long.compareUnsigned(s.a.id, s.b.id) < 0) s.a else s.b
            assertEquals(winner.id, answers[0].op.author)
            assertNull(answers[0].rejection, "the first answer applies")
            assertEquals(Rejection.NOT_PENDING, answers[1].rejection, "the second has nothing left to answer")
            val expected = if (winner === s.a) "Paulo" else "Paul"
            for (x in s.all) {
                assertEquals(winner.id, x.state.nameAnswers.getValue(s.p.id).by, "$x names the same answer")
                assertEquals(expected, x.nameOf(s.p.id))
            }
        }

    @Test
    fun `an administrator renames a waiting member - the member learns its new name, the others do not`() =
        problems.around {
            val r = AttackRoom(64)
            val s = r.standard()
            val w = r.letIn(s.a, "Walid")
            val card = Names.hide("Walther")
            s.b.must(Change.RenameMember(w.id, card.hidden))
            s.b.n.learnAndShare(card)
            r.settle()
            assertConverged(s.all + w)
            assertEquals("Walther", w.nameOf(w.id), "W reads its new name")
            assertEquals("Walther", w.n.linkState().ownCard?.name, "W keeps it for its next start")
            assertEquals("Walther", s.a.nameOf(w.id))
            for (x in listOf(s.l, s.m, s.o, s.p)) assertFalse(x.knows(card), "$x does not read it")
        }

    @Test
    fun `a renamed operation reaches who waits, and an invitation given before still works`() =
        problems.around {
            val r = AttackRoom(65)
            val a = r.creator("Alice")
            val b = r.letIn(a, "Bruno")
            a.must(Change.AddAdmin(b.id))
            val w = r.letIn(a, "Walid")
            r.settle()
            val before = assertNotNull(a.n.invitation())
            assertEquals("Opération Vercors", before.ensembleCard.name)

            // The creator renames: the waiting member learns the new name.
            val renamed = Names.hide("Opération Mont-Aiguille")
            a.must(Change.RenameEnsemble(renamed.hidden))
            a.n.learnAndShare(renamed)
            r.settle()
            for (x in listOf(a, b, w)) assertEquals("Opération Mont-Aiguille", x.n.operationName(), "on $x")
            assertEquals("Opération Mont-Aiguille", assertNotNull(a.n.invitation()).ensembleCard.name)

            // Someone comes in with the invitation given before: the former name until it is in, then the new one.
            val link = OperationInviteCodec.toLink(before)
            val j = r.joiner("Jules", assertNotNull(OperationInviteCodec.parseOrNull(link)))
            r.settle()
            assertEquals("Opération Vercors", j.n.operationName(), "the invitation's name before coming in")
            assertTrue(a.n.pendingJoins.any { it.member == j.id }, "the former invitation still lets ask")
            assertTrue(a.n.admit(j.id) is IssueResult.Issued)
            r.settle()
            assertTrue(a.state.isWaiting(j.id))
            assertEquals("Opération Mont-Aiguille", j.n.operationName(), "the name of now once in")
            assertTrue(j.n.inviteNameChecked, "a former name of the operation checks")
            assertEquals(0, problems.count("operation.invite.name"), "a former name is no problem")

            // A waiting member away during a rename learns it when it comes back.
            r.goOffline(w)
            val again = Names.hide("Opération Grande Moucherolle")
            b.must(Change.RenameEnsemble(again.hidden))
            b.n.learnAndShare(again)
            r.settle()
            assertEquals("Opération Grande Moucherolle", j.n.operationName())
            assertEquals("Opération Mont-Aiguille", w.n.operationName(), "W was away")
            r.comeBack(w)
            r.settle()
            assertConverged(listOf(a, b, w, j))
            assertEquals("Opération Grande Moucherolle", w.n.operationName(), "W learns it back")
            for (x in listOf(w, j)) {
                assertTrue(x.state.namesFor(x.id).containsAll(x.n.names.cards.keys), "$x keeps only what it may read")
            }
        }
}
