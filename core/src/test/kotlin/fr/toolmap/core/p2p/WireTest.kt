package fr.toolmap.core.p2p

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireTest {
    private val key = GroupCrypto.randomKey()
    private val crypto = GroupCrypto(key)
    private val fix =
        PeerFix(
            45.123456789,
            5.987654321,
            altM = 1234,
            accuracyM = 8,
            speedMs = 1.37,
            bearingDeg = 271.4,
            timeSec = 1_790_000_123L,
            battery = 64,
        )

    private fun roundTrip(msg: Msg): Frame = Wire.decode(Wire.encode(msg, crypto), crypto)

    @Test
    fun `a position keeps its values to the precision of the wire`() {
        val back = (roundTrip(Msg.Pos(7, 42, fix)).msg as Msg.Pos)
        assertEquals(7, back.memberId)
        assertEquals(42, back.seq)
        assertEquals(45.1234568, back.fix.lat, 1e-7)
        assertEquals(5.9876543, back.fix.lon, 1e-7)
        assertEquals(1234, back.fix.altM)
        assertEquals(8, back.fix.accuracyM)
        assertEquals(1.37, back.fix.speedMs!!, 0.006)
        assertEquals(271.4, back.fix.bearingDeg!!, 0.06)
        assertEquals(1_790_000_123L, back.fix.timeSec)
        assertEquals(64, back.fix.battery)
    }

    @Test
    fun `unknown values stay unknown, negative altitudes and the west and south keep their sign`() {
        val bare = (roundTrip(Msg.Pos(1, 1, PeerFix(-33.8, -70.6, timeSec = 5))).msg as Msg.Pos).fix
        assertEquals(-33.8, bare.lat, 1e-7)
        assertEquals(-70.6, bare.lon, 1e-7)
        assertNull(bare.altM)
        assertNull(bare.accuracyM)
        assertNull(bare.speedMs)
        assertNull(bare.bearingDeg)
        assertNull(bare.battery)
        assertEquals(
            -420,
            (roundTrip(Msg.Pos(1, 1, PeerFix(0.0, 0.0, altM = -420, timeSec = 5))).msg as Msg.Pos).fix.altM,
        )
        // A bearing of 359.96° rounds to 360.0 and must not be written as an impossible value.
        assertEquals(
            0.0,
            (
                roundTrip(
                    Msg.Pos(1, 1, PeerFix(0.0, 0.0, bearingDeg = 359.96, timeSec = 5)),
                ).msg as Msg.Pos
            ).fix.bearingDeg!!,
            0.01,
        )
    }

    @Test
    fun `a position frame is about fifty bytes`() {
        val size = Wire.encode(Msg.Pos(7, 42, fix), crypto).size
        assertTrue(size in 50..70, "$size bytes")
        assertEquals(21, PeerFix.BYTES)
    }

    @Test
    fun `every kind of message round trips`() {
        val hello = roundTrip(Msg.Hello(9, "Camille", 4)).msg as Msg.Hello
        assertEquals("Camille", hello.name)
        assertEquals(4, hello.color)
        assertEquals(Msg.PROTOCOL, hello.protocol)

        val peers = roundTrip(Msg.Peers(listOf(PeerInfo(1, "A", 0, 12), PeerInfo(2, "Éva", 5, 3600)))).msg as Msg.Peers
        assertEquals(listOf(1L, 2L), peers.peers.map { it.memberId })
        assertEquals("Éva", peers.peers[1].name)
        assertEquals(3600, peers.peers[1].lastSeenAgoSec)

        // The whole packed look travels: icon and RGB colour, not just a palette byte.
        val look = MemberLook.pack(3, 0xFF12AB34.toInt())
        assertEquals(look, (roundTrip(Msg.Hello(9, "C", look)).msg as Msg.Hello).color)
        assertEquals(look, (roundTrip(Msg.Peers(listOf(PeerInfo(1, "A", look, 1)))).msg as Msg.Peers).peers[0].color)
        val picture = ByteArray(3000) { (it * 7).toByte() }
        val avatar = roundTrip(Msg.Avatar(5, 99, picture)).msg as Msg.Avatar
        assertEquals(5, avatar.memberId)
        assertEquals(99, avatar.seq)
        assertContentEquals(picture, avatar.image)
        assertEquals(0, (roundTrip(Msg.Avatar(5, 100, ByteArray(0))).msg as Msg.Avatar).image.size)
        // A picture over the limit is not sent as is.
        val big = ByteArray(MemberLook.MAX_PICTURE_BYTES + 1)
        assertEquals(0, (roundTrip(Msg.Avatar(5, 101, big)).msg as Msg.Avatar).image.size)
        val legacy = 0xFFD32F2F.toInt()
        assertEquals(legacy, (roundTrip(Msg.Hello(9, "C", legacy)).msg as Msg.Hello).color)

        val payload = ByteArray(300) { it.toByte() }
        val signal = roundTrip(Msg.Signal(77, 1, 2, payload)).msg as Msg.Signal
        assertEquals(77, signal.seq)
        assertEquals(1, signal.from)
        assertEquals(2, signal.to)
        assertContentEquals(payload, signal.payload)

        val trail = roundTrip(Msg.Trail(3, listOf(fix, fix))).msg as Msg.Trail
        assertEquals(2, trail.fixes.size)

        val ping = roundTrip(Msg.Ping(3, 1_790_000_000)).msg as Msg.Ping
        assertEquals(1_790_000_000, ping.timeSec)

        val text = roundTrip(Msg.Text(3, 5, "On se retrouve au refuge à 14 h ☀")).msg as Msg.Text
        assertEquals("On se retrouve au refuge à 14 h ☀", text.text)

        val sos = roundTrip(Msg.Sos(3, 6, fix, "Cheville cassée")).msg as Msg.Sos
        assertEquals("Cheville cassée", sos.text)
        assertNotNull(sos.fix)
        assertNull((roundTrip(Msg.Sos(3, 7, null, "")).msg as Msg.Sos).fix)

        val newKey = GroupCrypto.randomKey()
        val rekey = roundTrip(Msg.Rekey(1, 9, 5, newKey)).msg as Msg.Rekey
        assertEquals(5, rekey.excluded)
        assertContentEquals(newKey, rekey.key)

        assertEquals(8, (roundTrip(Msg.Bye(8)).msg as Msg.Bye).memberId)
        assertEquals(6, (roundTrip(Msg.Kick(6)).msg as Msg.Kick).target)
    }

    @Test
    fun `a diagnostic payload round trips and its largest frame fits the server limit`() {
        val body = ByteArray(Msg.Diag.MAX_BYTES) { (it * 31).toByte() }
        val diag = roundTrip(Msg.Diag(0x1234_5678_9ABCL, 77, body)).msg as Msg.Diag
        assertEquals(0x1234_5678_9ABCL, diag.memberId)
        assertEquals(77, diag.seq)
        assertContentEquals(body, diag.body)
        val frame = Wire.encode(Msg.Diag(1, 1, body), crypto)
        assertEquals(MsgType.DIAG, Wire.typeOf(frame))
        assertEquals(8, Wire.frameId(frame).length)
        // firebase/database.rules.json: a frame of the reliable channel is at most 6000 Base64 characters.
        assertTrue(java.util.Base64.getEncoder().encodeToString(frame).length <= 6000)
        assertFailsWith<IllegalArgumentException> {
            Wire.encode(Msg.Diag(1, 2, ByteArray(Msg.Diag.MAX_BYTES + 1)), crypto)
        }
        assertTrue(!Msg.Diag(1, 1, body).onPositionChannel)
    }

    @Test
    fun `texts are cut at 140 characters and names at 24 bytes`() {
        val long = "x".repeat(300)
        assertEquals(140, ((roundTrip(Msg.Text(1, 1, long)).msg) as Msg.Text).text.length)
        val name = (roundTrip(Msg.Hello(1, "é".repeat(40), 0)).msg as Msg.Hello).name
        assertTrue(name.toByteArray().size <= 24)
    }

    @Test
    fun `positions and pings go on the unordered channel, the rest on the reliable one`() {
        assertTrue(Msg.Pos(1, 1, fix).onPositionChannel)
        assertTrue(Msg.Ping(1, 1).onPositionChannel)
        assertTrue(!Msg.Text(1, 1, "").onPositionChannel)
        assertTrue(!Msg.Sos(1, 1, null, "").onPositionChannel)
        assertTrue(!Msg.Signal(1, 1, 2, ByteArray(0)).onPositionChannel)
    }

    @Test
    fun `a relay lowers the time to live without touching the ciphertext and stops at the last hop`() {
        val frame = Wire.encode(Msg.Text(1, 1, "hi"), crypto, ttl = 3)
        val once = Wire.relayed(frame)!!
        val twice = Wire.relayed(once)!!
        assertEquals(3, Wire.decode(frame, crypto).ttl)
        assertEquals(2, Wire.decode(once, crypto).ttl)
        assertEquals(1, Wire.decode(twice, crypto).ttl)
        assertNull(Wire.relayed(twice))
        assertContentEquals(frame.copyOfRange(2, frame.size), twice.copyOfRange(2, twice.size))
        assertEquals(4, Wire.DEFAULT_TTL)
    }

    @Test
    fun `a frame of another group, with another type or damaged is refused`() {
        val frame = Wire.encode(Msg.Text(1, 1, "hi"), crypto)
        assertFailsWith<WireException> { Wire.decode(frame, GroupCrypto(GroupCrypto.randomKey())) }
        assertFailsWith<WireException> {
            Wire.decode(frame.copyOf().also { it[0] = MsgType.SOS.code.toByte() }, crypto)
        }
        assertFailsWith<WireException> { Wire.decode(frame.copyOf().also { it[0] = 99 }, crypto) }
        assertFailsWith<WireException> {
            Wire.decode(
                frame.copyOf().also {
                    it[frame.size - 1] =
                        (it[frame.size - 1] + 1).toByte()
                },
                crypto,
            )
        }
        assertFailsWith<WireException> { Wire.decode(ByteArray(1), crypto) }
        assertFailsWith<WireException> { Wire.decode(ByteArray(20_000), crypto) }
    }

    @Test
    fun `a position outside the globe is refused`() {
        val w = ByteWriter().u64(1).u32(1)
        w.i32(Math.round(95.0 * 1e7).toInt()).i32(0).i16(0).u16(0).u16(0).u16(0).u32(0).u8(0)
        val sealed = crypto.seal(w.toByteArray(), byteArrayOf(MsgType.POS.code.toByte()))
        val frame = byteArrayOf(MsgType.POS.code.toByte(), 4) + sealed
        assertFailsWith<WireException> { Wire.decode(frame, crypto) }
    }
}

