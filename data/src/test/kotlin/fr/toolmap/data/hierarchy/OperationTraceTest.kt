package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.OperationPayloads
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.ProofVerdict
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
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
 * The two rules of the user's answers of 2026-11-15 (P39.9, ADR-131, `docs/specs/group-hierarchy.md` § 20), with
 * whole controllers, each with its storage and a software key store, in a room of a coordination server in memory
 * ([MemoryRelay]) and a clock of the test's own:
 * - R1 « Chaque téléphone garde une semaine une trace fermée de l'opération »;
 * - R2 « Oui, les 500 derniers de chaque fil ».
 */
class OperationTraceTest {
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
    private val day = 24 * 3600_000L

    /** One phone: its folder, its key store, and its controller of now (a new one after a restart). */
    private inner class Phone(
        val label: String,
        val relay: MemoryRelay,
    ) {
        val vault = SoftwareKeyVault()
        val root = File(temp.root, label)
        var c = controller()

        fun storage() = OperationStorage(root, vault, clock = clock::get)

        private fun controller() = OperationController(storage(), relay, { server }, clock::get)

        /** A new process: a new controller on the same folder and key store, its operation opened again. */
        fun restart(): StartResult =
            runBlocking {
                c.close()
                c = controller()
                c.open(c.ids().single())
            }

        val self: Long get() = assertNotNull(c.view.value.self)

        /** Every file left in this phone's folder, folders aside. */
        fun files(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
    }

    private fun settle(vararg phones: Phone) =
        runBlocking {
            repeat(30) {
                clock.addAndGet(700)
                for (p in phones) p.c.tickNow()
                Thread.sleep(5)
            }
        }

    /** A creates an operation, B comes in and is placed in A's group; A is the leader of the operation. */
    private fun operation(
        relay: MemoryRelay,
        title: String = "Opération Vercors",
    ): Triple<Phone, Phone, Long> =
        runBlocking {
            val a = Phone("a", relay)
            val b = Phone("b", relay)
            assertEquals(StartResult.STARTED, a.c.create(title, "Alice"))
            assertEquals(StartResult.STARTED, b.c.join(assertNotNull(a.c.invitationLink()), "Bruno"))
            settle(a, b)
            assertNull(a.c.admit(a.c.view.value.joinRequests.single().member))
            val group = assertNotNull(a.c.createGroup("Groupe Nord").first)
            settle(a, b)
            assertNull(a.c.place(a.self, group))
            assertNull(a.c.place(b.self, group))
            assertNull(a.c.setPost(fr.toolmap.core.hierarchy.HierarchyState.ROOT, Post.LEADER, a.self))
            settle(a, b)
            assertEquals(OperationPhase.PLACED, b.c.view.value.phase)
            Triple(a, b, group)
        }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    // ---- R1 -------------------------------------------------------------------------------------------------------

    /** B is away when A dissolves; [daysAway] later it opens the operation again, and A's app comes to the front. */
    private fun awayAtTheDissolution(daysAway: Long): Triple<Phone, Phone, Long> =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, _) = operation(relay)
            b.c.close()
            assertNull(a.c.dissolve())
            settle(a)
            val ended = a.c.view.value
            assertEquals(Ending.DISSOLVED, ended.ending)
            assertFalse(ended.endedByTrace)
            val keptUntil = assertNotNull(ended.traceKeptUntilMs, "the creator was a member: it keeps a trace")
            assertTrue(a.c.ids().isEmpty())
            assertEquals(listOf(keptUntil), a.c.traces().map { it.keptUntilMs })

