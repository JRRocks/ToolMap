package fr.toolmap.core.p2p

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayTest {
    private val server =
        RelayConfig.of(
            "https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app/",
            " fake-key ",
        )

    private fun invite(
        name: String = "Jean",
        at: Long = 1_800_000_000L,
    ) = RoomInvite(at, ByteArray(16) { it.toByte() }, ByteArray(32) { (it * 3).toByte() }, 0x1234ABCDL, name, server)

    @Test
    fun `server config is cleaned and validated`() {
        assertEquals("https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app", server.base)
        assertTrue(server.isUsable)
        assertEquals(RelayConfig.Problem.BAD_URL, RelayConfig.problem(RelayConfig("http://insecure.example", "k")))
        assertEquals(RelayConfig.Problem.BAD_URL, RelayConfig.problem(RelayConfig("https://a.b/x?y=1", "k")))
        assertEquals(RelayConfig.Problem.BAD_KEY, RelayConfig.problem(RelayConfig("https://a.firebaseio.com", "")))
        assertEquals(
            RelayConfig.Problem.BAD_KEY,
            RelayConfig.problem(RelayConfig("https://a.firebaseio.com", "has space")),
        )
    }

    @Test
    fun `an invitation survives the QR text and the link`() {
        val i = invite()
        for (text in listOf(
            RoomInviteCodec.toQrText(i),
            RoomInviteCodec.toLink(i),
            "  " + RoomInviteCodec.toLink(i) + "\n",
        )) {
            val back = assertNotNull(RoomInviteCodec.parseOrNull(text))
            assertTrue(back.groupId.contentEquals(i.groupId))
            assertTrue(back.groupKey.contentEquals(i.groupKey))
            assertEquals(i.inviterId, back.inviterId)
            assertEquals("Jean", back.inviterName)
            assertEquals(server, back.server)
            assertEquals(i.createdAtSec, back.createdAtSec)
        }
    }

    @Test
    fun `an invitation fits a QR code of a reasonable size`() {
        assertTrue(RoomInviteCodec.encodeBytes(invite()).size < 260)
    }

    @Test
    fun `other codes are not invitations`() {
        assertNull(RoomInviteCodec.parseOrNull("toolmap://pair?d=AAAA"))
        assertNull(RoomInviteCodec.parseOrNull("hello"))
        assertNull(RoomInviteCodec.parseOrNull(""))
    }

    @Test
    fun `a damaged invitation is refused`() {
        val bytes = RoomInviteCodec.encodeBytes(invite())
        assertFailsWith<WireException> { RoomInviteCodec.decodeBytes(bytes.copyOf(bytes.size - 5)) }
        assertFailsWith<WireException> { RoomInviteCodec.decodeBytes(bytes + 1) }
    }

    @Test
    fun `an invitation expires after a week`() {
        val i = invite(at = 1_000_000L)
        assertNull(RoomInviteCodec.staleness(i, 1_000_000L + 3600))
        assertEquals(PairingCodec.Staleness.EXPIRED, RoomInviteCodec.staleness(i, 1_000_000L + 8 * 86400))
        assertEquals(PairingCodec.Staleness.FROM_THE_FUTURE, RoomInviteCodec.staleness(i, 1_000_000L - 8 * 86400))
    }

    @Test
    fun `member keys round trip, including ids with the high bit set and all-digit ids`() {
        for (id in listOf(1L, -1L, 0x1234567890123456L, Long.MIN_VALUE, 0x7FFFFFFFFFFFFFFFL)) {
            val key = RelayPaths.member(id)
            assertTrue(key.startsWith("m"))
            assertEquals(id, RelayPaths.memberOf(key))
        }
        assertNull(RelayPaths.memberOf("1234"))
        assertNull(RelayPaths.memberOf("mzz"))
        assertNull(RelayPaths.memberOf("m"))
        assertEquals("00010203", RelayPaths.room(byteArrayOf(0, 1, 2, 3)))
    }

    private fun view(
        status: MemberStatus,
        lastHeardMs: Long = 0,
        fix: PeerFix? = null,
        self: Boolean = false,
    ) = MemberView(1, "x", 0, status, self, lastHeardMs, fix, emptyList())

    private val fix = PeerFix(1.0, 2.0, timeSec = 1)

    @Test
    fun `peer states follow the link, the silence and the group`() {
        val now = 10_000_000L
        assertEquals(PeerState.ONLINE, PeerState.of(view(MemberStatus.DIRECT, now, fix), now))
        assertEquals(PeerState.ONLINE, PeerState.of(view(MemberStatus.RELAYED, now, fix), now))
        assertEquals(PeerState.PAIRED, PeerState.of(view(MemberStatus.LOST), now))
        assertEquals(PeerState.LOST_SYNC, PeerState.of(view(MemberStatus.LOST, now - 60_000, fix), now))
        assertEquals(PeerState.OFFLINE, PeerState.of(view(MemberStatus.LOST, now - 11 * 60_000, fix), now))
        assertEquals(PeerState.UNPAIRED, PeerState.of(view(MemberStatus.LEFT, now, fix), now))
    }

    @Test
    fun `without the server this device can only vouch for nobody`() {
        val now = 1L
        assertEquals(PeerState.OFFLINE, PeerState.of(view(MemberStatus.DIRECT, self = true), now, serverUp = false))
        assertEquals(PeerState.ONLINE, PeerState.of(view(MemberStatus.DIRECT, self = true), now, serverUp = true))
        assertEquals(PeerState.LOST_SYNC, PeerState.of(view(MemberStatus.DIRECT, 1, fix), now, serverUp = false))
        assertEquals(PeerState.UNPAIRED, PeerState.of(view(MemberStatus.LEFT), now, serverUp = false))
        assertFalse(PeerState.entries.isEmpty())
    }
}
