package fr.toolmap.share

import fr.toolmap.core.p2p.DtlsSetup
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MeshListener
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PairingKind
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.PairingTransport
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.TransportFactory
import fr.toolmap.core.record.Fix
import fr.toolmap.p2p.ShareStore
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE_MS = 1_790_000_000_000L
private const val PEER_ID = 0x1234L
private const val GROUP_FILL = 7

/** A transport the test drives: what WebRTC would produce comes from the test. */
private class FakeTransport(
    val listener: LinkListener,
) : PairingTransport {
    class Invite(
        override val payload: PairingPayload,
    ) : PairingTransport.Invitation {
        var accepted: PairingPayload? = null
        var cancelled = false

        override fun accept(answer: PairingPayload) {
            accepted = answer
        }

        override fun cancel() {
            cancelled = true
        }
    }

    override var identity: fr.toolmap.core.p2p.TransportIdentity? = null
    var invitation: Invite? = null
    var inviteFails = false
    var joinFails = false
    var joined: PairingPayload? = null
    var closed = false
    var nowSec = BASE_MS / 1000

    override fun invite(
        groupKey: ByteArray,
        done: (PairingTransport.Invitation?) -> Unit,
    ) {
        if (inviteFails) return done(null)
        val id = identity!!
        invitation = Invite(payload(PairingKind.OFFER, id.groupId, groupKey, id.memberId, id.name, nowSec))
        done(invitation)
    }

    override fun join(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    ) {
        joined = offer
        if (joinFails) return done(null)
        val id = identity!!
        done(payload(PairingKind.ANSWER, id.groupId, null, id.memberId, id.name, nowSec))
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

    override fun close() {
        closed = true
    }
}

private fun payload(
    kind: PairingKind,
    groupId: ByteArray,
    key: ByteArray?,
    memberId: Long,
    name: String,
    createdAtSec: Long,
) = PairingPayload(
    kind = kind,
    createdAtSec = createdAtSec,
    groupId = groupId,
    groupKey = key,
    memberId = memberId,
    name = name,
    color = 1,
    iceUfrag = "aB3d",
    icePwd = "x7Yq0pLm9Rt2VnB4cD6eF8gH",
    dtlsFingerprint = ByteArray(32) { it.toByte() },
    dtlsSetup = if (kind == PairingKind.ANSWER) DtlsSetup.ACTIVE else DtlsSetup.ACTPASS,
    candidates = emptyList(),
)

private class FakeGps : LocationSource {
    val flow = MutableSharedFlow<Fix>(extraBufferCapacity = 64)
    var failing = false

    /** The interval of each request, in order. */
    val intervals = ArrayList<Long>()

    override fun fixes(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Fix> =
        flow {
            intervals += minIntervalMillis
            if (failing) error("no GPS")
            flow.collect { emit(it) }
        }
}

/** The other phone: a real [MeshNode] that talks to the controller through frames passed by hand. */
private class Remote(
    val id: Long,
    val name: String,
    key: ByteArray,
    groupId: ByteArray,
    clock: () -> Long,
) {
    val positions = ArrayList<PeerFix>()
    val texts = ArrayList<String>()
    val sosTexts = ArrayList<Pair<String, PeerFix?>>()
    var kicked = false
    var toController: ((ByteArray) -> Unit)? = null

    val node =
        MeshNode(
            groupId,
            key,
            id,
            name,
            0xFF00FF00.toInt(),
            transport =
                object : fr.toolmap.core.p2p.MeshTransport {
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
                },
            listener =
                object : MeshListener {
                    override fun onPosition(
                        member: Long,
                        fix: PeerFix,
                    ) {
                        positions += fix
                    }

                    override fun onText(
                        member: Long,
                        text: String,
                    ) {
                        texts += text
                    }

                    override fun onSos(
                        member: Long,
                        fix: PeerFix?,
                        text: String,
                    ) {
                        sosTexts += text to fix
                    }

                    override fun onExcluded() {
                        kicked = true
                    }
                },
            clock = clock,
        )

    val link =
        object : MeshLink {
            override fun send(
                frame: ByteArray,
                onPositionChannel: Boolean,
            ): Boolean {
                toController?.invoke(frame)
                return true
            }

            override fun close() = Unit
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareControllerTest {
    private lateinit var dir: File
    private lateinit var store: ShareStore
    private val gps = FakeGps()
    private var transport: FakeTransport? = null
    private var failJoins = false

    @Before
    fun setUp() {
        dir = createTempDirectory("share").toFile()
        store = ShareStore(File(dir, "share.json"))
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun TestScope.controller(
        battery: Int? = 80,
        savedStore: ShareStore = store,
        phone: PhoneChecks? = null,
    ) = ShareController(
        store = savedStore,
        transports =
            TransportFactory { listener, _ ->
                FakeTransport(listener).also {
                    it.joinFails = failJoins
                    transport = it
                }
            },
        location = gps,
        deviceName = { "Phone" },
        batteryPercent = { battery },
        clockMs = { BASE_MS + currentTime },
        scope = backgroundScope,
        phone = phone,
    )

    /** A second phone in the controller's group with an open link both ways. */
    private fun TestScope.connectRemote(c: ShareController): Remote {
        val g = checkNotNull(store.read().group)
        val remote = Remote(PEER_ID, "Bob", g.key, g.groupId) { BASE_MS + currentTime }
        val t = checkNotNull(transport)
        val controllerLink =
            object : MeshLink {
                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean {
                    remote.node.onFrame(g.selfId, frame)
                    return true
                }

                override fun close() = Unit
            }
        remote.toController = { frame -> t.listener.onFrame(PEER_ID, frame) }
        t.listener.onLinkOpen(PEER_ID, "Bob", 1, controllerLink)
        remote.node.onLinkOpen(g.selfId, "Phone", 1, remote.link)
        runCurrent()
        return remote
    }

    private fun fix(
        atSec: Long,
        lat: Double = 45.0,
        lon: Double = 6.0,
    ) = Fix(lat, lon, ele = 1500.0, timeMillis = atSec * 1000, accuracyMeters = 5.0, speedMps = 1.0)

    @Test
    fun `a new group is saved and shows this device alone`() =
        runTest {
            val c = controller()
            c.load()
            assertFalse(c.state.value.inGroup)
            c.createGroup()
            val ui = c.state.value
            assertTrue(ui.inGroup)
            assertEquals(1, ui.members.size)
            assertTrue(ui.members.single().isSelf)
            val saved = assertNotNull(store.read().group)
            assertEquals(saved.selfId, ui.selfId)
        }

    @Test
    fun `an invitation parses back and the answer opens the link`() =
        runTest {
            val c = controller()
            c.load()
            c.invite()
            val invitation = assertNotNull(c.state.value.invitation)
            val sent = assertNotNull(transport?.invitation).payload
            assertEquals(sent, PairingCodec.parse(invitation.qrText))
            assertEquals(sent, PairingCodec.parse(invitation.link))
            assertEquals("Phone", sent.name)

            val g = assertNotNull(store.read().group)
            val answer = payload(PairingKind.ANSWER, g.groupId, null, PEER_ID, "Bob", BASE_MS / 1000)
            c.handleCode(PairingCodec.toQrText(answer))
            assertEquals(answer, transport?.invitation?.accepted)
            assertTrue(c.state.value.pairing)
            assertNull(c.state.value.invitation)

            connectRemote(c)
            val ui = c.state.value
            assertFalse(ui.pairing)
            assertEquals("Bob", ui.justJoined)
            assertEquals(listOf("Bob"), ui.others.map { it.name })
            assertEquals(MemberStatus.DIRECT, ui.others.single().status)
        }

    @Test
    fun `the phone settings that cut the sharing are read on demand and every 5 s in a group`() =
        runTest {
            var phone = listOf(ShareAlert.POWER_SAVER)
            val c = controller(phone = { phone })
            c.load()
            assertEquals(listOf(ShareAlert.POWER_SAVER), c.recheckPhone())
            assertEquals(listOf(ShareAlert.POWER_SAVER), c.state.value.alerts)
            c.createGroup()
            phone = listOf(ShareAlert.LOCATION_OFF, ShareAlert.BATTERY_OPTIMIZED)
            advanceTimeBy(6_000)
            assertEquals(listOf(ShareAlert.LOCATION_OFF, ShareAlert.BATTERY_OPTIMIZED), c.state.value.alerts)
            phone = emptyList()
            advanceTimeBy(6_000)
            assertEquals(emptyList(), c.state.value.alerts)
        }

    @Test
    fun `an invitation that cannot be made says there is no network`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            transport!!.inviteFails = true
            c.invite()
            assertEquals(ShareProblem.NO_NETWORK, c.state.value.problem)
            assertFalse(c.state.value.preparing)
        }

    @Test
    fun `a scanned invitation joins the group and shows an answer`() =
        runTest {
            val c = controller()
            c.load()
            val groupId = ByteArray(16) { GROUP_FILL.toByte() }
            val key = ByteArray(32) { (it + 1).toByte() }
            val offer = payload(PairingKind.OFFER, groupId, key, PEER_ID, "Bob", BASE_MS / 1000)
            c.handleCode(PairingCodec.toLink(offer))
            val saved = assertNotNull(store.read().group)
            assertContentEquals(groupId, saved.groupId)
            assertContentEquals(key, saved.key)
            assertNotEquals(0L, saved.selfId)
            assertEquals(offer, transport?.joined)
            val ui = c.state.value
            assertTrue(ui.inGroup)
            assertTrue(ui.pairing)
            val shown = PairingCodec.parse(assertNotNull(ui.answer).qrText)
            assertEquals(PairingKind.ANSWER, shown.kind)
            assertEquals(saved.selfId, shown.memberId)
        }

    @Test
    fun `a first pairing that fails leaves no group behind`() =
        runTest {
            failJoins = true
            val c = controller()
            c.load()
            val offer = payload(PairingKind.OFFER, ByteArray(16), ByteArray(32), PEER_ID, "Bob", BASE_MS / 1000)
            c.handleCode(PairingCodec.toQrText(offer))
            val ui = c.state.value
            assertEquals(ShareProblem.NO_NETWORK, ui.problem)
            assertFalse(ui.inGroup)
            assertNull(ui.answer)
            assertNull(store.read().group)
            assertTrue(transport!!.closed)
        }

    @Test
    fun `codes that are damaged, old, from the future or out of place are refused`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val g = assertNotNull(store.read().group)
            val nowSec = BASE_MS / 1000

            c.handleCode("not a code at all")
            assertEquals(ShareProblem.BAD_CODE, c.state.value.problem)

            c.clearProblem()
            c.handleCode(
                PairingCodec.toQrText(payload(PairingKind.OFFER, g.groupId, g.key, PEER_ID, "B", nowSec - 700)),
            )
            assertEquals(ShareProblem.EXPIRED, c.state.value.problem)

            c.handleCode(
                PairingCodec.toQrText(payload(PairingKind.OFFER, g.groupId, g.key, PEER_ID, "B", nowSec + 700)),
            )
            assertEquals(ShareProblem.CLOCK_OFF, c.state.value.problem)

            c.handleCode(PairingCodec.toQrText(payload(PairingKind.ANSWER, g.groupId, null, PEER_ID, "B", nowSec)))
            assertEquals(ShareProblem.NOT_EXPECTED, c.state.value.problem)

            val other = ByteArray(16) { 99 }
            c.handleCode(PairingCodec.toQrText(payload(PairingKind.OFFER, other, ByteArray(32), PEER_ID, "B", nowSec)))
            assertEquals(ShareProblem.OTHER_GROUP, c.state.value.problem)
            assertContentEquals(g.groupId, store.read().group!!.groupId)
        }

    @Test
    fun `an invitation that nobody answers expires by itself`() =
        runTest {
            val c = controller()
            c.load()
            c.invite()
            assertNotNull(c.state.value.invitation)
            advanceTimeBy(601_000)
            runCurrent()
            assertNull(c.state.value.invitation)
        }

    @Test
    fun `positions follow the interval, the distance and the keep-alive`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            c.setSharing(true)
            runCurrent()
            val t0 = BASE_MS / 1000
            assertTrue(c.state.value.sharing)

            gps.flow.tryEmit(fix(t0))
            runCurrent()
            assertEquals(1, remote.positions.size)
            assertEquals(80, remote.positions.single().battery)
            assertEquals(1500, remote.positions.single().altM)

            // Five seconds later, same place: too early.
            gps.flow.tryEmit(fix(t0 + 5))
            runCurrent()
            assertEquals(1, remote.positions.size)

            // After the interval (30 s) but not moved (under 10 m): not sent.
            gps.flow.tryEmit(fix(t0 + 31, lat = 45.00002))
            runCurrent()
            assertEquals(1, remote.positions.size)

            // After the interval and moved about 55 m: sent.
            gps.flow.tryEmit(fix(t0 + 62, lat = 45.0005))
            runCurrent()
            assertEquals(2, remote.positions.size)

            // Standing still for five intervals: sent anyway so the others know the device is alive.
            gps.flow.tryEmit(fix(t0 + 62 + 150, lat = 45.0005))
            runCurrent()
            assertEquals(3, remote.positions.size)
        }

    @Test
    fun `stopping the sharing stops the GPS and a failing GPS is reported`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            c.setSharing(true)
            runCurrent()
            c.setSharing(false)
            runCurrent()
            assertFalse(c.state.value.sharing)
            assertEquals(0, gps.flow.subscriptionCount.value)

            gps.failing = true
            c.setSharing(true)
            runCurrent()
            assertFalse(c.state.value.sharing)
            assertTrue(c.state.value.gpsUnavailable)
        }

    @Test
    fun `a new interval asks the GPS again without the sharing going off`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            c.setSharing(true)
            runCurrent()
            // The sharing service stops as soon as it sees "off" (live test of 2026-10-24): not even for an instant.
            val seen = ArrayList<Boolean>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.state.collect { seen += it.sharing } }
            c.updateSettings { it.copy(intervalSec = 10) }
            runCurrent()
            assertEquals(listOf(true), seen.distinct())
            assertEquals(listOf(30_000L, 10_000L), gps.intervals)
            assertEquals(1, gps.flow.subscriptionCount.value)

            c.updateSettings { it.copy(minMoveMeters = 0) }
            runCurrent()
            assertEquals(2, gps.intervals.size)
        }

    @Test
    fun `texts go both ways`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            c.sendText("  On est au col  ")
            runCurrent()
            assertEquals(listOf("On est au col"), remote.texts)
            assertTrue(c.state.value.messages.single().mine)

            remote.node.sendText("Ok, j'arrive")
            runCurrent()
            val last = c.state.value.messages.last()
            assertFalse(last.mine)
            assertEquals("Bob", last.name)
            assertEquals("Ok, j'arrive", last.text)
            // Only the others' messages are counted unread, until their page is open (P29.8).
            assertEquals(1, c.state.value.unread)
            c.markMessagesRead()
            assertEquals(0, c.state.value.unread)
        }

    @Test
    fun `an SOS raises an alert that stays until it is acknowledged and goes when it stops repeating`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            remote.node.startSos(PeerFix(45.1, 6.1, timeSec = BASE_MS / 1000), "Cheville")
            runCurrent()
            val alert = c.state.value.sos.single()
            assertEquals("Bob", alert.name)
            assertEquals("Cheville", alert.text)
            assertFalse(alert.acknowledged)
            assertEquals(45.1, alert.fix!!.lat, 1e-6)

            c.acknowledgeSos(PEER_ID)
            assertTrue(c.state.value.sos.single().acknowledged)

            // The sender stops: nothing repeats and the alert disappears after two minutes.
            remote.node.stopSos()
            advanceTimeBy(125_000)
            runCurrent()
            assertTrue(c.state.value.sos.isEmpty())
        }

    @Test
    fun `an SOS of this device is sent with the last known position`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            c.setSharing(true)
            runCurrent()
            gps.flow.tryEmit(fix(BASE_MS / 1000, lat = 45.5, lon = 6.5))
            runCurrent()
            c.startSos("Perdu")
            runCurrent()
            assertTrue(c.state.value.ownSos)
            val (text, where) = remote.sosTexts.single()
            assertEquals("Perdu", text)
            assertEquals(45.5, where!!.lat, 1e-6)

            // It repeats until stopped.
            advanceTimeBy(16_000)
            runCurrent()
            assertEquals(2, remote.sosTexts.size)
            c.stopSos()
            assertFalse(c.state.value.ownSos)
            advanceTimeBy(40_000)
            runCurrent()
            assertEquals(2, remote.sosTexts.size)
        }

    @Test
    fun `leaving closes the transport and forgets the group`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            c.leaveGroup()
            runCurrent()
            assertFalse(c.state.value.inGroup)
            assertTrue(transport!!.closed)
            assertNull(store.read().group)
            assertEquals(MemberStatus.LEFT, remote.node.members().first { !it.isSelf }.status)
        }

    @Test
    fun `being removed by another member forgets the group and says so`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val remote = connectRemote(c)
            val selfId = c.state.value.selfId
            remote.node.remove(selfId)
            runCurrent()
            val ui = c.state.value
            assertTrue(ui.excluded)
            assertFalse(ui.inGroup)
            assertNull(store.read().group)
            c.clearExcluded()
            assertFalse(c.state.value.excluded)
        }

    @Test
    fun `removing a member changes the key that is saved`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            val before = store.read().group!!.key.copyOf()
            val remote = connectRemote(c)
            c.removeMember(remote.id)
            runCurrent()
            val after = store.read().group!!.key
            assertFalse(before.contentEquals(after))
            assertTrue(remote.kicked, "the removed member is told on its own link")
        }

    @Test
    fun `settings are kept and the group comes back after a restart`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            c.updateSettings { it.copy(name = "Alice", intervalSec = 60, colorIndex = 4) }
            val selfId = c.state.value.selfId
            assertEquals("Alice", c.state.value.self?.name)

            val again = controller()
            again.load()
            val ui = again.state.value
            assertTrue(ui.inGroup)
            assertEquals(selfId, ui.selfId)
            assertEquals("Alice", ui.settings.name)
            assertEquals(60, ui.settings.intervalSec)
            assertEquals("Alice", ui.self?.name)
        }

    @Test
    fun `a blank name falls back to the device name and settings are kept within their limits`() =
        runTest {
            val c = controller()
            c.load()
            c.createGroup()
            assertEquals("Phone", c.state.value.self?.name)
            c.updateSettings { it.copy(intervalSec = 1, minMoveMeters = 100_000) }
            assertEquals(5, c.state.value.settings.intervalSec)
            assertEquals(1000, c.state.value.settings.minMoveMeters)
        }
}
