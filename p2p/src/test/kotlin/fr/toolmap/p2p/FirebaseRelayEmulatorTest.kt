package fr.toolmap.p2p

import fr.toolmap.core.p2p.GroupCrypto
import fr.toolmap.core.p2p.GroupRecord
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MeshListener
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the relay against the real Firebase emulators (auth on 9099, database on 9000, with `firebase/database.rules.json`).
 * Skipped unless `FIREBASE_EMULATOR=1`: `scripts/firebase-emulator-test.sh` starts them and runs this class.
 *
 * Or against a real project, by hand: set `TOOLMAP_TEST_FIREBASE_URL` (database address) and
 * `TOOLMAP_TEST_FIREBASE_KEY` (web API key). The test rooms are deleted afterwards; the key is read from the
 * environment and never written down.
 */
class FirebaseRelayEmulatorTest {
    private val emulated = System.getenv("FIREBASE_EMULATOR") == "1"

    /**
     * A real Firebase project, when the run is pointed at one (the class comment says how). Never used together
     * with the emulators.
     */
    private val remote: RelayConfig? =
        System.getenv("TOOLMAP_TEST_FIREBASE_URL")?.takeIf { !emulated && it.isNotBlank() }?.let { url ->
            val key = System.getenv("TOOLMAP_TEST_FIREBASE_KEY").orEmpty()
            require(key.isNotBlank()) { "TOOLMAP_TEST_FIREBASE_KEY must be set with TOOLMAP_TEST_FIREBASE_URL" }
            RelayConfig(url, key)
        }
    private val http =
        if (remote != null) {
            OkHttpClient()
        } else {
            OkHttpClient
                .Builder()
                .dns(
                    object : okhttp3.Dns {
                        override fun lookup(hostname: String) = listOf(InetAddress.getLoopbackAddress())
                    },
                ).build()
        }
    private val endpoints =
        if (remote != null) {
            FirebaseEndpoints()
        } else {
            FirebaseEndpoints(
                signUp = "http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:signUp",
                refresh = "http://127.0.0.1:9099/securetoken.googleapis.com/v1/token",
                dbBase = "http://demo-toolmap-default-rtdb.localhost:9000",
            )
        }
    private val config = remote ?: RelayConfig("https://demo-toolmap-default-rtdb.localhost", "fake-key")

    private class MemoryAuth : AuthStore {
        private var token: String? = null

        override fun read() = token

        override fun write(refreshToken: String?) {
            token = refreshToken
        }
    }

    private inner class Side(
        val group: GroupRecord,
        val memberId: Long,
        val name: String,
    ) {
        val links = CopyOnWriteArrayList<Long>()
        val closedLinks = CopyOnWriteArrayList<Long>()
        val positions = CopyOnWriteArrayList<Pair<Long, PeerFix>>()
        val frames = CopyOnWriteArrayList<ByteArray>()
        lateinit var node: MeshNode
        val relay: FirebaseRelay =
            FirebaseRelay(
                config,
                group.groupId,
                object : LinkListener {
                    override fun onLinkOpen(
                        peer: Long,
                        name: String,
                        color: Int,
                        link: MeshLink,
                    ) {
                        links += peer
                        node.onLinkOpen(peer, name, color, link)
                    }

                    override fun onLinkClosed(peer: Long) {
                        closedLinks += peer
                        node.onLinkClosed(peer)
                    }

                    override fun onFrame(
                        peer: Long,
                        bytes: ByteArray,
                    ) {
                        frames += bytes
                        node.onFrame(peer, bytes)
                    }
                },
                MemoryAuth(),
                http,
                endpoints,
            )

        init {
            relay.identity = TransportIdentity(group.groupId, memberId, name, 0xFF112233.toInt())
            node =
                MeshNode(
                    group.groupId,
                    group.key,
                    memberId,
                    name,
                    0xFF112233.toInt(),
                    relay,
                    object : MeshListener {
                        override fun onPosition(
                            member: Long,
                            fix: PeerFix,
                        ) {
                            positions += member to fix
                        }
                    },
                )
        }
    }

    /**
     * On a real project, removes the group's room once the test is over, so that nothing is left in the database.
     * A failure is reported with the exception's class only (never the URL, which carries the token): it must not
     * replace the result of the test.
     */
    private fun deleteRoom(group: GroupRecord) {
        val target = remote ?: return
        try {
            val token = FirebaseAuth(target, { http }, MemoryAuth(), endpoints) { System.currentTimeMillis() }.token()
            val url = "${target.base}/rooms/${RelayPaths.room(group.groupId)}.json?auth=$token"
            http.newCall(Request.Builder().url(url).delete().build()).execute().use { response ->
                if (!response.isSuccessful) System.err.println("test room not deleted: HTTP ${response.code}")
            }
        } catch (e: Exception) {
            System.err.println("test room not deleted: ${e.javaClass.simpleName}")
        }
    }

    private fun await(
        what: String,
        timeoutMs: Long = 25_000,
        condition: () -> Boolean,
    ) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("timeout waiting for $what")
    }

    @Test
    fun `two members find each other through the room and share positions end to end`() {
        assumeTrue(emulated || remote != null)
        val group = GroupRecord.create()
        val a = Side(group, 0x1111L, "Alice")
        val b = Side(group, 0x2222L, "Bob")
        try {
            a.relay.start()
            b.relay.start()
            await("A online") { a.relay.state == ServerState.ONLINE }
            await("B online") { b.relay.state == ServerState.ONLINE }
            await("A sees B") { b.memberId in a.links }
            await("B sees A") { a.memberId in b.links }

            val fix = PeerFix(45.5, 5.9, altM = 300, timeSec = System.currentTimeMillis() / 1000)
            a.node.publish(fix)
            await("B gets A's position") { b.positions.any { it.first == a.memberId } }
            val got = b.positions.first { it.first == a.memberId }.second
            assertEquals(45.5, got.lat, 1e-6)

            val bobView = a.node.members().first { it.id == b.memberId }
            assertEquals(MemberStatus.DIRECT, bobView.status)
            assertEquals("Bob", bobView.name)

            // Nothing readable on the server: every frame is sealed (a wrong key opens nothing).
            val stranger = GroupCrypto(GroupCrypto.randomKey())
            assertTrue(b.frames.isNotEmpty())
            for (f in b.frames) {
                val failed = runCatching { fr.toolmap.core.p2p.Wire.decode(f, stranger) }.isFailure
                assertTrue(failed, "a frame was readable with a foreign key")
            }

            // A clean leave is seen at once.
            b.node.leave()
            b.relay.close()
            await("A sees B leave") { a.node.members().first { it.id == b.memberId }.status == MemberStatus.LEFT }
            assertNotNull(a.node.members().firstOrNull { it.id == b.memberId })
        } finally {
            a.relay.close()
            b.relay.close()
            deleteRoom(group)
        }
    }

    @Test
    fun `a member that is refused by the rules is told so`() {
        assumeTrue(emulated || remote != null)
        val group = GroupRecord.create()
        val bad = Side(group, 0x3333L, "x".repeat(100))
        try {
            // A name longer than the rules allow cannot be written: the heartbeat is refused.
            bad.relay.start()
            await("refused") { bad.relay.state == ServerState.REFUSED }
        } finally {
            bad.relay.close()
            deleteRoom(group)
        }
    }
}
