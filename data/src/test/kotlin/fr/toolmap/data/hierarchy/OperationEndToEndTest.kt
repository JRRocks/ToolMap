package fr.toolmap.data.hierarchy

import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The commands of the end-to-end trial (P39.12, `OperationEndToEnd`, `tools/emulator/operation-e2e.sh`) in the order
 * the script gives them, on two phones of a room in memory ([MemoryRelay]) instead of two emulators and the real
 * server: what the script expects of each answer. The network cut of step 4 and the moment of the trace that is
 * waited out at the end are left to the emulators (they need a network to cut, and two minutes).
 */
class OperationEndToEndTest {
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

    private val server = RelayConfig.of("https://example.invalid", "key")
    private val relay = MemoryRelay()

    private fun phone(
        root: File,
        vault: KeyVault,
    ) = EndToEndSession(root, vault, relay, server, TrafficMeter())

    private suspend fun EndToEndSession.ok(vararg command: String): String {
        val answer = run(command[0], command.drop(1)) { }
        assertTrue(answer.startsWith("ok"), "${command[0]}: $answer")
        return answer
    }

    private fun value(
        key: String,
        line: String,
    ): String? = Regex("(?:^| )$key=([^ ]*)").find(line)?.groupValues?.get(1)

    @Test
    fun `the steps of the script, from the creation to the dissolution told by the trace`() =
        runBlocking<Unit> {
            val rootA = File(temp.root, "a")
            val rootB = File(temp.root, "b")
            val vaultA = SoftwareKeyVault()
            val vaultB = SoftwareKeyVault()
            var a = phone(rootA, vaultA)
            var b = phone(rootB, vaultB)
            a.ok("reset")
            b.ok("reset")

            // 1. Create, invite, ask (with the name, R5), let in, place.
            val created = a.ok("create", "Exercice", "Alpha")
            assertEquals("PLACED", value("phase", created))
            val invite = assertNotNull(a.invitation())
            val request = async { a.run("await-request", listOf("Bravo", "20")) { } }
            val joined = b.ok("join", "Bravo", "Exercice", invite)
            assertEquals("true", value("previewName", joined))
            assertTrue(request.await().startsWith("ok"), "the request with its name")
            a.ok("admit", "Bravo")
            b.ok("await-phase", "WAITING", "20")
            a.ok("organize", "Nord")
            val placed = b.ok("await-placed", "Nord", "Alpha", "20")
            assertEquals("LEADERS", value("alert", placed), "a group leader alerts every leader")

            // 2. Positions.
            val positions = async { a.run("await-positions", listOf("3", "20")) { } }
            val sent = b.ok("send-positions", "3", "1100")
            assertEquals("3", value("sent", sent))
            val got = positions.await()
            assertTrue(got.startsWith("ok"), "the last position arrives: $got ($sent)")

            // 3. A message in each thread, where the other reads it and nowhere else; alerts; a waypoint.
            val fromA = a.ok("send-messages", "ma")
            b.ok("await-messages", "ma", value("peerReads", fromA)!!, "20")
            val fromB = b.ok("send-messages", "mb")
            val readsA = value("peerReads", fromB)!!
            assertNotEquals(value("threads", fromB), readsA, "B writes in a thread A does not read (its group's)")
            a.ok("await-messages", "mb", readsA, "20")
            for ((sender, receiver, text) in listOf(Triple(a, b, "alerte-a"), Triple(b, a, "alerte-b"))) {
                val alert = sender.ok("send-alert", text)
                assertEquals("true", value("peerReads", alert))
                receiver.ok("await-alert", text, "20")
                sender.ok("lift-alert")
                receiver.ok("await-alert-gone", "20")
            }
            a.ok("put-waypoint")
            b.ok("await-object", "20")
            a.ok("remove-waypoint")
            b.ok("await-object-gone", "20")

            // 4. A stops (a new process later), B shares meanwhile; A opens again and catches up.
            a.ok("close")
            b.ok("put-waypoint")
            a = phone(rootA, vaultA)
            a.ok("open")
            a.ok("await-object", "20")
            a.ok("rename-group", "Sud")
            b.ok("await-group", "Sud", "20")

            // 5. B leaves: gone, invitation renewed (R3); the old invitation is refused.
            a.invitation()
            val departure = async { a.run("await-departure", listOf("20")) { } }
            b.ok("leave")
            val gone = departure.await()
            assertTrue(gone.startsWith("ok"), gone)
            assertTrue(value("epoch", gone)!!.toInt() > value("epochBefore", gone)!!.toInt())
            val refused = async { a.run("await-no-request", listOf("Charlie", "4")) { } }
            // As on the emulators, A is listening before B asks (B's first request goes at once, the next in 20 s).
            delay(300)
            b.ok("join", "Charlie", "Exercice", invite)
            val noRequest = refused.await()
            assertTrue(noRequest.startsWith("ok"), noRequest)
            b.ok("reset")

            // 6. B comes in again with a new invitation; away, A dissolves; A started again tells B by the trace.
            val renewed = assertNotNull(a.invitation())
            assertNotEquals(invite, renewed)
            val again = async { a.run("await-request", listOf("Delta", "20")) { } }
            b.ok("join", "Delta", "Exercice", renewed)
            assertTrue(again.await().startsWith("ok"))
            a.ok("admit", "Delta")
            b.ok("await-phase", "WAITING", "20")
            b.ok("close")
            val dissolved = a.ok("dissolve")
            assertEquals("DISSOLVED", value("ending", dissolved))
            assertEquals("true", value("traceKept", dissolved))
            assertEquals("7", value("keptDays", dissolved))
            // The transport of the operation leaves the room a moment after the end.
            delay(6_000)
            a = phone(rootA, vaultA)
            a.ok("announce")
            b = phone(rootB, vaultB)
            val ended = async { b.run("await-ended", listOf("20")) { } }
            b.run("open", emptyList()) { }
            val told = ended.await()
            assertTrue(told.startsWith("ok"), told)
            assertEquals("true", value("byTrace", told))
            assertEquals("true", value("traceKept", told))
            a.ok("reset")
            b.ok("reset")
        }

