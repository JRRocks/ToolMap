package fr.toolmap.core.hierarchy

import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A member with a valid identity and a modified phone, who tries to go past its rights (P39.11, spec § 22): it signs
 * what its role may not, names itself, comes back after being taken out, hands keys out, writes where it may not,
 * speaks in the name of another, plays old frames again, floods the room. Every honest phone must refuse, say it
 * (`Problems.expected`, never a bug), keep its state, and never crash. Two tests pin known limits (§ 6.4, § 22): what
 * the attack obtains today, so that the day it is closed the test says so.
 */
class MaliciousMemberTest {
    private val problems = ProblemLog()

    private fun AttackPhone.rejectionOf(op: Op): Rejection? {
        val entry = n.replica.history().firstOrNull { it.op == op }
        assertNotNull(entry, "$this holds $op")
        return entry.rejection
    }

    /** [change] as a modified phone of [by] writes it: signed, sent, and kept in its own copy. */
    private fun AttackRoom.forgeAndSend(
        by: AttackPhone,
        change: Change,
    ): Op {
        assertNotNull(by.n.check(change), "the application of $by already refuses $change")
        val op = by.forge(change)
        by.n.replica.add(op)
        by.sendOps(op)
        settle()
        return op
    }

    @Test
    fun `a plain member signs every change its role does not allow - kept everywhere, refused everywhere`() =
        problems.around {
            val r = AttackRoom(11)
            val s = r.standard()
            val m = s.m
            val intruder = OpAuth.memoryKeys()
            val honest = s.all - m
            val before = honest.map(::stateBytes)
            val attacks =
                listOf(
                    Change.AddAdmin(m.id),
                    Change.SetPost(HierarchyState.ROOT, Post.LEADER, m.id),
                    Change.SetPost(HierarchyState.ROOT, Post.DEPUTY, m.id),
                    Change.SetPost(s.nord, Post.LEADER, m.id),
                    Change.SetPost(s.nord, Post.DEPUTY, m.id),
                    Change.ClearPost(s.nord, Post.LEADER),
                    Change.AssignMember(m.id, s.sud),
                    Change.UnassignMember(s.p.id),
                    Change.RemoveMember(s.l.id),
                    Change.RemoveAdmin(s.b.id),
                    Change.CreateGroup(r.nonZero(), Names.hide("Pirates").hidden),
                    Change.RenameGroup(s.nord, Names.hide("Nord bis").hidden),
                    Change.DeleteGroup(s.sud),
                    Change.AdmitMember(intruder.memberId, Names.hide("Intrus").hidden, intruder.public),
                    Change.TransferCreator(m.id),
                    Change.Dissolve,
                    // The names (2026-11-16): it renames nobody, itself included, nor the operation; it asks for
                    // no name in another's place, takes back no other request and answers none.
                    Change.RenameMember(m.id, Names.hide("Marc le chef").hidden),
                    Change.RenameMember(s.l.id, Names.hide("Léa bis").hidden),
                    Change.RenameEnsemble(Names.hide("Opération pirate").hidden),
                    Change.RequestName(s.l.id, Names.hide("Léa ter").hidden),
                    Change.WithdrawName(s.l.id),
                    Change.AnswerName(s.l.id, Names.hide("Léa ter").hidden, accept = true),
                    // The rhythm of the positions (2026-11-18) is not a member's.
                    Change.SetPositionInterval(5),
                    // Nor the colour of a group (P39.14), which is the administrators'.
                    Change.SetGroupColor(s.nord, 0xD32F2F),
                )
            val ops = attacks.map { r.forgeAndSend(m, it) }
            for (p in honest) {
                for (op in ops) assertEquals(Rejection.FORBIDDEN, p.rejectionOf(op), "$p refuses $op")
            }
            assertEquals(before, honest.map(::stateBytes), "nothing changed")
            assertConverged(s.all)
            assertTrue(intruder.memberId !in s.a.state.members)
            assertNull(s.a.ended)
            // The phones still work together after it.
            assertEquals(ShareOutcome.SENT, s.a.n.sendMessage(Channel.ENSEMBLE, "toujours là"))
            r.settle()
            assertTrue(s.p.messages.any { it.third == "toujours là" })
        }

