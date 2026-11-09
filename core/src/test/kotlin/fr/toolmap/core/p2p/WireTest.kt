package fr.toolmap.core.p2p

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        // No sender lists more than 32 members: a longer list, sealed with the key, is refused.
        val many = ByteWriter().u8(33)
        repeat(33) { many.u64(it + 100L).str("x").i32(0).u32(1) }
        val aad = byteArrayOf(MsgType.PEERS.code.toByte())
        val forged = aad + byteArrayOf(1) + crypto.seal(many.toByteArray(), aad)
        assertFailsWith<WireException> { Wire.decode(forged, crypto) }
        assertEquals(32, (roundTrip(Msg.Peers(List(40) { PeerInfo(it + 1L, "p", 0, 1) })).msg as Msg.Peers).peers.size)

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

        val end = roundTrip(Msg.SosEnd(3, 8)).msg as Msg.SosEnd
        assertEquals(3, end.memberId)
        assertEquals(8, end.seq)

        val listening = roundTrip(Msg.State(3, 9, sharing = false)).msg as Msg.State
        assertEquals(3, listening.memberId)
        assertEquals(9, listening.seq)
        assertEquals(false, listening.sharing)
        assertEquals(true, (roundTrip(Msg.State(3, 10, sharing = true)).msg as Msg.State).sharing)

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
    fun `a point of the group round trips with every value, fits the server slot and has a type of its own`() {
        assertEquals(16, MsgType.POINT.code)
        val point =
            GroupPoint(
                id = -0x1234_5678_9ABC_DEFL,
                author = 0x7777_0000_1111L,
                rev = 3,
                lat = -21.1234567,
                lon = 55.7654321,
                altM = -12,
                name = "Rendez-vous télésiège ⛷",
                symbol = "ski-lift",
                createdSec = 1_790_000_000,
                updatedSec = 1_790_000_600,
                expiresSec = 1_790_007_200,
                alert = true,
                removedBy = 0x2222L,
            )
        val frame = Wire.encode(Msg.Point(9, 0xFFFF_FFFFL, point, replay = true), crypto)
        assertEquals(MsgType.POINT, Wire.typeOf(frame))
        val back = Wire.decode(frame, crypto).msg as Msg.Point
        assertEquals(9, back.memberId)
        assertEquals(0xFFFF_FFFFL, back.seq)
        assertTrue(back.replay)
        assertEquals(point, back.point)
        assertTrue(back.point.removed)
        assertEquals(0x2222L, back.point.writer)
        // A point without altitude nor end, not removed, sent live.
        val plain = GroupPoint(1, 2, 1, 45.0, 6.0, name = "Parking", createdSec = 1_790_000_000)
        val live = roundTrip(Msg.Point(2, 1, plain)).msg as Msg.Point
        assertFalse(live.replay)
        assertEquals(plain, live.point)
        assertNull(live.point.altM)
        assertEquals(GroupPoint.WHILE_GROUP, live.point.endSec)
        // The longest name and symbol are cut, and the frame stays well inside the slot of the server (800 characters
        // of Base64, `firebase/database.rules.json`).
        val long = plain.copy(name = "é".repeat(100), symbol = "s".repeat(100))
        val longFrame = Wire.encode(Msg.Point(2, 1, long), crypto)
        val cut = (Wire.decode(longFrame, crypto).msg as Msg.Point).point
        assertEquals(GroupPoint.MAX_NAME_BYTES / 2, cut.name.length)
        assertEquals(GroupPoint.MAX_SYMBOL_BYTES, cut.symbol.length)
        assertTrue((longFrame.size + 2) / 3 * 4 <= 800)
        assertFailsWith<WireException> { roundTrip(Msg.Point(2, 1, plain.copy(lat = 95.0))) }
    }

    @Test
    fun `a later revision or the removal of the same one replaces a point, another author never does`() {
        val p = GroupPoint(1, 2, 1, 45.0, 6.0, name = "A", createdSec = 100)
        assertTrue(p.copy(rev = 2).replaces(p))
        assertFalse(p.replaces(p))
        assertFalse(p.copy(rev = 2).replaces(p.copy(rev = 3)))
        assertTrue(p.copy(removedBy = 5).replaces(p))
        assertFalse(p.replaces(p.copy(removedBy = 5)))
        assertFalse(p.copy(rev = 2, author = 9).replaces(p))
        // Ends: its expiry, a day after the removal of a point without end, never for a point on without end.
        assertTrue(p.copy(expiresSec = 200).isOver(200))
        assertFalse(p.copy(expiresSec = 200).isOver(199))
        assertFalse(p.isOver(Long.MAX_VALUE / 2))
        val removed = p.copy(updatedSec = 150, removedBy = 5)
        assertEquals(150 + GroupPoint.TOMBSTONE_KEEP_S, removed.endSec)
    }

    @Test
    fun `the lift of an alert has a type of its own after the last one older versions know`() {
        // Versions up to 0.4.113 know the types 1 to 13: they drop a frame of type 14 as unknown (MeshNodeTest).
        assertEquals(14, MsgType.SOS_END.code)
        assertEquals(15, MsgType.STATE.code)
        assertEquals(MsgType.SOS_END, MsgType.of(14))
        assertEquals(8, MsgType.SOS.code)
        val frame = Wire.encode(Msg.SosEnd(0x1234_5678_9ABCL, 0xFFFF_FFFFL), crypto)
        assertEquals(MsgType.SOS_END, Wire.typeOf(frame))
        val back = Wire.decode(frame, crypto).msg as Msg.SosEnd
        assertEquals(0x1234_5678_9ABCL, back.memberId)
        assertEquals(0xFFFF_FFFFL, back.seq)
        // A lift and an alert are not interchangeable: the type is authenticated.
        assertFailsWith<WireException> {
            Wire.decode(frame.copyOf().also { it[0] = MsgType.SOS.code.toByte() }, crypto)
        }
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
        assertTrue(!Msg.SosEnd(1, 1).onPositionChannel)
        assertTrue(!Msg.State(1, 1, true).onPositionChannel)
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
    fun `an interval under one second is refused, a typed one from one second is kept`() {
        assertFailsWith<IllegalArgumentException> { ShareCadence(0, 0.0) }
        assertEquals(1, ShareCadence(1, 0.0).intervalSec)
        assertEquals(1, ShareState.fromJson("{\"settings\":{\"intervalSec\":1}}").settings.intervalSec)
        // Typed values between the presets (P23.3) are kept as they are.
        val typed = ShareState.fromJson("{\"settings\":{\"intervalSec\":45,\"minMoveMeters\":7}}").settings
        assertEquals(45 to 7, typed.intervalSec to typed.minMoveMeters)
        assertEquals(10_000, ShareState.fromJson("{\"settings\":{\"minMoveMeters\":20000}}").settings.minMoveMeters)
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