    @Test
    fun `the kinds of the server's calls never keep the room`() {
        val room = "0123456789abcdef0123456789abcdef"
        assertEquals("h", TrafficMeter.kindOf("PUT", "x.firebasedatabase.app", "/rooms/$room/b/m1f/h.json"))
        assertEquals("clock", TrafficMeter.kindOf("GET", "x.firebasedatabase.app", "/rooms/$room/b/m1f/h/t.json"))
        assertEquals("p", TrafficMeter.kindOf("PUT", "x", "/rooms/$room/b/m1f/p.json"))
        assertEquals("c", TrafficMeter.kindOf("POST", "x", "/rooms/$room/b/m1f/c.json"))
        assertEquals("c.clean", TrafficMeter.kindOf("PATCH", "x", "/rooms/$room/b/m1f/c.json"))
        assertEquals("c.list", TrafficMeter.kindOf("GET", "x", "/rooms/$room/b/m1f/c.json"))
        assertEquals("stream", TrafficMeter.kindOf("GET", "x", "/rooms/$room/b.json"))
        assertEquals("leave", TrafficMeter.kindOf("DELETE", "x", "/rooms/$room/b/m1f.json"))
        assertEquals("auth", TrafficMeter.kindOf("POST", "identitytoolkit.googleapis.com", "/v1/accounts:signUp"))
        assertEquals("report", TrafficMeter.kindOf("PUT", "x", "/reports/abc.json"))
        assertEquals("POSITION", TrafficMeter.genreOf(byteArrayOf(4, 4, 0)))
        assertEquals("SUMMARY", TrafficMeter.genreOf(byteArrayOf(0xC7.toByte(), 2, 0)))
    }
}