    @Test
    fun `an administrator signs what only the creator may, a group leader acts on the hierarchy - refused`() =
        problems.around {
            val r = AttackRoom(12)
            val s = r.standard()
            val honest = listOf(s.a, s.m, s.o, s.p)
            val before = honest.map(::stateBytes)
            val byAdmin =
                listOf(
                    Change.AddAdmin(s.l.id),
                    Change.RemoveAdmin(s.b.id),
                    Change.RemoveMember(s.m.id),
                    Change.TransferCreator(s.b.id),
                    Change.Dissolve,
                ).map { r.forgeAndSend(s.b, it) }
            val byLeader =
                listOf(
                    Change.AssignMember(s.m.id, s.sud),
                    Change.UnassignMember(s.m.id),
                    Change.SetPost(s.nord, Post.DEPUTY, s.m.id),
                    Change.SetPost(HierarchyState.ROOT, Post.LEADER, s.l.id),
                    Change.CreateGroup(r.nonZero(), Names.hide("Est").hidden),
                    Change.AddAdmin(s.l.id),
                    // A leader renames nobody of its group, nor itself: it asks, like everyone (2026-11-16).
                    Change.RenameMember(s.m.id, Names.hide("Marc 2").hidden),
                    Change.RenameMember(s.l.id, Names.hide("Cheffe").hidden),
                ).map { r.forgeAndSend(s.l, it) }
            for (p in honest) {
                for (op in byAdmin + byLeader) assertEquals(Rejection.FORBIDDEN, p.rejectionOf(op), "$p refuses $op")
            }
            assertEquals(before, honest.map(::stateBytes))
            assertEquals(setOf(s.b.id), s.a.state.admins)
            assertEquals(s.a.id, s.a.state.creator)
        }

    @Test
    fun `a member taken out signs its way back in, plays its admission again, asks again - nothing lets it in`() =
        problems.around {
            val r = AttackRoom(13)
            val s = r.standard()
            val m = s.m
            r.goOffline(m)
            s.a.must(Change.RemoveMember(m.id))
            r.settle()
            assertEquals(2, s.a.n.epoch, "the invitation key was renewed after the departure")
            val honest = s.all - m
            val before = honest.map(::stateBytes)
            // Its modified phone still believes it is in, and keeps the invitation key of epoch 1.
            assertTrue(m.id in m.state.members)
            val admission = s.a.n.replica.ops().first { (it.change as? Change.AdmitMember)?.member == m.id }
            r.comeBack(m)
            r.settle()
            val selfAdmit = m.forge(Change.AdmitMember(m.id, Names.hide("Marc").hidden, m.keys.public))
            val selfPlace =
                m.forge(Change.AssignMember(m.id, s.nord), seq = selfAdmit.seq + 1, clock = selfAdmit.clock + 1)
            m.sendOps(selfAdmit, selfPlace)
            // Its admission by the creator, played again; and a request to come in under its old invitation key.
            m.sendOps(admission)
            val request = JoinRequests.make(m.keys, m.n.ensembleId, Names.hide("Marc"), emptyList(), r.now / 1000)
            m.write(m.frame(FrameGenre.JOIN, request.encode()))
            r.settle()
            for (p in honest) {
                assertTrue(m.id !in p.state.members, "$p")
                assertTrue(p.rejectionOf(selfAdmit) != null && p.rejectionOf(selfPlace) != null, "$p refuses")
                assertTrue(p.n.pendingJoins.none { it.member == m.id }, "$p shows no request")
            }
            assertEquals(before, honest.map(::stateBytes))
            assertTrue(s.a.n.dropped.getOrDefault("join.old_key", 0) >= 1)
            // It never got the new invitation key: it reads nothing of what follows, and never learns it is out.
            assertEquals(1, m.n.epoch)
            val mark = r.written.size
            assertEquals(ShareOutcome.SENT, s.a.n.sendMessage(Channel.ENSEMBLE, "sans Marc"))
            r.settle()
            assertTrue(m.messages.none { it.third == "sans Marc" })
            assertTrue(r.openedSince(mark, m).none { it.genre == FrameGenre.SHARE })
            assertNull(m.ended, "it stays in an operation that no longer answers it")
        }