            clock.addAndGet(daysAway * day)
            assertEquals(StartResult.STARTED, b.restart(), "B's phone does not know yet")
            assertEquals(OperationPhase.PLACED, b.c.view.value.phase)
            val told = a.c.announceDissolutions()
            settle(a, b)
            Triple(a, b, keptUntil).also { assertEquals(if (daysAway < 7) 1 else 0, told) }
        }

    @Test
    fun `a phone away at the dissolution learns it from a trace on day 6`() =
        runBlocking {
            val (a, b, keptUntil) = awayAtTheDissolution(daysAway = 6)
            val v = b.c.view.value
            assertEquals(OperationPhase.ENDED, v.phase)
            assertEquals(Ending.DISSOLVED, v.ending)
            assertTrue(v.endedByTrace, "said by the trace, not read from the operations")
            assertTrue(b.c.ids().isEmpty(), "everything of the operation is wiped")
            // B was a member when it was dissolved: it keeps a trace too, until the same day as A's.
            assertEquals(keptUntil, v.traceKeptUntilMs)
            assertEquals(listOf(keptUntil), b.c.traces().map { it.keptUntilMs })
            assertTrue(problems.none { it.where == "operation.dissolution.refused" })

            // A's moment in the room ends by itself after two minutes; its trace stays until the end of the week.
            val trace = a.storage().traces.list(clock.get()).single()
            val room = RelayPaths.room(trace.room)
            assertTrue(a.relay.isPresent(room, trace.sender))
            assertTrue(trace.sender != a.c.view.value.self, "the trace speaks under an identifier of its own")
            clock.addAndGet(OperationController.TRACE_WINDOW_MS + 1)
            a.c.tickNow()
            assertFalse(a.relay.isPresent(room, trace.sender), "A left the room")
            assertEquals(1, a.c.traces().size)
        }

    @Test
    fun `a phone away at the dissolution does not learn it on day 8, the traces are gone`() =
        runBlocking {
            val (a, b, _) = awayAtTheDissolution(daysAway = 8)
            assertEquals(OperationPhase.PLACED, b.c.view.value.phase, "nobody told it")
            assertFalse(b.c.view.value.endedByTrace)
            assertEquals(1, b.c.ids().size)
            assertTrue(a.c.traces().isEmpty(), "A's trace went by itself")
            assertTrue(a.vault.aliases(OperationStorage.traceAliasPrefix(OperationStorage.ALIAS_PREFIX)).isEmpty())
            assertTrue(a.files().isEmpty(), "nothing left on A's phone")
        }

    @Test
    fun `the trace on the disk names nothing, and holds no message, no position, no object`() =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, group) = operation(relay, title = "Opération Belledonne")
            val fix = PeerFix(45.123456, 5.987654, timeSec = 1000)
            assertEquals(ShareOutcome.SENT, a.c.sendPosition(fix))
            assertEquals(ShareOutcome.SENT, a.c.sendMessage(Channel.unit(group), "Rendez-vous au refuge"))
            assertEquals(ShareOutcome.SENT, b.c.sendMessage(Channel.unit(group), "Bien reçu"))
            val body = "point secret du bivouac".toByteArray()
            assertEquals(ShareOutcome.SENT, a.c.putObject(ShareKind.WAYPOINT, Channel.ENSEMBLE, body).first)
            settle(a, b)
            assertEquals(2, a.c.view.value.threads[Channel.unit(group)]?.size)
            val ensemble = assertNotNull(a.c.view.value.ensembleId)
            assertNull(a.c.dissolve())
            settle(a, b)

            for (p in listOf(a, b)) {
                assertEquals(Ending.DISSOLVED, p.c.view.value.ending)
                // What is left on the phone: the trace and nothing else.
                val left = p.files()
                assertEquals(1, left.size, "$left")
                assertEquals(DissolutionTraces.FOLDER, left.single().parentFile.name)
                val trace = p.storage().traces.list(clock.get()).single()
                assertEquals(ensemble, trace.ensembleId)
                assertEquals(ProofVerdict.VALID, trace.proof.check(ensemble))
                val plain = trace.encode()
                for (bytes in listOf(plain, left.single().readBytes())) {
                    for (text in listOf(
                        "Opération Belledonne",
                        "Alice",
                        "Bruno",
                        "Groupe Nord",
                        "Rendez-vous au refuge",
                        "Bien reçu",
                    )) {
                        assertFalse(contains(bytes, text.toByteArray()), "« $text » in the trace of ${p.label}")
                    }
                    assertFalse(contains(bytes, body), "the object in the trace")
                    assertFalse(contains(bytes, OperationPayloads.position(fix).copyOf(8)), "the position in the trace")
                }
                // Its key is the trace's own, none of the operation's is left.
                assertEquals(1, p.vault.aliases(OperationStorage.traceAliasPrefix(OperationStorage.ALIAS_PREFIX)).size)
                assertTrue(p.vault.aliases(OperationStorage.ALIAS_PREFIX).isEmpty())
            }
        }

    @Test
    fun `a member taken out keeps nothing, not even a trace, and the dissolution after changes nothing for it`() =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, group) = operation(relay)
            assertEquals(ShareOutcome.SENT, b.c.sendMessage(Channel.unit(group), "Je pars"))
            settle(a, b)
            assertNull(a.c.removeMember(b.self))
            settle(a, b)
            assertEquals(Ending.OUT, b.c.view.value.ending)
            assertNull(b.c.view.value.traceKeptUntilMs)
            assertTrue(b.c.traces().isEmpty())
            assertTrue(b.files().isEmpty(), "messages, keys, everything wiped: ${b.files()}")
            assertTrue(b.vault.aliases("").isEmpty(), "no key of B's phone left")
            assertNull(a.c.dissolve())
            settle(a, b)
            assertEquals(1, a.c.traces().size)
            assertTrue(b.c.traces().isEmpty() && b.files().isEmpty())
        }

    // ---- R2 -------------------------------------------------------------------------------------------------------

    @Test
    fun `the messages are read back after a restart, once each, and the alerts are not`() =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, group) = operation(relay)
            assertEquals(ShareOutcome.SENT, a.c.sendMessage(Channel.ENSEMBLE, "Départ à 7 h"))
            assertEquals(ShareOutcome.SENT, b.c.sendMessage(Channel.unit(group), "Bien reçu"))
            assertEquals(ShareOutcome.SENT, b.c.sendAlert("Cheville tordue", null))
            settle(a, b)
            val before = a.c.view.value.threads
            assertEquals(listOf("Départ à 7 h"), before[Channel.ENSEMBLE]?.map { it.text })
            assertEquals(listOf("Bien reçu"), before[Channel.unit(group)]?.map { it.text })
            assertEquals(1, a.c.view.value.alerts.size)

            assertEquals(StartResult.STARTED, a.restart())
            assertEquals(StartResult.STARTED, b.restart())
            settle(a, b)
            assertEquals(before, a.c.view.value.threads, "the same messages, authors and times")
            assertTrue(a.c.view.value.alerts.isEmpty(), "alerts live in memory only")

            // Every frame B wrote, played again to A's new process: the engine forgot what it saw, the log did not.
            val room = relay.written.keys.single { k -> relay.written.getValue(k).any { it.first == b.self } }
            for ((from, frame) in relay.written.getValue(room).filter { it.first == b.self }.toList()) {
                relay.replay(room, from, frame)
            }
            settle(a, b)
            assertEquals(before, a.c.view.value.threads, "no message twice")
        }

    @Test
    fun `a thread keeps its last 500 messages on the disk, and they go with the dissolution`() =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, _) = operation(relay)
            for (i in 1..520) assertEquals(ShareOutcome.SENT, a.c.sendMessage(Channel.ENSEMBLE, "Point $i"))
            settle(a, b)
            for (p in listOf(a, b)) {
                assertEquals(StartResult.STARTED, p.restart())
                val thread = assertNotNull(p.c.view.value.threads[Channel.ENSEMBLE])
                assertEquals(500, thread.size, p.label)
                assertEquals("Point 21", thread.first().text)
                assertEquals("Point 520", thread.last().text)
            }
            assertNull(a.c.dissolve())
            settle(a, b)
            for (p in listOf(a, b)) {
                assertEquals(Ending.DISSOLVED, p.c.view.value.ending)
                assertTrue(p.files().none { it.name == StoredOperation.MESSAGES_FILE }, "messages wiped on ${p.label}")
                val plain = p.storage().traces.list(clock.get()).single().encode()
                assertFalse(contains(plain, "Point".toByteArray()), "no message in the trace of ${p.label}")
            }
        }

    @Test
    fun `an operation stored by the version 0_4_250 reads back as it was, nothing wiped`() =
        runBlocking {
            val relay = MemoryRelay()
            val (a, b, group) = operation(relay, title = "Opération Chartreuse")
            settle(a, b)
            val before = a.c.view.value
            a.c.close()
            // The version 0.4.250 wrote neither the messages nor any trace: its folder had the parts and the link only.
            val dir = File(a.root, a.c.ids().single())
            assertTrue(File(dir, StoredOperation.MESSAGES_FILE).delete())
            assertFalse(File(a.root, DissolutionTraces.FOLDER).exists())
            val names = dir.listFiles().orEmpty().map { it.name }.toSet()
            assertTrue(names.containsAll(StoredPart.entries.map { it.file } + StoredOperation.LINK_FILE), "$names")
            synchronized(problems) { problems.clear() }

            assertEquals(StartResult.STARTED, a.restart())
            val after = a.c.view.value
            assertEquals(OperationPhase.PLACED, after.phase)
            assertEquals(before.ensembleId, after.ensembleId)
            assertEquals("Opération Chartreuse", after.operationName)
            assertEquals(before.groups, after.groups)
            assertEquals(before.members.map { it.id to it.name }, after.members.map { it.id to it.name })
            assertTrue(after.threads.isEmpty())
            synchronized(problems) {
                assertTrue(problems.none { it.where.startsWith("hierarchy.store") }, "nothing said damaged: $problems")
            }
            // Messages are kept from now on.
            settle(a, b)
            assertEquals(ShareOutcome.SENT, a.c.sendMessage(Channel.unit(group), "Première après la mise à jour"))
            assertEquals(StartResult.STARTED, a.restart())
            assertEquals(
                listOf("Première après la mise à jour"),
                a.c.view.value.threads[Channel.unit(group)]?.map { it.text },
            )
            assertEquals(DissolutionTrace.KEPT_MS, 7 * day)
        }
}
