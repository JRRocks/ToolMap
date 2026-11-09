package fr.toolmap.p2p

import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.TransportIdentity
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The relay comes back quickly after a network change (field test of 2026-10-14), against a fake Firebase. */
class FirebaseRelayReconnectTest {
    private val server = MockWebServer()
    private val streams = AtomicInteger()

    @Volatile
    private var streamAnswer: () -> MockResponse = { MockResponse().setResponseCode(503) }

    private val listener =
        object : LinkListener {
            override fun onLinkOpen(
                peer: Long,
                name: String,
                color: Int,
                link: MeshLink,
            ) = Unit

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
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/signUp") -> {
                            MockResponse().setBody(
                                """{"idToken":"t","refreshToken":"r","expiresIn":"3600","localId":"u"}""",
                            )
                        }

                        path.startsWith("/refresh") -> {
                            MockResponse().setBody(
                                """{"id_token":"t2","refresh_token":"r","expires_in":"3600","user_id":"u"}""",
                            )
                        }

                        request.getHeader("Accept") == "text/event-stream" -> {
                            streams.incrementAndGet()
                            streamAnswer()
                        }

                        request.method == "GET" -> {
                            MockResponse().setBody("1700000000000")
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
        // The silent stream of the second test is still being trickled out: shutting down may complain about it.
        runCatching { server.shutdown() }
    }

    private fun relay(): FirebaseRelay {
        val endpoints =
            FirebaseEndpoints(
                signUp = server.url("/signUp").toString(),
                refresh = server.url("/refresh").toString(),
                dbBase = server.url("/").toString(),
            )
        return FirebaseRelay(
            RelayConfig("https://example.invalid", "key"),
            ByteArray(8) {
                1
            },
            listener,
            MemoryAuth(),
            endpoints = endpoints,
        )
            .apply { identity = TransportIdentity(ByteArray(8) { 1 }, 42L, "A", 0) }
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

    @Test
    fun reconnectNowSkipsTheBackoffPause() {
        val r = relay()
        r.start()
        try {
            // Attempts at about 0, 1 and 3 s; the next one would only come 4 s later.
            assertTrue(waitFor(6_000) { streams.get() >= 3 }, "three failed attempts")
            Thread.sleep(300)
            val before = streams.get()
            r.reconnectNow()
            assertTrue(waitFor(1_500) { streams.get() > before }, "an attempt right after reconnectNow")
        } finally {
            r.close()
        }
    }

    @Test
    fun reconnectNowDropsAStreamLeftOnTheOldNetwork() {
        // A stream that stays silent (the network went away under it) until its 45 s read timeout.
        streamAnswer = {
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(": waiting\n\n".repeat(100))
                .throttleBody(1, 1, TimeUnit.HOURS)
        }
        val r = relay()
        r.start()
        try {
            assertTrue(waitFor(3_000) { streams.get() == 1 }, "stream opened")
            // A stream opened under 5 s ago is already on the new network and kept.
            Thread.sleep(5_200)
            r.reconnectNow()
            assertTrue(waitFor(2_500) { streams.get() >= 2 }, "the silent stream is replaced at once")
        } finally {
            r.close()
        }
    }

    @Test
    fun aServerThatKeepsRefusingIsNotAskedEveryFewSeconds() {
        streamAnswer = { MockResponse().setResponseCode(401).setBody("{\"error\":\"Permission denied\"}") }
        val r = relay()
        r.start()
        try {
            // The first refusal is tried again at once, with a fresh token; the second one waits a minute.
            assertTrue(waitFor(4_000) { streams.get() == 2 }, "a second attempt right after the first refusal")
            Thread.sleep(3_500)
            assertEquals(2, streams.get(), "no third attempt in the seconds that follow")
            // A network change still brings an attempt at once.
            r.reconnectNow()
            assertTrue(waitFor(1_500) { streams.get() == 3 }, "an attempt right after reconnectNow")
        } finally {
            r.close()
        }
    }

    @Test
    fun aRoomTooLargeToReadIsNotDownloadedAgainEveryFewSeconds() {
        // One event of 9 MB: more than the reader accepts (a room filled by someone who holds its id).
        val flood = "event: put\ndata: {\"path\":\"/\",\"data\":\"" + "x".repeat(9 * 1024 * 1024) + "\"}\n\n"
        streamAnswer = { MockResponse().setHeader("Content-Type", "text/event-stream").setBody(flood) }
        val r = relay()
        r.start()
        try {
            assertTrue(waitFor(6_000) { streams.get() == 1 }, "stream opened")
            Thread.sleep(4_000)
            assertEquals(1, streams.get(), "the room is not asked again in the seconds that follow")
        } finally {
            r.close()
        }
    }
}