    /**
     * A known limit, verified so it is not forgotten (spec § 6.4, § 22): a member taken out still holds
     * the invitation key of the epoch before it left. With it, it can sign an admission dated before its own removal
     * for a phone of its own, which every phone applies (its author had the right at that point of the order). This
     * test records what the attack obtains today; the day a checkpoint closes it (§ 6.4), the test will change.
     */
    @Test
    fun `known limit - a removed administrator antedates an admission for a second phone of its own`() =
        problems.around {
            val r = AttackRoom(14)
            val s = r.standard()
            val b = s.b
            r.goOffline(b)
            s.a.must(Change.RemoveMember(b.id))
            r.settle()
            assertEquals(2, s.a.n.epoch)
            assertTrue(b.id !in s.a.state.members)
            val namedAdmin = b.n.replica.ops().first { it.change == Change.AddAdmin(b.id) }
            val removal = s.a.n.replica.ops().first { it.change == Change.RemoveMember(b.id) }
            val second = AttackPhone("Second", r)
            val admit =
                b.forge(
                    Change.AdmitMember(second.id, Names.hide("Second").hidden, second.keys.public),
                    seq = 1,
                    clock = namedAdmin.clock + 1,
                )
            val place = b.forge(Change.AssignMember(second.id, s.nord), seq = 2, clock = namedAdmin.clock + 2)
            assertTrue(place.clock < removal.clock, "dated before the removal")
            b.n.replica.add(admit)
            b.n.replica.add(place)
            r.comeBack(b)
            b.sendOps(admit, place)
            r.settle()
            for (p in listOf(s.a, s.l, s.o)) {
                assertNull(p.rejectionOf(admit), "$p applies the antedated admission (the limit of § 6.4)")
                assertEquals(s.nord, p.state.members[second.id]?.group, "$p")
            }
            // Its second phone joins with the room, the key of epoch 1 and the name the removed one kept.
            val invite =
                OperationInvite(
                    r.now / 1000,
                    b.n.ensembleId,
                    b.n.room,
                    1,
                    assertNotNull(b.n.inviteKey(1)),
                    assertNotNull(b.n.names.ensembleCard(b.state)),
                    r.server,
                )
            r.joiner("Second", invite, second)
            r.settle()
            r.now += OperationNode.ANSWER_EVERY_MS
            r.settle()
            // Handed the key of now before it held the operations that let it check it (refused), it is handed it
            // again at the next summary of the room: until P39.17, every phone answering every summary kept the room
            // speaking to it under the older key, and it was asked again within seconds (ADR-138).
            r.now += OperationNode.SUMMARY_EVERY_MS
            r.settle()
            assertEquals(s.a.n.epoch, second.n.epoch, "it is a member: it got the invitation key of now")
            assertFalse(second.state.isWaiting(second.id))
            assertTrue(b.n.epoch < s.a.n.epoch, "the removed one is not handed the key of now by its second phone")
        }

