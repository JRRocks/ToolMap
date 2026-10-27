package fr.toolmap.p2p

import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MsgType
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The relay defects found by the live tests (P21.5 b, e, f, g, h, n, p, q, r), against a fake Firebase. */
class FirebaseRelayFixesTest {
    private class Call(
        val method: String,
        val path: String,
        val auth: String?,
        val stream: Boolean,
        /** First request on its connection: a new connection. */
        val newConnection: Boolean,
        val body: String = "",
    )

    private val server = MockWebServer()
    private val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

    /** Lifetime of the first token: short, so that a write renews it (P21.5 g). */
    @Volatile
    private var firstTokenLifetimeS = 3600

    @Volatile
    private var failPositions = false

    /** What a `shallow` read of the own control frames answers (P21.5 r). */
    @Volatile
    private var ownFrameKeys = "null"

    /** Added to the phone clock, to move it during a test (P21.5 n, q). */
    @Volatile
    private var clockShiftMs = 0L

    /** Moves the phone clock by this much when the server time is first read (P21.5 n). */
    @Volatile
    private var shiftAtFirstTimeRead = 0L

    @Volatile
    private var link: MeshLink? = null

    private val listener =
        object : LinkListener {
            override fun onLinkOpen(
                peer: Long,
                name: String,
                color: Int,
                link: MeshLink,
            ) {
                this@FirebaseRelayFixesTest.link = link
            }

            override fun onLinkClosed(peer: Long) = Unit

            override fun onFrame(
                peer: Long,
                bytes: ByteArray,
            ) = Unit
        }

    private class MemoryAuth : AuthStore {
        override fun read(): String? = null

        override fun write(refreshToken: String?) = Unit
    }

