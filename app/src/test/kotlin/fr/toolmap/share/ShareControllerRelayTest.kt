package fr.toolmap.share

import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.RoomInvite
import fr.toolmap.core.p2p.RoomInviteCodec
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.record.Fix
import fr.toolmap.p2p.ShareStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val NOW_MS = 1_790_000_000_000L
private const val DAY_SEC = 86_400L

private class FakeRelay(
    val config: RelayConfig,
    val groupId: ByteArray,
    val listener: LinkListener,
) : RelayTransport {
    override var identity: TransportIdentity? = null
    override var onState: ((ServerState) -> Unit)? = null
    override var state: ServerState = ServerState.CONNECTING
    var started = false
    var closed = false
    val banned = ArrayList<Long>()
    val networks = ArrayList<String>()
    var blocked = false
    var reconnects = 0

    override fun start() {
        started = true
    }

    override fun reconnectNow() {
        reconnects++
    }

    override fun onNetwork(network: String) {
        networks += network
    }

    override fun onBlocked(blocked: Boolean) {
        this.blocked = blocked
    }

    override fun ban(peer: Long) {
        banned += peer
    }

    override fun close() {
        closed = true
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

    override fun drop(peer: Long) = Unit

    fun report(next: ServerState) {
        state = next
        onState?.invoke(next)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareControllerRelayTest {
    private lateinit var dir: File
    private lateinit var store: ShareStore
    private val relays = ArrayList<FakeRelay>()
    private val server = RelayConfig.of("https://toolmap-test-default-rtdb.firebasedatabase.app", "AIzaTestKey")

    private val noGps =
        object : LocationSource {
            override fun fixes(
                minIntervalMillis: Long,
                minDistanceMeters: Float,
            ): Flow<Fix> = emptyFlow()
        }

    @Before
    fun setUp() {
        dir = createTempDirectory("relay").toFile()
        store = ShareStore(File(dir, "share.json"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.controller(
        builtIn: RelayConfig? = server,
        network: NetworkWatch? = null,
    ) = ShareController(
        store = store,
        transports = { _, _ -> error("direct mode must not be used") },
        location = noGps,
        deviceName = { "Phone" },
        batteryPercent = { 80 },
        clockMs = { NOW_MS + currentTime },
        scope = backgroundScope,
        relays =
            RelayTransportFactory { config, groupId, listener ->
                FakeRelay(config, groupId, listener).also { relays += it }
            },
        builtInServer = builtIn,
        network = network,
    )

    @Test
    fun `a group made with a server uses it and the invitation is ready at once`() =
        runTest {
            val c = controller()
            c.load()
            assertEquals(server, c.state.value.serverForNewGroups)
            assertTrue(c.state.value.serverIsBuiltIn)
            c.invite()
            val ui = c.state.value
            assertTrue(ui.onServer)
            assertFalse(ui.preparing)
            val code = assertNotNull(ui.invitation)
            val invite = assertNotNull(RoomInviteCodec.parseOrNull(code.qrText))
            assertEquals(server, invite.server)
            assertEquals(server, store.read().group?.server)
            assertTrue(relays.single().started)
        }

    @Test
    fun `the server state of the transport reaches the screen`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val relay = relays.single()
            relay.report(ServerState.ONLINE)
            assertEquals(ServerState.ONLINE, c.state.value.server)
            relay.report(ServerState.UNREACHABLE)
            assertEquals(ServerState.UNREACHABLE, c.state.value.server)
        }

    @Test
    fun `an invitation of the server joins the group without an answer`() =
        runTest {
            val c = controller(builtIn = null)
            c.load()
            val key = ByteArray(32) { it.toByte() }
            val groupId = ByteArray(16) { (it + 1).toByte() }
            val invite = RoomInvite(NOW_MS / 1000, groupId, key, 0x77L, "Alice", server)
            c.handleCode(RoomInviteCodec.toLink(invite))
            val ui = c.state.value
            assertTrue(ui.inGroup)
            assertTrue(ui.onServer)
            assertEquals("Alice", ui.justJoined)
            assertNull(ui.problem)
            assertEquals(server, store.read().group?.server)
        }

    @Test
    fun `an expired invitation is refused`() =
        runTest {
            val c = controller()
            c.load()
            val old = NOW_MS / 1000 - 8 * DAY_SEC
            val invite = RoomInvite(old, ByteArray(16) { 3 }, ByteArray(32) { 4 }, 0x77L, "Alice", server)
            c.handleCode(RoomInviteCodec.toQrText(invite))
            assertEquals(ShareProblem.EXPIRED, c.state.value.problem)
            assertFalse(c.state.value.inGroup)
        }

    @Test
    fun `an invitation to another group is refused while in a group`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val invite = RoomInvite(NOW_MS / 1000, ByteArray(16) { 9 }, ByteArray(32) { 4 }, 0x77L, "Alice", server)
            c.handleCode(RoomInviteCodec.toQrText(invite))
            assertEquals(ShareProblem.OTHER_GROUP, c.state.value.problem)
        }

    @Test
    fun `leaving the group closes the server connection`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            c.leaveGroup()
            assertTrue(relays.single().closed)
            assertFalse(c.state.value.inGroup)
            assertEquals(ServerState.NONE, c.state.value.server)
        }

    @Test
    fun `the server connection follows the default network as long as the group exists`() =
        runTest {
            val watch = FakeNetworkWatch()
            val c = controller(network = watch)
            c.load()
            assertNull(watch.listener, "no group, no watch")
            c.createGroup()
            val relay = relays.single()
            val events = assertNotNull(watch.listener)
            events.onNetwork("100", retry = false)
            events.onBlocked(true)
            events.onNetwork("101", retry = true)
            assertEquals(listOf("100", "101"), relay.networks)
            assertTrue(relay.blocked)
            assertEquals(1, relay.reconnects, "a new network is tried at once")
            c.leaveGroup()
            assertTrue(watch.closed)
        }
}

private class FakeNetworkWatch : NetworkWatch {
    var listener: NetworkWatch.Listener? = null
    var closed = false

    override fun start(listener: NetworkWatch.Listener): AutoCloseable {
        this.listener = listener
        return AutoCloseable { closed = true }
    }
}