    @Test
    fun `a member writes a message and an alert in the name of another member - refused by every reader`() =
        problems.around {
            val r = AttackRoom(15)
            val s = r.standard()
            // M, a plain member of Nord, holds the content key of Nord. It seals a message as if L (the leader) wrote
            // it, with that very key: the signature is M's, over an author that is not M.
            val nordKey = assertNotNull(s.m.n.ring.sealingKey(s.m.state, Channel.unit(s.nord)))
            val forged =
                s.m.sealRaw(
                    ShareKind.MESSAGE,
                    Channel.unit(s.nord),
                    s.m.messagePayload("Repli immédiat"),
                    nordKey,
                    author = s.l.id,
                )
            s.m.write(s.m.frame(FrameGenre.SHARE, forged))
            // And an alert in the ensemble channel, as if the creator sent it.
            val ensembleKey = assertNotNull(s.m.n.ring.sealingKey(s.m.state, Channel.ENSEMBLE))
            val alert = OperationPayloads.alert(OperationText(r.nonZero(), r.now, "Évacuation", fixAt(r.now / 1000)))
            val forgedAlert = s.m.sealRaw(ShareKind.ALERT, Channel.ENSEMBLE, alert, ensembleKey, author = s.a.id)
            s.m.write(s.m.frame(FrameGenre.SHARE, forgedAlert))
            r.settle()
            for (p in s.all - s.m) {
                assertTrue(p.messages.none { it.third == "Repli immédiat" }, "$p reads no forged message")
                assertTrue(p.alerts.isEmpty(), "$p reads no forged alert")
                assertTrue(p.n.dropped.isNotEmpty(), "$p dropped it")
            }
            assertTrue(problems.count("operation.share.refused") >= 1)
        }

    @Test
    fun `a group member writes in a channel it does not read - never reaches it`() =
        problems.around {
            val r = AttackRoom(16)
            val s = r.standard()
            // M belongs to Nord. It tries to write into Sud's own channel, whose key it does not hold.
            assertNull(s.m.n.ring.sealingKey(s.m.state, Channel.unit(s.sud)), "M holds no key of Sud")
            assertEquals(ShareOutcome.NOT_ALLOWED, s.m.n.sendMessage(Channel.unit(s.sud), "chez les autres"))
            assertEquals(ShareOutcome.NOT_ALLOWED, s.m.n.sendMessage(Channel.LEADERS, "entre chefs"))
            assertEquals(ShareOutcome.NOT_ALLOWED, s.m.n.sendMessage(Channel.up(s.nord), "vers le haut"))
            // Even sealed by hand with Nord's key but addressed to Sud's channel: no reader of Sud holds Nord's key.
            val nordKey = assertNotNull(s.m.n.ring.sealingKey(s.m.state, Channel.unit(s.nord)))
            val wrong =
                s.m.sealRaw(ShareKind.MESSAGE, Channel.unit(s.sud), s.m.messagePayload("intrusion"), nordKey)
            s.m.write(s.m.frame(FrameGenre.SHARE, wrong))
            r.settle()
            for (p in listOf(s.o, s.p)) assertTrue(p.messages.isEmpty(), "$p (Sud) reads nothing")
            assertConverged(s.all)
        }

    @Test
    fun `a member hands a channel key to a phone that must not have it - the phone still cannot read`() =
        problems.around {
            val r = AttackRoom(17)
            val s = r.standard()
            // M (Nord) copies every key batch it holds and gives them to the server's log, which hands them to O (Sud).
            // A content key is wrapped per recipient: O is in none of Nord's lists, so no copy opens for it.
            val batches = s.m.n.ring.batchesFor(s.m.state)
            assertTrue(batches.isNotEmpty())
            for (batch in batches) s.m.write(s.m.frame(FrameGenre.KEYS, HierarchyWire.encodeKeyBatch(batch)))
            r.settle()
            assertNull(s.o.n.ring.sealingKey(s.o.state, Channel.unit(s.nord)), "O holds no key of Nord")
            // L writes in Nord; O, given the batches, still cannot open it.
            val mark = r.written.size
            assertEquals(ShareOutcome.SENT, s.l.n.sendMessage(Channel.unit(s.nord), "Nord fermé"))
            r.settle()
            assertTrue(s.m.messages.any { it.third == "Nord fermé" })
            assertTrue(s.o.messages.none { it.third == "Nord fermé" }, "O never reads Nord")
            // Even if O keeps every batch and every invitation key, no sealing key of Nord exists for it.
            val keys = HashMap<Pair<Channel, Long>, ContentKey>()
            s.o.hoardKeys(keys)
            val invites = HashMap<Int, ByteArray>()
            s.o.hoardInviteKeys(invites)
            val nordShares =
                openEnvelopes(r.written.drop(mark), invites, s.o.n.ensembleId)
                    .filter { it.genre == FrameGenre.SHARE }
                    .mapNotNull { openRaw(it.body, keys) }
                    .filter { it.first.channel == Channel.unit(s.nord) }
            assertTrue(nordShares.isEmpty(), "O opens no share of Nord even with every key it could capture")
        }