    @Before
    fun setUp() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    val stream = request.getHeader("Accept") == "text/event-stream"
                    val auth = request.requestUrl?.queryParameter("auth")
                    val body = request.body.readUtf8()
                    calls += Call(request.method.orEmpty(), path, auth, stream, request.sequenceNumber == 0, body)
                    return when {
                        path.startsWith("/signUp") -> {
                            val lifetime = firstTokenLifetimeS
                            MockResponse().setBody(
                                """{"idToken":"t1","refreshToken":"r1","expiresIn":"$lifetime","localId":"u"}""",
                            )
                        }

                        path.startsWith("/refresh") -> {
                            MockResponse().setBody(
                                """{"id_token":"t2","refresh_token":"r2","expires_in":"3600","user_id":"u"}""",
                            )
                        }

                        stream -> {
                            // Another member's heartbeat (at the server time below), then a stream that stays open.
                            val event =
                                "event: put\ndata: {\"path\":\"/\",\"data\":{\"m2b\":{\"h\":" +
                                    "{\"t\":$SERVER_MS,\"n\":\"B\",\"k\":0}}}}\n\n"
                            MockResponse()
                                .setHeader("Content-Type", "text/event-stream")
                                .setBody(event + ": waiting\n\n".repeat(100))
                                .throttleBody(event.length.toLong(), 1, TimeUnit.HOURS)
                        }

                        request.method == "GET" && request.requestUrl?.queryParameter("shallow") == "true" -> {
                            MockResponse().setBody(ownFrameKeys)
                        }

                        request.method == "GET" -> {
                            clockShiftMs += shiftAtFirstTimeRead
                            shiftAtFirstTimeRead = 0
                            MockResponse().setBody("$SERVER_MS")
                        }

                        request.method == "PUT" && path.endsWith("/p.json") && failPositions -> {
                            MockResponse().setResponseCode(503)
                        }

                        request.method == "POST" -> {
                            MockResponse().setBody("""{"name":"-k${calls.size}"}""")
                        }

                        else -> {
                            MockResponse().setBody("{}")
                        }
                    }
                }
            }
        server.start()
    }

    @After
    fun tearDown() {
        // The open streams are still being trickled out: shutting down may complain about them.
        runCatching { server.shutdown() }
    }

    private fun relay(): FirebaseRelay {
        val endpoints =
            FirebaseEndpoints(
                signUp = server.url("/signUp").toString(),
                refresh = server.url("/refresh").toString(),
                dbBase = server.url("/").toString(),
            )
        val groupId = ByteArray(8) { 1 }
        return FirebaseRelay(
            RelayConfig("https://example.invalid", "key"),
            groupId,
            listener,
            MemoryAuth(),
            endpoints = endpoints,
            clockMs = { System.currentTimeMillis() + clockShiftMs },
        )
            .apply { identity = TransportIdentity(groupId, OWN_ID, "A", 0) }
    }

    private fun waitFor(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private fun count(predicate: (Call) -> Boolean) = synchronized(calls) { calls.count(predicate) }

    private fun streams() = count { it.stream }

    private fun positionWrites() = count { it.method == "PUT" && it.path.endsWith("/$OWN/p.json") }

    private fun controlWrites() = count { it.method == "POST" && it.path.endsWith("/$OWN/c.json") }

    private fun heartbeats() = count { it.method == "PUT" && it.path.endsWith("/$OWN/h.json") }

    /** A frame of [type]: the relay only reads its first byte (the type) and never decrypts it. */
    private fun frame(
        type: MsgType,
        n: Int,
    ) = ByteArray(FRAME_BYTES) { if (it == 0) type.code.toByte() else n.toByte() }

    private fun linked(): MeshLink {
        assertTrue(waitFor(3_000) { link != null }, "the other member's link opens")
        return assertNotNull(link)
    }

    @Test
    fun `a PING goes with the control frames and leaves the latest position in p`() {
        val r = relay()
        r.start()
        try {
            val l = linked()
            l.send(frame(MsgType.POS, 1), true)
            assertTrue(waitFor(2_000) { positionWrites() == 1 }, "the position goes to p")
            l.send(frame(MsgType.PING, 2), true)
            assertTrue(waitFor(2_000) { controlWrites() == 1 }, "the PING goes to c")
            Thread.sleep(300)
            assertEquals(1, positionWrites(), "p still holds the position")
        } finally {
            r.close()
        }
    }

    @Test
    fun `a position held during an outage is written as soon as the stream reopens`() {
        val r = relay()
        r.onNetwork("net-1")
        r.start()
        try {
            val l = linked()
            failPositions = true
            l.send(frame(MsgType.POS, 1), true)
            assertTrue(waitFor(2_000) { positionWrites() == 1 }, "the write fails")
            failPositions = false
            // A new network: the stream reopens at once; the next heartbeat would only come 15 s after the start.
            r.onNetwork("net-2")
            r.reconnectNow()
            assertTrue(waitFor(3_000) { streams() == 2 }, "the stream reopens")
            assertTrue(waitFor(1_500) { positionWrites() == 2 }, "the held position is written at once")
        } finally {
            r.close()
        }
    }

    @Test
    fun `a stream opened on the previous network is dropped at once, even a recent one`() {
        val r = relay()
        r.onNetwork("net-1")
        r.start()
        try {
            assertTrue(waitFor(3_000) { streams() == 1 }, "stream opened")
            // Same network, stream under 5 s old: kept (the call that comes with the registration).
            r.onNetwork("net-1")
            r.reconnectNow()
            Thread.sleep(1_000)
            assertEquals(1, streams(), "a recent stream on the same network is kept")
            // A write meanwhile: its connection, of the first network, stays idle in the pool.
            linked().send(frame(MsgType.POS, 1), true)
            assertTrue(waitFor(2_000) { positionWrites() == 1 }, "position written")
            Thread.sleep(200)
            // A second network arrives: the stream still uses the first one.
            val before = calls.size
            r.onNetwork("net-2")
            r.reconnectNow()
            assertTrue(waitFor(2_500) { streams() == 2 }, "the stream moves to the new network")
            // Not on a connection of the first network, idle in the pool: Android would cut it 30 s later.
            val first = synchronized(calls) { calls[before] }
            assertTrue(first.newConnection, "the first call after the change opens a new connection (${first.path})")
        } finally {
            r.close()
        }
    }

    @Test
    fun `a renewed token reopens the stream at once, with no gap in the state`() {
        // The token expires 2 min and 1 s after the sign-in: from 1 s on, a write renews it first.
        firstTokenLifetimeS = 121
        val states: MutableList<ServerState> = Collections.synchronizedList(ArrayList())
        val r = relay()
        r.onState = { states += it }
        r.start()
        try {
            val l = linked()
            assertTrue(waitFor(3_000) { states.contains(ServerState.ONLINE) }, "online")
            Thread.sleep(1_200)
            l.send(frame(MsgType.POS, 1), true)
            assertTrue(waitFor(3_000) { streams() == 2 }, "the stream reopens after the renewal")
            val last = synchronized(calls) { calls.last { it.stream } }
            assertEquals("t2", last.auth, "with the new token")
            assertEquals(1, count { it.path.startsWith("/refresh") }, "renewed once")
            Thread.sleep(300)
            assertFalse(ServerState.UNREACHABLE in states, "the state never left online: $states")
        } finally {
            r.close()
        }
    }

    @Test
    fun `no attempt while Android blocks the network, one at once when it lets it back`() {
        val r = relay()
        r.onBlocked(true)
        r.start()
        try {
            Thread.sleep(1_500)
            assertEquals(0, calls.size, "nothing tried while blocked")
            r.onBlocked(false)
            assertTrue(waitFor(1_500) { streams() == 1 }, "connected right after")
        } finally {
            r.close()
        }
    }

    @Test
    fun `no attempt while there is no network, one at once when a network comes`() {
        val r = relay()
        r.onOffline()
        r.start()
        try {
            Thread.sleep(1_500)
            assertEquals(0, calls.size, "nothing tried without a network")
            r.onNetwork("net-1")
            assertTrue(waitFor(1_500) { streams() == 1 }, "connected right after")
        } finally {
            r.close()
        }
    }

    @Test
    fun `the heartbeat follows the wall clock, not the timer's own delay`() {
        val r = relay()
        r.start()
        try {
            linked()
            val before = heartbeats()
            Thread.sleep(1_500)
            assertEquals(before, heartbeats(), "not before 15 s")
            // 16 s pass on the wall clock while the processor slept: the beat goes within a second or so.
            clockShiftMs += 16_000
            assertTrue(waitFor(2_500) { heartbeats() == before + 1 }, "the beat is sent")
        } finally {
            r.close()
        }
    }

    @Test
    fun `a clock measure spoilt by a clock change is made again`() {
        // The clock goes back by 815 s while the first measure waits for the server time.
        shiftAtFirstTimeRead = -815_000
        val r = relay()
        r.start()
        try {
            assertTrue(waitFor(3_000) { streams() == 1 }, "stream opened")
            assertEquals(
                2,
                synchronized(calls) {
                    calls.takeWhile { !it.stream }
                }.count { it.path.endsWith("/h.json") },
            )
        } finally {
            r.close()
        }
    }

    @Test
    fun `frames left on the server by a previous process are deleted at the start`() {
        val old = PushIds.timePrefix(SERVER_MS - 600_000) + "oldoldoldold"
        val recent = PushIds.timePrefix(SERVER_MS - 10_000) + "newnewnewnew"
        assertEquals(SERVER_MS - 600_000, PushIds.timeMs(old))
        ownFrameKeys = """{"$old":true,"$recent":true}"""
        val r = relay()
        r.start()
        try {
            val purge = { count { it.method == "PATCH" && it.path.endsWith("/$OWN/c.json") } }
            assertTrue(waitFor(3_000) { purge() == 1 }, "one grouped delete")
            val body = synchronized(calls) { calls.first { it.method == "PATCH" }.body }
            assertTrue(old in body, body)
            assertFalse(recent in body, "a frame under 2 min old stays: $body")
        } finally {
            r.close()
        }
    }

    private companion object {
        const val SERVER_MS = 1_700_000_000_000L
        const val OWN_ID = 42L
        const val OWN = "m2a"
        const val FRAME_BYTES = 40
    }
}
