package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The names of the members and of the ensemble (the user's answers of 2026-11-16): the creator and the
 * administrators rename directly; every other member asks, the creator or an administrator answers, the first answer
 * in the order of the operations decides; the ensemble itself may be renamed. Reducer, replicas, format.
 */
class HierarchyNamesTest {
    /**
     * [deployed] with its posts and someone who waits: A creator, B administrator (no group), C leads G1 with D as
     * deputy, E leads G2 where F is a plain member, H waits for a group.
     */
    private fun named(rules: HierarchyRules = HierarchyRules.DEFAULT): HierarchyReplica =
        deployed(rules).apply {
            must(leader(G1, C))
            must(deputy(G1, D))
            must(leader(G2, E))
            must(admit(H, "Hotel"))
        }

    private val everyone = listOf(A, B, C, D, E, F, H)
    private val commanders = setOf(A, B)

    @Test
    fun `the creator and the administrators rename anyone, themselves and the ensemble - nobody else`() {
        val state = named().state
        for (author in everyone) {
            for (target in everyone) {
                val expected = if (author in commanders) null else Rejection.FORBIDDEN
                val change = Change.RenameMember(target, hidden("Nouveau $target"))
                assertEquals(expected, HierarchyReducer.check(state, author, change), "$author renames $target")
            }
            val expected = if (author in commanders) null else Rejection.FORBIDDEN
            assertEquals(expected, HierarchyReducer.check(state, author, Change.RenameEnsemble(hidden("Sortie 2"))))
        }
        // Not even the leader of the ensemble (R5: a title, no right over the hierarchy).
        val led = named().apply { must(leader(ROOT, F)) }
        assertEquals(Rejection.FORBIDDEN, HierarchyReducer.check(led.state, F, Change.RenameMember(F, hidden("Chef"))))
        assertEquals(Rejection.FORBIDDEN, HierarchyReducer.check(led.state, F, Change.RenameEnsemble(hidden("Chef"))))
    }

    @Test
    fun `each member asks for its own name only, who waits too, and only the creator and the administrators answer`() {
        val replica = named()
        val state = replica.state
        for (author in everyone) {
            for (target in everyone) {
                val expected = if (author == target) null else Rejection.FORBIDDEN
                val ask = Change.RequestName(target, hidden("Demande $target"))
                assertEquals(expected, HierarchyReducer.check(state, author, ask), "$author asks for $target")
            }
        }
        assertTrue(state.isWaiting(H))
        // Requests pending for F and H: only A and B answer them; only the one who asked takes its own back.
        replica.copyFor(F).apply { must(Change.RequestName(F, hidden("Fox"))) }.ops().forEach(replica::add)
        replica.copyFor(H).apply { must(Change.RequestName(H, hidden("Hôtel"))) }.ops().forEach(replica::add)
        val pending = replica.state
        assertEquals(mapOf(F to hidden("Fox"), H to hidden("Hôtel")), pending.nameRequests)
        for (author in everyone) {
            for (target in listOf(F, H)) {
                val answer = Change.AnswerName(target, pending.nameRequests.getValue(target), accept = true)
                val expected = if (author in commanders) null else Rejection.FORBIDDEN
                assertEquals(expected, HierarchyReducer.check(pending, author, answer), "$author answers $target")
                val withdraw = Change.WithdrawName(target)
                val own = if (author == target) null else Rejection.FORBIDDEN
                assertEquals(own, HierarchyReducer.check(pending, author, withdraw), "$author withdraws for $target")
            }
        }
        // Someone who is not in the ensemble asks for nothing.
        val outsider = replica.copyFor(OUTSIDER)
        assertEquals(Rejection.NOT_A_MEMBER, outsider.refused(Change.RequestName(OUTSIDER, hidden("Intrus"))))
    }

    @Test
    fun `a request changes nothing until accepted, a refusal keeps the name, the answer stays till the next one`() {
        val a = named()
        val f = a.copyFor(F)
        assertEquals(Rejection.NO_CHANGE, f.refused(Change.RequestName(F, hidden("Foxtrot"))), "its own name already")
        f.must(Change.RequestName(F, hidden("Fox")))
        assertEquals(Rejection.NO_CHANGE, f.refused(Change.RequestName(F, hidden("Fox"))), "already asked")
        sync(a, f)
        assertEquals(hidden("Foxtrot"), a.state.members.getValue(F).name, "everyone keeps seeing the old name")
        assertEquals(hidden("Fox"), a.state.nameRequests[F])
        // Refused: the name stays, the answer says who refused which name.
        val b = a.copyFor(B)
        b.must(Change.AnswerName(F, hidden("Fox"), accept = false))
        sync(a, b)
        sync(a, f)
        assertEquals(hidden("Foxtrot"), f.state.members.getValue(F).name)
        assertEquals(emptyMap(), f.state.nameRequests)
        assertEquals(NameAnswer(B, hidden("Fox"), accepted = false), f.state.nameAnswers[F])
        // The refused name stays readable by who asked: it is one of its names until the next request.
        assertTrue(hidden("Fox") in f.state.namesOf(F))
        assertTrue(hidden("Fox") in f.state.hiddenNames())
        assertEquals(Rejection.NOT_PENDING, a.refused(Change.AnswerName(F, hidden("Fox"), accept = true)))
        // A new request forgets the last answer; accepted, the member is named so on every phone.
        f.must(Change.RequestName(F, hidden("Foxtrot 2")))
        sync(a, f)
        assertNull(a.state.nameAnswers[F])
        a.must(Change.AnswerName(F, hidden("Foxtrot 2"), accept = true))
        sync(a, f)
        sync(a, b)
        for (phone in listOf(a, b, f)) {
            assertEquals(hidden("Foxtrot 2"), phone.state.members.getValue(F).name, "${phone.self}")
            assertEquals(NameAnswer(A, hidden("Foxtrot 2"), accepted = true), phone.state.nameAnswers[F])
            assertEquals(emptyMap(), phone.state.nameRequests)
        }
        assertSame(a, f)
        assertSame(a, b)
    }

    @Test
    fun `who asked takes its request back while unanswered, asks again - a new request replaces the pending one`() {
        val a = named()
        val h = a.copyFor(H)
        assertEquals(Rejection.NOT_PENDING, h.refused(Change.WithdrawName(H)), "nothing to take back")
        h.must(Change.RequestName(H, hidden("Hôtel")))
        h.must(Change.WithdrawName(H))
        assertEquals(emptyMap(), h.state.nameRequests)
        assertEquals(Rejection.NOT_PENDING, h.refused(Change.WithdrawName(H)))
        h.must(Change.RequestName(H, hidden("Hôtel 2")))
        h.must(Change.RequestName(H, hidden("Hôtel 3")))
        sync(a, h)
        assertEquals(mapOf(H to hidden("Hôtel 3")), a.state.nameRequests)
        // An answer to a request that was replaced, or taken back, changes nothing.
        assertEquals(Rejection.NOT_PENDING, a.refused(Change.AnswerName(H, hidden("Hôtel 2"), accept = true)))
        h.must(Change.WithdrawName(H))
        sync(a, h)
        assertEquals(Rejection.NOT_PENDING, a.refused(Change.AnswerName(H, hidden("Hôtel 3"), accept = true)))
        assertEquals(hidden("Hotel"), a.state.members.getValue(H).name)
        assertSame(a, h)
    }

    @Test
    fun `a direct rename drops the pending request, renaming to the same name changes nothing`() {
        val a = named()
        val f = a.copyFor(F)
        f.must(Change.RequestName(F, hidden("Fox")))
        sync(a, f)
        val b = a.copyFor(B)
        b.must(Change.RenameMember(F, hidden("Foxy")))
        assertEquals(Rejection.NO_CHANGE, b.refused(Change.RenameMember(F, hidden("Foxy"))))
        sync(a, b)
        sync(a, f)
        assertEquals(hidden("Foxy"), f.state.members.getValue(F).name)
        assertEquals(emptyMap(), f.state.nameRequests)
        assertNull(f.state.nameAnswers[F], "a direct rename is not an answer")
        assertEquals(Rejection.NOT_PENDING, a.refused(Change.AnswerName(F, hidden("Fox"), accept = true)))
        // The administrators rename themselves, and who waits.
        b.must(Change.RenameMember(B, hidden("Bravo 2")))
        b.must(Change.RenameMember(H, hidden("Hotel 2")))
        assertEquals(hidden("Bravo 2"), b.state.members.getValue(B).name)
        assertEquals(hidden("Hotel 2"), b.state.members.getValue(H).name)
        assertEquals(Rejection.NO_SUCH_MEMBER, b.refused(Change.RenameMember(OUTSIDER, hidden("Personne"))))
        assertSame(a, f)
    }

    @Test
    fun `the request and the last answer go with a member who leaves the ensemble, not with one put back to waiting`() {
        val a = named()
        val f = a.copyFor(F)
        val h = a.copyFor(H)
        f.must(Change.RequestName(F, hidden("Fox")))
        h.must(Change.RequestName(H, hidden("Hôtel")))
        sync(a, f)
        sync(a, h)
        a.must(Change.AnswerName(H, hidden("Hôtel"), accept = false))
        h.must(Change.RequestName(H, hidden("Hôtel 2")))
        sync(a, h)
        // Put back to waiting: its request stays, and its names go to its waiting lane only from now on.
        a.must(Change.UnassignMember(F))
        assertTrue(a.state.isWaiting(F))
        assertEquals(hidden("Fox"), a.state.nameRequests[F])
        assertEquals(setOf(hidden("Sortie du jour"), hidden("Foxtrot"), hidden("Fox")), a.state.namesFor(F))
        // Taken out, and leaving: nothing of their names is left.
        a.must(Change.RemoveMember(F))
        h.must(Change.RemoveMember(H))
        sync(a, h)
        assertEquals(emptyMap(), a.state.nameRequests)
        assertEquals(emptyMap(), a.state.nameAnswers)
        assertFalse(hidden("Fox") in a.state.hiddenNames())
        assertFalse(hidden("Hôtel 2") in a.state.hiddenNames())
        assertNull(a.state.inconsistency())
    }

    @Test
    fun `who waits may be told the ensemble's name and its own ones - its request included, nothing of the others`() {
        val a = named()
        val h = a.copyFor(H)
        val f = a.copyFor(F)
        h.must(Change.RequestName(H, hidden("Hôtel")))
        f.must(Change.RequestName(F, hidden("Fox")))
        sync(a, h)
        sync(a, f)
        val state = a.state
        assertEquals(setOf(hidden("Sortie du jour"), hidden("Hotel"), hidden("Hôtel")), state.namesFor(H))
        assertEquals(setOf(hidden("Hotel"), hidden("Hôtel")), state.namesOf(H))
        assertTrue(state.namesFor(F).containsAll(listOf(hidden("Hôtel"), hidden("Fox"))), "the access: every name")
        // The administrators and the creator write the names in a waiting lane; nobody else does, and only names.
        val lane = Channel.waiting(H)
        assertTrue(state.mayShare(A, ShareKind.ROSTER, lane))
        assertTrue(state.mayShare(B, ShareKind.ROSTER, lane))
        assertTrue(state.mayShare(H, ShareKind.ROSTER, lane))
        for (other in listOf(C, D, E, F)) assertFalse(state.mayShare(other, ShareKind.ROSTER, lane), "$other")
        for (kind in ShareKind.entries - ShareKind.ROSTER) {
            assertFalse(state.mayShare(B, kind, lane), "$kind")
        }
        assertEquals(listOf(Channel.ENSEMBLE, lane), state.shareChannels(B, ShareKind.ROSTER))
        // A table without the cell: the administrators do not write there (the rules of an older ensemble).
        val without =
            state.copy(
                rules =
                    state.rules.copy(
                        shares =
                            ShareTable.DEFAULT.with(ShareKind.ROSTER, Role.ADMIN).with(ShareKind.ROSTER, Role.CREATOR),
                    ),
            )
        assertFalse(without.mayShare(B, ShareKind.ROSTER, lane))
        assertEquals(listOf(Channel.ENSEMBLE), without.shareChannels(A, ShareKind.ROSTER))
    }

    @Test
    fun `the ensemble renamed - its former names are kept in the order of the operations`() {
        val a = named()
        val b = a.copyFor(B)
        assertEquals(Rejection.NO_CHANGE, b.refused(Change.RenameEnsemble(hidden("Sortie du jour"))))
        b.must(Change.RenameEnsemble(hidden("Sortie du lendemain")))
        sync(a, b)
        assertEquals(hidden("Sortie du lendemain"), a.state.name)
        assertEquals(listOf(hidden("Sortie du jour"), hidden("Sortie du lendemain")), a.ensembleNames().toList())
        assertTrue(hidden("Sortie du jour") !in a.state.hiddenNames(), "the former name is no name of the state")
        assertTrue(a.state.namesFor(H).contains(hidden("Sortie du lendemain")))
        assertSame(a, b)
    }

    @Test
    fun `two administrators answer at the same time on two phones - the first in the order decides on both`() {
        val base = named()
        val f = base.copyFor(F)
        f.must(Change.RequestName(F, hidden("Fox")))
        sync(base, f)
        val a = base.copyFor(A)
        val b = base.copyFor(B)
        // Written apart, each on a phone where the request is pending: both are written.
        val accept = a.must(Change.AnswerName(F, hidden("Fox"), accept = true))
        val refuse = b.must(Change.AnswerName(F, hidden("Fox"), accept = false))
        assertEquals(accept.clock, refuse.clock)
        sync(a, b)
        sync(a, f)
        // Same clock: the order of the authors decides (A before B), the same on every phone.
        assertTrue(Op.ORDER.compare(accept, refuse) < 0)
        for (phone in listOf(a, b, f)) {
            assertEquals(hidden("Fox"), phone.state.members.getValue(F).name)
            assertEquals(NameAnswer(A, hidden("Fox"), accepted = true), phone.state.nameAnswers[F])
            assertEquals(Rejection.NOT_PENDING, phone.rejectionOf(refuse), "${phone.self}")
            assertNull(phone.rejectionOf(accept))
        }
        assertSame(a, b)
        assertSame(a, f)
        // The other way round, a refusal first: the name stays, the late acceptance changes nothing.
        val base2 = named()
        val f2 = base2.copyFor(F).apply { must(Change.RequestName(F, hidden("Fox"))) }
        sync(base2, f2)
        val a2 = base2.copyFor(A)
        val b2 = base2.copyFor(B)
        val no = a2.must(Change.AnswerName(F, hidden("Fox"), accept = false))
        val yes = b2.must(Change.AnswerName(F, hidden("Fox"), accept = true))
        sync(b2, a2)
        assertEquals(hidden("Foxtrot"), b2.state.members.getValue(F).name)
        assertEquals(NameAnswer(A, hidden("Fox"), accepted = false), b2.state.nameAnswers[F])
        assertEquals(Rejection.NOT_PENDING, b2.rejectionOf(yes))
        assertNull(b2.rejectionOf(no))
        assertSame(a2, b2)
    }

    @Test
    fun `an ensemble whose rules do not have the cells renames nobody`() {
        val rights =
            listOf(Operation.RENAME_MEMBER, Operation.ANSWER_NAME, Operation.RENAME_ENSEMBLE, Operation.REQUEST_NAME)
                .fold(RightsTable.DEFAULT) { table, op ->
                    Role.entries.fold(table) { t, role -> t.with(op, role) }
                }
        val a = named(HierarchyRules(rights = rights))
        assertEquals(Rejection.FORBIDDEN, a.refused(Change.RenameMember(C, hidden("Nouveau"))))
        assertEquals(Rejection.FORBIDDEN, a.refused(Change.RenameEnsemble(hidden("Nouveau"))))
        assertEquals(Rejection.FORBIDDEN, a.copyFor(C).refused(Change.RequestName(C, hidden("Nouveau"))))
    }

    @Test
    fun `the new changes survive the format, with their proof`() {
        val changes =
            listOf(
                Change.RenameMember(C, hidden("Charlie 2")),
                Change.RequestName(-1, hidden("x".repeat(Names.MAX_BYTES))),
                Change.WithdrawName(C),
                Change.AnswerName(C, hidden("Charlie 2"), accept = true),
                Change.AnswerName(C, hidden("Charlie 2"), accept = false),
                Change.RenameEnsemble(hidden("Sortie 2")),
            )
        for ((i, change) in changes.withIndex()) {
            val op = signedOp(0x1234, A, i + 1, 10L + i, change)
            val bytes = HierarchyWire.encodeOp(op)
            val back = HierarchyWire.decodeOp(bytes)
            assertEquals(op, back)
            assertEquals(change, back.change)
            assertTrue(back.authentic)
            assertContentEquals(bytes, HierarchyWire.encodeOp(back))
            // No name in clear in an operation: only its commitment.
            assertFalse(bytes.holds("Charlie 2".toByteArray()))
        }
        // An answer is accepted or refused, nothing else.
        val answer = HierarchyWire.encodeOp(signedOp(0x1234, A, 1, 1, Change.AnswerName(C, hidden("x"), true)))
        val accept = answer.size - P256.POINT_BYTES - P256.SIGNATURE_BYTES - 1
        assertEquals(1, answer[accept].toInt())
        assertFailsWith<WireException> { HierarchyWire.decodeOp(answer.copyOf().also { it[accept] = 2 }) }
        // Identifiers 0 and names that are not hidden are refused by every phone.
        val bad = HiddenName(ByteArray(3))
        for (
        malformed in
        listOf(
            Change.RenameMember(0, hidden("x")),
            Change.RenameMember(C, bad),
            Change.RequestName(0, hidden("x")),
            Change.RequestName(C, bad),
            Change.WithdrawName(0),
            Change.AnswerName(0, hidden("x"), true),
            Change.AnswerName(C, bad, true),
            Change.RenameEnsemble(bad),
        )
        ) {
            assertTrue(malformed.isMalformed(), "$malformed")
            assertEquals(Rejection.MALFORMED, HierarchyReducer.check(named().state, A, malformed))
        }
    }

    @Test
    fun `a state with requests and answers survives its format, and a block written before them still reads`() {
        val a = named()
        a.copyFor(F).apply { must(Change.RequestName(F, hidden("Fox"))) }.ops().forEach(a::add)
        a.copyFor(H).apply { must(Change.RequestName(H, hidden("Hôtel"))) }.ops().forEach(a::add)
        a.must(Change.AnswerName(H, hidden("Hôtel"), accept = false))
        val state = a.state
        assertEquals(1, state.nameRequests.size)
        assertEquals(1, state.nameAnswers.size)
        val bytes = HierarchyWire.encodeState(state)
        val back = HierarchyWire.decodeState(bytes)
        assertEquals(state, back)
        assertContentEquals(bytes, HierarchyWire.encodeState(back))
        // The block of a state without them is the one written before 2026-11-16: no tail at all.
        val plain = state.copy(nameRequests = emptyMap(), nameAnswers = emptyMap())
        val old = HierarchyWire.encodeState(plain)
        val tail = 2 + 1 * (8 + HiddenName.BYTES) + 2 + 1 * (8 + 8 + HiddenName.BYTES + 1)
        assertEquals(old.size + tail, bytes.size)
        assertContentEquals(old, bytes.copyOf(bytes.size - tail))
        assertEquals(plain, HierarchyWire.decodeState(bytes.copyOf(bytes.size - tail)))
        // One form only: an empty tail, a truncated tail, an answer neither accepted nor refused are refused.
        assertFailsWith<WireException> { HierarchyWire.decodeState(old + ByteWriter().u16(0).u16(0).toByteArray()) }
        assertFailsWith<WireException> { HierarchyWire.decodeState(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<WireException> {
            HierarchyWire.decodeState(bytes.copyOf().also { it[it.size - 1] = 2 })
        }
        // A request or an answer of someone who is not a member cannot be.
        val broken =
            listOf(
                state.copy(nameRequests = mapOf(OUTSIDER to hidden("x"))),
                state.copy(nameAnswers = mapOf(OUTSIDER to NameAnswer(A, hidden("x"), true))),
                state.copy(nameAnswers = mapOf(F to NameAnswer(0, hidden("x"), true))),
            )
        for ((i, bad) in broken.withIndex()) {
            assertTrue(bad.inconsistency() != null, "case $i")
            assertFailsWith<WireException>("case $i") { HierarchyWire.decodeState(HierarchyWire.encodeState(bad)) }
        }
        assertTrue(state.copy(nameRequests = mapOf(F to HiddenName(ByteArray(3)))).inconsistency() != null)
    }

    @Test
    fun `a stored ensemble gives back its requests and answers - the operations are what is stored`() {
        val a = named()
        a.copyFor(F).apply { must(Change.RequestName(F, hidden("Fox"))) }.ops().forEach(a::add)
        a.copyFor(H).apply { must(Change.RequestName(H, hidden("Hôtel"))) }.ops().forEach(a::add)
        a.must(Change.AnswerName(F, hidden("Fox"), accept = true))
        a.must(Change.RenameEnsemble(hidden("Sortie 2")))
        val stored = HierarchySnapshot.encodeOps(a.ops())
        val ops = HierarchySnapshot.decodeOps(stored, a.ensembleId)
        val back = HierarchySnapshot.restoreReplica(a.ensembleId, A, ops, keysOf(A))
        assertSame(a, back)
        assertEquals(mapOf(H to hidden("Hôtel")), back.state.nameRequests)
        assertEquals(hidden("Fox"), back.state.members.getValue(F).name)
        assertEquals(hidden("Sortie 2"), back.state.name)
        // A snapshot written before these changes existed: the same operations without them, read as before.
        val before = named()
        val older =
            HierarchySnapshot.restoreReplica(
                before.ensembleId,
                A,
                HierarchySnapshot.decodeOps(HierarchySnapshot.encodeOps(before.ops()), before.ensembleId),
                keysOf(A),
            )
        assertSame(before, older)
        assertEquals(emptyMap(), older.state.nameRequests)
        assertEquals(emptyMap(), older.state.nameAnswers)
        // The names a phone keeps follow: the refused or accepted name and the pending one are kept, the others not.
        val book = NameBook()
        for (name in listOf("Fox", "Hôtel", "Membre", "Sortie du jour", "Sortie 2")) book.learn(card(name))
        book.prune(back.state)
        assertEquals(null, book.nameOf(hidden("Sortie du jour")), "the former name of the ensemble")
        assertEquals("Fox", book.nameOf(hidden("Fox")))
        assertEquals("Hôtel", book.nameOf(hidden("Hôtel")))
        assertEquals("Sortie 2", book.nameOf(hidden("Sortie 2")))
    }
}