    @Test
    fun `a waiting member reads no name and no position but its own lane - verified on the bytes`() =
        problems.around {
            val r = AttackRoom(18)
            val a = r.creator("Alice")
            val b = r.letIn(a, "Bruno")
            val g = a.newGroup("Nord")
            a.must(Change.AssignMember(b.id, g))
            r.settle()
            // W is admitted and waits. The others share positions and a message; W must read none of it.
            val w = r.letIn(a, "Walid")
            r.settle()
            assertTrue(a.state.isWaiting(w.id))
            assertEquals(ShareOutcome.SENT, a.n.sendPosition(fixAt(100, lat = 44.8)))
            assertEquals(ShareOutcome.SENT, b.n.sendPosition(fixAt(101, lat = 44.7)))
            assertEquals(ShareOutcome.SENT, a.n.sendMessage(Channel.ENSEMBLE, "secret"))
            r.settle()
            assertTrue(w.positions.isEmpty(), "W sees no position")
            assertTrue(w.messages.isEmpty(), "W reads no message")
            assertNull(w.nameOf(a.id), "W reads no name of a member")
            assertNull(w.nameOf(b.id))
            assertNull(w.n.names.nameOf(a.state.groups.getValue(g).name), "W reads no group name")
            assertEquals("Opération Vercors", w.n.operationName(), "only the operation's name, from its invitation")
            // Even with every key and invitation key it could capture, nothing of the ensemble opens for W.
            val keys = HashMap<Pair<Channel, Long>, ContentKey>()
            w.hoardKeys(keys)
            assertTrue(keys.keys.none { it.first == Channel.ENSEMBLE }, "W holds no ensemble key")
            // No clear name in the bytes W holds (its operations and its state), but the operation's own name.
            val held =
                w.n.replica.ops().fold(ByteArray(0)) { acc, op -> acc + op.content } +
                    HierarchyWire.encodeState(w.state)
            for (name in listOf("Alice", "Bruno", "Walid", "Nord")) {
                assertFalse(held.holds(name.toByteArray()), "W holds no clear « $name »")
            }
            assertFalse(held.holds("Opération Vercors".toByteArray()), "the operation name is in no operation either")
            assertNull(w.positions[a.id])
        }

    @Test
    fun `giant frames and bursts do not crash a phone and leave the state untouched`() =
        problems.around {
            val r = AttackRoom(19)
            val s = r.standard()
            val m = s.m
            val honest = s.all - m
            val before = honest.map(::stateBytes)
            // A frame at the format's size limit, random body, every genre: it opens (M's key) then fails to decode.
            for (genre in FrameGenre.entries) {
                val body = ByteArray(OperationFrames.MAX_BODY).also(r.secure::nextBytes)
                m.write(m.frame(genre, body))
            }
            // A burst of the same share frame, and of operations frames.
            val share =
                run {
                    val key = assertNotNull(m.n.ring.sealingKey(m.state, Channel.unit(s.nord)))
                    m.frame(
                        FrameGenre.SHARE,
                        m.sealRaw(ShareKind.MESSAGE, Channel.unit(s.nord), m.messagePayload("x"), key),
                    )
                }
            repeat(500) { m.write(share) }
            r.settle()
            // The honest phones still converge, their state is unchanged, and no burst message piled up.
            assertEquals(before, honest.map(::stateBytes), "nothing changed")
            assertConverged(s.all)
            assertTrue(s.l.messages.count { it.third == "x" } <= 1, "a replayed message counts once")
            for (p in s.all) assertNull(p.ended)
            assertEquals(ShareOutcome.SENT, s.a.n.sendPosition(fixAt(900)))
            r.settle()
            assertNotNull(s.p.positions[s.a.id], "the operation still works")
            assertConverged(s.all)
        }