class ShareCadenceTest {
    private fun fix(
        t: Long,
        lat: Double = 45.0,
        lon: Double = 6.0,
    ) = PeerFix(lat, lon, timeSec = t)

    @Test
    fun `the first fix is always sent`() {
        assertTrue(ShareCadence(30, 10.0).shouldSend(null, fix(0)))
    }

    @Test
    fun `nothing goes before the interval, a fix up to a second early counts`() {
        val c = ShareCadence(30, 0.0)
        val last = SentPosition(100, 45.0, 6.0)
        assertTrue(!c.shouldSend(last, fix(110, 45.01)))
        assertTrue(!c.shouldSend(last, fix(128, 45.01)))
        assertTrue(c.shouldSend(last, fix(129, 45.01)))
        assertTrue(c.shouldSend(last, fix(131, 45.01)))
    }

    @Test
    fun `a device that has not moved by the threshold stays quiet until the keep alive`() {
        val c = ShareCadence(30, 10.0)
        val last = SentPosition(0, 45.0, 6.0)
        assertTrue(!c.shouldSend(last, fix(30, 45.00003)))
        assertTrue(c.shouldSend(last, fix(30, 45.0002)))
        assertTrue(!c.shouldSend(last, fix(149, 45.00003)))
        assertTrue(c.shouldSend(last, fix(150, 45.00003)))
    }

    @Test
    fun `a fix older than the last one sent, after the clock went back, is sent`() {
        val c = ShareCadence(30, 10.0)
        val last = SentPosition(1000, 45.0, 6.0)
        assertTrue(c.shouldSend(last, fix(185, 45.0)))
    }

    @Test
    fun `an interval under five seconds is refused`() {
        assertFailsWith<IllegalArgumentException> { ShareCadence(4, 0.0) }
        assertEquals(5, ShareCadence.PRESETS_S.first())
        assertEquals(1800, ShareCadence.PRESETS_S.last())
    }

    @Test
    fun `initials come from the first two words`() {
        assertEquals("JD", MemberPalette.initials("Jean Dupont"))
        assertEquals("CA", MemberPalette.initials("camille"))
        assertEquals("?", MemberPalette.initials("   "))
        assertEquals(MemberPalette.colors[1], MemberPalette.argb(13))
    }
}
