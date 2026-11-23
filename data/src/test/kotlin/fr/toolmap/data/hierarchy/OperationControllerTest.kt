package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Rejection
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller the screens will call (P39.7, `docs/specs/group-hierarchy.md` § 17): two phones, each with its own
 * storage and a software key store, in a room of a coordination server simulated in memory (what the real one
 * does: every frame written reaches every member present). The « Entre amis » relay is not involved.
 */
class OperationControllerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { synchronized(problems) { problems.add(it) } }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
        synchronized(problems) { assertTrue(problems.none { !it.expected }, "bugs reported: $problems") }
    }

    private val clock = AtomicLong(1_700_000_000_000L)
    private val server = RelayConfig.of("https://example.invalid", "key")

    /** The server in memory: one room per name, the members present, and what each frame's first byte asked for. */
    private inner class MemoryServer : RelayTransportFactory {
        val rooms = HashMap<String, MutableList<Member>>()
        val positionSlot = ArrayList<ByteArray>()
        val controlFrames = ArrayList<ByteArray>()

        inner class Member(
            val room: String,
            val listener: LinkListener,
        ) : RelayTransport {
            override var identity: TransportIdentity? = null
            override var onState: ((ServerState) -> Unit)? = null
            override var state: ServerState = ServerState.CONNECTING
            var closed = false

            private fun others() = synchronized(rooms) { rooms[room].orEmpty().filter { it !== this && !it.closed } }

            private fun linkTo(peer: Member): MeshLink =
                object : MeshLink {
                    override val isBroadcastMedium get() = true

                    override fun send(
                        frame: ByteArray,
                        onPositionChannel: Boolean,
                    ): Boolean {
                        if (closed) return false
                        // What FirebaseRelay decides from the first byte (Wire.typeOf): position slot, or control frame.
                        synchronized(rooms) {
                            if (onPositionChannel &&
                                frame[0].toInt() == 4
                            ) {
                                positionSlot += frame
                            } else {
                                controlFrames += frame
                            }
                        }
                        peer.listener.onFrame(identity!!.memberId, frame)
                        return true
                    }

                    override fun close() = Unit
                }

            override fun start() {
                synchronized(rooms) { rooms.getOrPut(room) { ArrayList() } += this }
                state = ServerState.ONLINE
                onState?.invoke(state)
                for (other in others()) {
                    other.listener.onLinkOpen(identity!!.memberId, "", 0, other.linkTo(this))
                    listener.onLinkOpen(other.identity!!.memberId, "", 0, linkTo(other))
                }
            }

            override fun ban(peer: Long) = Unit

            override fun close() {
                closed = true
                for (other in others()) other.listener.onLinkClosed(identity!!.memberId)
            }

            override fun offer(
                peer: Long,
                done: (PairingPayload?) -> Unit,
            ) = done(null)

            override fun answer(
                offer: PairingPayload,
                done: (PairingPayload?) -> Unit,
            ) = done(null)

            override fun accept(answer: PairingPayload) = Unit

            /** What the phone's network watch told this connection, in order (P39.14, L4 bis). */
            val told = ArrayList<String>()

            override fun onNetwork(network: String) {
                synchronized(told) { told += "network $network" }
            }

            override fun reconnectNow() {
                synchronized(told) { told += "reconnect" }
            }

            override fun onBlocked(blocked: Boolean) {
                synchronized(told) { told += "blocked $blocked" }
            }

            override fun onOffline() {
                synchronized(told) { told += "offline" }
            }

            override fun onClockChanged() {
                synchronized(told) { told += "clock" }
            }

            override fun drop(peer: Long) = Unit
        }

        override fun create(
            config: RelayConfig,
            groupId: ByteArray,
            listener: LinkListener,
        ): RelayTransport = Member(RelayPaths.room(groupId), listener)
    }

    private fun phone(
        name: String,
        relay: MemoryServer?,
        vault: KeyVault = SoftwareKeyVault(),
        root: File = File(temp.root, name),
    ) = OperationController(OperationStorage(root, vault), relay, { server }, clock::get)

    /** Lets the threads of both phones run what was posted, moving the clock so that what is due gets done. */
    private fun settle(vararg phones: OperationController) =
        runBlocking {
            repeat(30) {
                clock.addAndGet(700)
                for (p in phones) p.tickNow()
                Thread.sleep(5)
            }
        }

    @Test
    fun `two phones, create, invite, let in, place, share, then dissolve and nothing is left`() =
        runBlocking {
            val relay = MemoryServer()
            val a = phone("a", relay)
            val b = phone("b", relay)
            assertEquals(StartResult.STARTED, a.create("Opération Vercors", "Alice"))
            assertEquals(OperationPhase.PLACED, a.view.value.phase)
            assertTrue(a.view.value.mayInvite)
            val link = assertNotNull(a.invitationLink())
            assertTrue(link.startsWith("toolmap://operation?d="))
            assertEquals("Opération Vercors", b.preview(link)?.operationName)
            assertEquals(StartResult.STARTED, b.join(link, "Bruno"))
            settle(a, b)
            assertEquals(OperationPhase.ASKING, b.view.value.phase)
            assertEquals("Opération Vercors", b.view.value.operationName)
            val request = a.view.value.joinRequests.single()
            assertEquals("Bruno", request.name)
            assertNull(a.admit(request.member))
            settle(a, b)
            assertEquals(OperationPhase.WAITING, b.view.value.phase)
            assertEquals(Channel.waiting(b.view.value.self!!), b.view.value.targets?.position)
            assertNull(b.view.value.targets?.alert, "who waits alerts nobody")
            val (group, refused) = a.createGroup("Groupe Nord")
            assertNull(refused)
            assertNull(a.place(request.member, group!!))
            assertNull(a.setPost(group, Post.LEADER, request.member))
            settle(a, b)
            val bv = b.view.value
            assertEquals(OperationPhase.PLACED, bv.phase)
            assertEquals("Groupe Nord", bv.groups.single().name)
            assertEquals("Alice", bv.members.first { it.id == a.view.value.self }.name)
            assertEquals(Channel.LEADERS, bv.targets?.alert, "a group leader alerts every leader (T4 B)")
            assertEquals(ShareOutcome.SENT, b.sendPosition(PeerFix(45.2, 5.7, timeSec = 1000)))
            assertEquals(ShareOutcome.SENT, b.sendMessage(Channel.up(group), "Arrivés au col"))
            val (put, objectId) = a.putObject(ShareKind.WAYPOINT, Channel.down(group), byteArrayOf(1, 2))
            assertEquals(ShareOutcome.SENT, put)
            settle(a, b)
            assertEquals(45.2, a.view.value.positions[bv.self]?.fix?.lat)
            assertEquals(listOf("Arrivés au col"), a.view.value.threads[Channel.up(group)]?.map { it.text })
            assertEquals(listOf(objectId), b.view.value.objects.map { it.objectId })
            // The positions went to the server's position slot, everything else as control frames.
            assertTrue(
                relay.positionSlot.isNotEmpty() &&
                    relay.positionSlot.all { it.size <= OperationFrames.MAX_POSITION_FRAME },
            )
            assertTrue(
                relay.controlFrames.all {
                    it.size <= OperationFrames.MAX_FRAME &&
                        it[0].toInt() and 0xFF == OperationFrames.MARKER
                },
            )
            // B may not dissolve; A does, and both phones wipe everything.
            assertEquals(Rejection.FORBIDDEN, b.dissolve())
            assertEquals(Rejection.IS_CREATOR, a.leave())
            assertNull(a.dissolve())
            settle(a, b)
            assertEquals(OperationPhase.ENDED, a.view.value.phase)
            assertEquals(Ending.DISSOLVED, b.view.value.ending)
            assertTrue(a.ids().isEmpty() && b.ids().isEmpty(), "both stores are empty")
        }

    @Test
    fun `an operation reads back from the storage after a restart, then a member leaves`() =
        runBlocking {
            val relay = MemoryServer()
            val vaultA = SoftwareKeyVault()
            val vaultB = SoftwareKeyVault()
            val a = phone("a", relay, vaultA)
            val b = phone("b", relay, vaultB)
            a.create("Opération Chartreuse", "Alice")
            b.join(a.invitationLink()!!, "Bruno")
            settle(a, b)
            a.admit(a.view.value.joinRequests.single().member)
            val group = a.createGroup("Sud").first!!
            settle(a, b)
            val bId = b.view.value.self!!
            a.place(bId, group)
            a.setPost(HierarchyState.ROOT, Post.LEADER, a.view.value.self!!)
            settle(a, b)
            val before = a.view.value
            a.close()
            b.close()
            // A new process: new controllers on the same folders and key stores.
            val a2 = phone("a", relay, vaultA)
            val b2 = phone("b", relay, vaultB)
            assertEquals(StartResult.STARTED, a2.open(a2.ids().single()))
            assertEquals(StartResult.STARTED, b2.open(b2.ids().single()))
            settle(a2, b2)
            val after = a2.view.value
            assertEquals(before.ensembleId, after.ensembleId)
            assertEquals(
                before.members,
                after.members.map {
                    it.copy(
                        online =
                            before.members.first { m ->
                                m.id == it.id
                            }.online,
                    )
                },
            )
            assertEquals(before.groups, after.groups)
            assertEquals("Opération Chartreuse", after.operationName)
            assertEquals(Channel.ENSEMBLE, after.targets?.alert, "the leader of the operation alerts everyone placed")
            // B leaves: its storage is wiped, A sees it gone.
            assertNull(b2.leave())
            settle(a2, b2)
            assertEquals(Ending.LEFT, b2.view.value.ending, "its departure got by A (M4 A)")
            assertTrue(b2.ids().isEmpty())
            assertTrue(vaultB.aliases(OperationStorage.ALIAS_PREFIX).isEmpty(), "no key of B left")
            assertFalse(after.members.none { it.id == bId })
            assertTrue(a2.view.value.members.none { it.id == bId })
            assertEquals(2, a2.view.value.inviteEpoch, "the invitation key was renewed once B left")
        }

    @Test
    fun `names - requests, answers, direct renames and a renamed operation, as the screens see them`() =
        runBlocking {
            val relay = MemoryServer()
            val a = phone("a", relay)
            val b = phone("b", relay)
            val c = phone("c", relay)
            a.create("Opération Vercors", "Alice")
            val before = assertNotNull(a.invitationLink())
            b.join(before, "Bruno")
            settle(a, b)
            assertNull(a.admit(a.view.value.joinRequests.single().member))
            settle(a, b)
            val aId = a.view.value.self!!
            val bId = b.view.value.self!!
            assertEquals(OperationPhase.WAITING, b.view.value.phase)

            // B, waiting, asks; blank names are refused before anything is written.
            assertEquals(Rejection.MALFORMED, b.requestName("   "))
            assertNull(b.requestName("  Bruno B "))
            settle(a, b)
            val asked = a.view.value.nameRequests.single()
            assertEquals(bId, asked.member)
            assertEquals("Bruno B", asked.requested)
            assertTrue(asked.seenAtMs > 0)
            assertEquals(asked.copy(seenAtMs = 0), b.view.value.nameRequests.single().copy(seenAtMs = 0))
            assertEquals("Bruno", a.view.value.members.first { it.id == bId }.name, "the old name stays")
            // B may neither rename nor answer.
            assertEquals(Rejection.FORBIDDEN, b.renameMember(aId, "Alicia"))
            assertEquals(Rejection.FORBIDDEN, b.renameOperation("Opération pirate"))
            assertEquals(Rejection.FORBIDDEN, b.answerName(bId, accept = true))

            // A refuses: both see the answer; a second answer has nothing left to answer.
            assertNull(a.answerName(bId, accept = false))
            assertEquals(Rejection.NOT_PENDING, a.answerName(bId, accept = true))
            settle(a, b)
            val refused = NameAnswerView(bId, aId, "Bruno B", accepted = false)
            assertEquals(refused, a.view.value.nameAnswers[bId])
            assertEquals(refused, b.view.value.nameAnswers[bId])
            assertTrue(a.view.value.nameRequests.isEmpty())

            // B asks again (the answer goes), then takes it back.
            assertNull(b.requestName("Bruno C"))
            settle(a, b)
            assertEquals("Bruno C", a.view.value.nameRequests.single().requested)
            assertTrue(a.view.value.nameAnswers.isEmpty(), "a new request clears the last answer")
            assertNull(b.withdrawNameRequest())
            assertEquals(Rejection.NOT_PENDING, b.withdrawNameRequest())
            settle(a, b)
            assertTrue(a.view.value.nameRequests.isEmpty() && b.view.value.nameRequests.isEmpty())

            // A renames B at once (trimmed), then the operation: B, still waiting, learns both.
            assertEquals(Rejection.MALFORMED, a.renameMember(bId, " "))
            assertNull(a.renameMember(bId, "  Bruno Z "))
            assertNull(a.renameOperation("Opération Chartreuse"))
            settle(a, b)
            assertEquals("Bruno Z", a.view.value.members.first { it.id == bId }.name)
            assertEquals("Bruno Z", b.view.value.members.first { it.id == bId }.name)
            assertEquals("Opération Chartreuse", b.view.value.operationName)
            assertEquals("Opération Chartreuse", a.preview(a.invitationLink()!!)?.operationName)

            // C comes in with the invitation given before the rename: the former name until it is in.
            assertEquals("Opération Vercors", c.preview(before)?.operationName)
            assertEquals(StartResult.STARTED, c.join(before, "Chloé"))
            settle(a, b, c)
            assertEquals(OperationPhase.ASKING, c.view.value.phase)
            assertEquals("Opération Vercors", c.view.value.operationName)
            val cId = a.view.value.joinRequests.single().member
            assertNull(a.admit(cId))
            settle(a, b, c)
            assertEquals(OperationPhase.WAITING, c.view.value.phase)
            assertEquals("Opération Chartreuse", c.view.value.operationName)

            // Placed, B and C are plain members: each sees its own request and answer, not the other's.
            val group = a.createGroup("Nord").first!!
            assertNull(a.place(bId, group))
            assertNull(a.place(cId, group))
            settle(a, b, c)
            assertNull(b.requestName("Bruno Y"))
            settle(a, b, c)
            assertEquals(listOf("Bruno Y"), a.view.value.nameRequests.map { it.requested })
            assertEquals(listOf("Bruno Y"), b.view.value.nameRequests.map { it.requested })
            assertTrue(c.view.value.nameRequests.isEmpty(), "C does not see B's request")
            assertNull(a.answerName(bId, accept = true))
            settle(a, b, c)
            val accepted = NameAnswerView(bId, aId, "Bruno Y", accepted = true)
            assertEquals(accepted, a.view.value.nameAnswers[bId])
            assertEquals(accepted, b.view.value.nameAnswers[bId])
            assertTrue(c.view.value.nameAnswers.isEmpty(), "C does not see B's answer")
            for (p in listOf(a, b, c)) assertEquals("Bruno Y", p.view.value.members.first { it.id == bId }.name)
        }

    @Test
    fun `without a server the operation lives on this phone and gives no invitation`() =
        runBlocking {
            val a =
                OperationController(OperationStorage(File(temp.root, "solo"), SoftwareKeyVault()), null, {
                    null
                }, clock::get)
            assertEquals(StartResult.STARTED, a.create("Seul", "Alice"))
            assertNull(a.invitationLink())
            assertFalse(a.view.value.mayInvite)
            assertEquals(StartResult.BAD_NAME, a.create("", "Alice"))
            assertEquals(StartResult.BAD_INVITATION, a.join("toolmap://join?d=AAAA", "Bruno"))
            assertEquals(StartResult.NOT_FOUND, a.open("0000"))
        }

    @Test
    fun `the phone's network is told to the server connection of the operation that runs (L4 bis)`() =
        runBlocking {
            val relay = MemoryServer()
            val a = phone("a", relay)
            // No operation yet: nothing to tell, nothing fails.
            a.onOffline()
            assertEquals(StartResult.STARTED, a.create("Exercice Vallon", "Alice"))
            a.onOffline()
            a.onBlocked(true)
            a.onBlocked(false)
            a.onNetwork("net 2", retry = true)
            a.onNetwork("net 2", retry = false)
            a.onClockChanged()
            settle(a)
            val member = synchronized(relay.rooms) { relay.rooms.values.single().single() }
            assertEquals(
                listOf(
                    "offline",
                    "blocked true",
                    "blocked false",
                    "network net 2",
                    "reconnect",
                    "network net 2",
                    "clock",
                ),
                synchronized(member.told) { member.told.toList() },
            )
        }
}