    @Test
    fun `an old renewed invitation no longer lets a phone in, and a stolen phone after removal reads nothing`() =
        problems.around {
            val r = AttackRoom(20)
            val a = r.creator("Alice")
            val b = r.letIn(a, "Bruno")
            val c = r.letIn(a, "Chloé")
            val g = a.newGroup("Nord")
            for (x in listOf(b, c)) a.must(Change.AssignMember(x.id, g))
            r.settle()
            // An invitation handed out, kept by a would-be member who has not used it yet.
            val link = OperationInviteCodec.toLink(assertNotNull(a.n.invitation()))
            val oldInvite = assertNotNull(OperationInviteCodec.parseOrNull(link))
            assertEquals(1, oldInvite.epoch)
            // Meanwhile C leaves: the invitation key is renewed.
            assertTrue(c.n.issue(Change.RemoveMember(c.id)) is IssueResult.Issued)
            r.settle()
            assertEquals(2, a.n.epoch, "renewed after C left")
            // The latecomer tries the old invitation: its join request travels under epoch 1, and is dropped.
            val late = r.joiner("Tardif", oldInvite)
            r.settle()
            assertTrue(a.n.pendingJoins.none { it.member == late.id }, "an old invitation does not let it in")
            assertTrue(a.n.dropped.getOrDefault("join.old_key", 0) >= 1 || late.online)
            assertFalse(late.n.isMember)
            // A fresh invitation does let a new phone in: the operation is not broken.
            val fresh = r.letIn(a, "Nadia")
            assertTrue(fresh.n.isMember)

            // A stolen phone: C's modified phone kept every content key and invitation key it held when removed.
            val stolenContent = HashMap<Pair<Channel, Long>, ContentKey>()
            c.hoardKeys(stolenContent)
            val stolenInvites = HashMap<Int, ByteArray>()
            c.hoardInviteKeys(stolenInvites)
            r.goOffline(late)
            val mark = r.written.size
            assertEquals(ShareOutcome.SENT, b.n.sendMessage(Channel.unit(g), "après le vol"))
            assertEquals(ShareOutcome.SENT, b.n.sendPosition(fixAt(700)))
            r.settle()
            // The stolen invitation key of epoch 1 opens no share written after the removal: the frames are at the
            // new epoch, which C never got.
            val frames = openEnvelopes(r.written.drop(mark), stolenInvites, a.n.ensembleId)
            assertTrue(frames.none { it.genre == FrameGenre.SHARE }, "the stolen invitation key opens no share")
            // Even reconstructing the share bodies another way, the stolen content keys open nothing of Nord after.
            val nordAfter =
                r.written.drop(mark).mapNotNull { w ->
                    OperationFrames.epochOf(w.frame)?.let { e ->
                        a.n.inviteKey(e)?.let { k ->
                            val fk = OperationFrames.frameKey(k, a.n.ensembleId, e)
                            OperationFrames.open(fk, a.n.ensembleId, w.frame)
                        }
                    }
                }.filter { it.genre == FrameGenre.SHARE }.mapNotNull { openRaw(it.body, stolenContent) }
            assertTrue(nordAfter.isEmpty(), "the stolen content keys open no share of Nord after the removal")
        }
}
