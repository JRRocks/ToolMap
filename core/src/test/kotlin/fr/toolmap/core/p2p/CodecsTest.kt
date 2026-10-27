package fr.toolmap.core.p2p

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

internal fun samplePayload(
    kind: PairingKind = PairingKind.OFFER,
    createdAtSec: Long = 1_790_000_000L,
    name: String = "Jean",
    candidates: List<IceCandidate> =
        listOf(
            IceCandidate(CandidateType.HOST, ip(192, 168, 1, 23), 51_234, 0),
            IceCandidate(CandidateType.SRFLX, ip(203, 0, 113, 7), 40_001, 1),
            IceCandidate(CandidateType.HOST, ip(10, 0, 0, 5), 52_000, 2),
        ),
    memberId: Long = 0x1122334455667788L,
) = PairingPayload(
    kind = kind,
    createdAtSec = createdAtSec,
    groupId = ByteArray(16) { (it + 1).toByte() },
    groupKey = if (kind == PairingKind.OFFER) ByteArray(32) { (100 + it).toByte() } else null,
    memberId = memberId,
    name = name,
    color = 3,
    iceUfrag = "aB3d",
    icePwd = "x7Yq0pLm9Rt2VnB4cD6eF8gH",
    dtlsFingerprint = ByteArray(32) { (it * 7).toByte() },
    dtlsSetup = if (kind == PairingKind.ANSWER) DtlsSetup.ACTIVE else DtlsSetup.ACTPASS,
    candidates = candidates,
)

class Base45Test {
    @Test
    fun `the vectors of RFC 9285`() {
        assertEquals("BB8", Base45.encode("AB".toByteArray()))
        assertEquals("%69 VD92EX0", Base45.encode("Hello!!".toByteArray()))
        assertEquals("UJCLQE7W581", Base45.encode("base-45".toByteArray()))
        assertEquals("QED8WEX0", Base45.encode("ietf!".toByteArray()))
        assertEquals("ietf!", String(Base45.decode("QED8WEX0")))
        assertEquals("Hello!!", String(Base45.decode("%69 VD92EX0")))
    }

    @Test
    fun `any bytes come back, whatever the length`() {
        val random = java.util.Random(7)
        for (n in 0..40) {
            val data = ByteArray(n).also(random::nextBytes)
            assertContentEquals(data, Base45.decode(Base45.encode(data)), "length $n")
        }
    }

    @Test
    fun `text that is not base45 is refused without a raw exception`() {
        assertFailsWith<WireException> { Base45.decode("abc") }
        assertFailsWith<WireException> { Base45.decode("A") }
        assertFailsWith<WireException> { Base45.decode("GGW") } // 16 + 16·45 + 16·2025 > 65535
        assertFailsWith<WireException> { Base45.decode(":::") }
    }
}

class PairingCodecTest {
    @Test
    fun `the three kinds survive the binary form`() {
        for (kind in PairingKind.entries) {
            val p = samplePayload(kind)
            assertEquals(p, PairingCodec.decodeBytes(PairingCodec.encodeBytes(p)))
        }
    }

    @Test
    fun `only the invitation carries the key`() {
        val offer = PairingCodec.encodeBytes(samplePayload(PairingKind.OFFER))
        val answer = PairingCodec.encodeBytes(samplePayload(PairingKind.ANSWER))
        assertTrue(offer.size >= answer.size + 32 - 8, "offer ${offer.size} B, answer ${answer.size} B")
        assertFailsWith<IllegalArgumentException> {
            PairingPayload(
                PairingKind.ANSWER,
                0,
                ByteArray(16),
                ByteArray(32),
                1,
                "x",
                0,
                "u",
                "p",
                ByteArray(32),
                DtlsSetup.ACTIVE,
                emptyList(),
            )
        }
    }

    @Test
    fun `an IPv6 candidate and a long name round trip, the name cut at 24 bytes on a character boundary`() {
        val v6 = IceCandidate(CandidateType.HOST, ByteArray(16) { (0x20 + it).toByte() }, 9, 0)
        val p = samplePayload(name = "Élodie-Anne de la Croix-Rousse", candidates = listOf(v6))
        val back = PairingCodec.decodeBytes(PairingCodec.encodeBytes(p))
        assertEquals(listOf(v6), back.candidates)
        assertTrue(back.name.toByteArray().size <= 24)
        assertTrue(back.name.startsWith("Élodie-Anne"))
    }

    @Test
    fun `text and link read back the same payload, the link surviving a messenger`() {
        val p = samplePayload()
        assertEquals(p, PairingCodec.parse(PairingCodec.toQrText(p)))
        val link = PairingCodec.toLink(p)
        assertTrue(link.startsWith("toolmap://pair?d="))
        assertTrue(link.all { it.isLetterOrDigit() || it in ":/?=-_." }, link)
        assertEquals(p, PairingCodec.parse("  $link\n"))
        assertEquals(p, PairingCodec.parse(link + "&utm=1"))
    }

    @Test
    fun `the QR text uses only the alphanumeric mode and fits version 15 at level M`() {
        val p =
            samplePayload(
                candidates =
                    (0 until 5).map {
                        IceCandidate(
                            CandidateType.HOST,
                            ip(192, 168, 1, 20 + it),
                            50_000 + it,
                            it,
                        )
                    },
            )
        val text = PairingCodec.toQrText(p)
        assertTrue(text.all { it in Base45.ALPHABET })
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
            )
        val version = (matrix.width - 17) / 4
        assertTrue(version <= 15, "QR version $version for ${text.length} characters")
        assertTrue(PairingCodec.encodeBytes(p).size in 100..330, "${PairingCodec.encodeBytes(p).size} bytes")
    }

    @Test
    fun `a code older than ten minutes or from the future is refused`() {
        val p = samplePayload(createdAtSec = 1_000)
        assertNull(PairingCodec.staleness(p, 1_000))
        assertNull(PairingCodec.staleness(p, 1_600))
        assertEquals(PairingCodec.Staleness.EXPIRED, PairingCodec.staleness(p, 1_601))
        assertEquals(PairingCodec.Staleness.FROM_THE_FUTURE, PairingCodec.staleness(p, 399))
    }

    @Test
    fun `damaged or foreign codes are refused cleanly`() {
        val good = PairingCodec.encodeBytes(samplePayload())
        assertFailsWith<WireException> { PairingCodec.decodeBytes(ByteArray(0)) }
        assertFailsWith<WireException> { PairingCodec.decodeBytes(good.copyOf(good.size / 2)) }
        assertFailsWith<WireException> { PairingCodec.decodeBytes(good.copyOf().also { it[0] = 0 }) }
        assertFailsWith<WireException> { PairingCodec.decodeBytes(good.copyOf().also { it[2] = 9 }) }
        assertFailsWith<WireException> { PairingCodec.decodeBytes(good + 1) }
        assertFailsWith<WireException> { PairingCodec.parse("https://example.org") }
        assertFailsWith<WireException> { PairingCodec.parse("toolmap://pair?d=@@@") }
        // Random garbage never throws anything but a WireException.
        val random = java.util.Random(3)
        repeat(300) {
            val noise = ByteArray(random.nextInt(120)).also(random::nextBytes)
            if (noise.size > 3) {
                noise[0] = 0x54
                noise[1] = 0x4D
            }
            try {
                PairingCodec.decodeBytes(noise)
            } catch (e: WireException) {
                // expected
            } catch (e: IllegalArgumentException) {
                // a payload that parses but breaks its own invariants (more than 8 candidates is caught earlier)
            }
        }
    }

    @Test
    fun `a bomb of repeated bytes is not inflated without limit`() {
        val d = java.util.zip.Deflater(9, true)
        d.setInput(ByteArray(2_000_000))
        d.finish()
        val buf = ByteArray(10_000)
        val n = d.deflate(buf)
        d.end()
        val bomb = byteArrayOf(0x54, 0x4D, (1 or 0x80).toByte()) + buf.copyOf(n)
        assertFailsWith<WireException> { PairingCodec.decodeBytes(bomb) }
    }

    @Test
    fun `pruning keeps IPv4 first, host before reflexive, and renumbers the ranks`() {
        val v6 = IceCandidate(CandidateType.HOST, ByteArray(16) { 1 }, 1, 0)
        val reflexive = IceCandidate(CandidateType.SRFLX, ip(1, 2, 3, 4), 2, 0)
        val host = IceCandidate(CandidateType.HOST, ip(10, 0, 0, 1), 3, 0)
        val extras = (0 until 8).map { IceCandidate(CandidateType.HOST, ip(10, 0, 1, it), 4, 0) }
        val pruned = PairingCodec.prune(listOf(v6, reflexive, host) + extras, limit = 4)
        assertEquals(4, pruned.size)
        assertEquals(listOf(0, 1, 2, 3), pruned.map { it.rank })
        assertEquals(host.port, pruned[0].port)
        assertTrue(pruned.none { it.isV6 })
        assertEquals(
            3,
            PairingCodec.prune(
                listOf(
                    host,
                    host,
                    host.let {
                        IceCandidate(it.type, it.ip, it.port, 9)
                    },
                    v6,
                    reflexive,
                ),
                5,
            ).size,
        )
    }
}

class SdpTest {
    @Test
    fun `an SDP built from a payload reads back to the same fields`() {
        val p = samplePayload(PairingKind.ANSWER)
        val sdp = Sdp.build(p)
        assertTrue(sdp.contains("m=application 9 UDP/DTLS/SCTP webrtc-datachannel"))
        assertTrue(sdp.contains("a=setup:active"))
        assertTrue(sdp.contains("a=sctp-port:5000"))
        assertTrue(sdp.endsWith("\r\n"))
        val info = Sdp.parse(sdp)
        assertEquals(p.iceUfrag, info.iceUfrag)
        assertEquals(p.icePwd, info.icePwd)
        assertContentEquals(p.dtlsFingerprint, info.dtlsFingerprint)
        assertEquals(p.dtlsSetup, info.dtlsSetup)
        // The best candidates come first: hosts before the server reflexive one.
        assertEquals(
            listOf(
                Triple(CandidateType.HOST, "192.168.1.23", 51_234),
                Triple(CandidateType.HOST, "10.0.0.5", 52_000),
                Triple(CandidateType.SRFLX, "203.0.113.7", 40_001),
            ),
            info.candidates.map { Triple(it.type, it.address, it.port) },
        )
    }

    @Test
    fun `priorities rebuilt from the rank keep the order of the candidates`() {
        val lines = samplePayload().candidates.mapIndexed { i, c -> Sdp.candidateLine(c, i) }
        val priorities = lines.map { it.split(' ')[3].toLong() }
        assertTrue(priorities[0] > priorities[2], "two hosts: the better rank first")
        assertTrue(priorities[0] > priorities[1], "host before server reflexive")
        assertTrue(lines[1].contains("typ srflx raddr 0.0.0.0 rport 0"))
    }

    @Test
    fun `the SDP of a real data channel offer gives only what we can use`() {
        val sdp =
            """
            v=0
            o=- 1234567890 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            a=extmap-allow-mixed
            a=msid-semantic: WMS
            m=application 9 UDP/DTLS/SCTP webrtc-datachannel
            c=IN IP4 0.0.0.0
            a=candidate:1 1 udp 2122260223 192.168.43.1 40000 typ host generation 0 network-id 1
            a=candidate:2 1 udp 2122194687 10.20.0.5 40001 typ host generation 0 network-id 2
            a=candidate:3 1 tcp 1518280447 192.168.43.1 9 typ host tcptype active generation 0
            a=candidate:4 1 udp 2122129151 fe80::1 40002 typ host generation 0
            a=candidate:5 1 udp 1686052607 90.12.34.56 40003 typ srflx raddr 192.168.43.1 rport 40000 generation 0
            a=candidate:6 1 udp 41885439 51.0.0.1 3478 typ relay raddr 90.12.34.56 rport 40003 generation 0
            a=candidate:7 1 udp 2122260223 5d7f2a9c-1b3e-4c8d-9f10-aabbccddeeff.local 40004 typ host generation 0
            a=candidate:8 1 udp 2122262783 2a01:cb00:1234::5 40005 typ host generation 0
            a=ice-ufrag:Zq1r
            a=ice-pwd:0123456789abcdefghijklmn
            a=ice-options:trickle
            a=fingerprint:sha-256 ${(0 until 32).joinToString(":") { "%02X".format(it * 3) }}
            a=setup:actpass
            a=mid:0
            a=sctp-port:5000
            a=max-message-size:262144
            """.trimIndent().replace("\n", "\r\n")
        val info = Sdp.parse(sdp)
        assertEquals("Zq1r", info.iceUfrag)
        assertEquals(DtlsSetup.ACTPASS, info.dtlsSetup)
        assertEquals(32, info.dtlsFingerprint.size)
        assertEquals(0, info.dtlsFingerprint[0].toInt())
        assertEquals(3, info.dtlsFingerprint[1].toInt())
        val found = info.candidates.map { it.address to it.type }
        assertEquals(
            listOf(
                "192.168.43.1" to CandidateType.HOST,
                "10.20.0.5" to CandidateType.HOST,
                "90.12.34.56" to CandidateType.SRFLX,
                "2a01:cb00:1234:0:0:0:0:5" to CandidateType.HOST,
            ),
            found,
        )
        assertEquals(listOf(0, 1, 2, 3), info.candidates.map { it.rank })
    }

    @Test
    fun `an SDP missing a mandatory field is refused`() {
        assertFailsWith<WireException> { Sdp.parse("v=0\r\na=ice-ufrag:x\r\n") }
        assertFailsWith<WireException> { Sdp.parse("") }
        assertNull(Sdp.parseCandidate("a=candidate:1 1 udp 1 not-an-ip 5 typ host"))
        assertNotNull(Sdp.parseCandidate("candidate:1 1 UDP 1 1.2.3.4 5 typ host"))
        assertFalse(Sdp.parseCandidate("a=candidate:1 1 udp 1 0.0.0.0 5 typ host") != null)
    }
}

class GroupCryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `HKDF-SHA256 gives the vector 1 of RFC 5869`() {
        val okm =
            GroupCrypto.hkdfSha256(
                ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                salt = hex("000102030405060708090a0b0c"),
                info = hex("f0f1f2f3f4f5f6f7f8f9"),
                length = 42,
            )
        assertContentEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test
    fun `HKDF with no salt gives the vector 3 of RFC 5869`() {
        val okm =
            GroupCrypto.hkdfSha256(
                ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
                salt = ByteArray(0),
                info = ByteArray(0),
                length = 42,
            )
        assertContentEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm,
        )
    }

    @Test
    fun `sealed bytes open with the same key and aad only`() {
        val key = GroupCrypto.randomKey()
        val a = GroupCrypto(key)
        val b = GroupCrypto(key)
        val sealed = a.seal("position".toByteArray(), byteArrayOf(4))
        assertEquals(12 + 8 + 16, sealed.size)
        assertEquals("position", String(b.open(sealed, byteArrayOf(4))))
        assertFailsWith<WireException> { b.open(sealed, byteArrayOf(5)) }
        assertFailsWith<WireException> {
            b.open(sealed.copyOf().also { it[20] = (it[20] + 1).toByte() }, byteArrayOf(4))
        }
        assertFailsWith<WireException> { GroupCrypto(GroupCrypto.randomKey()).open(sealed, byteArrayOf(4)) }
        assertFailsWith<WireException> { b.open(ByteArray(10), byteArrayOf(4)) }
    }

    @Test
    fun `two seals of the same text differ and a re-key keeps the previous key for what is in flight`() {
        val old = GroupCrypto.randomKey()
        val crypto = GroupCrypto(old, SecureRandom())
        val one = crypto.seal(ByteArray(4), byteArrayOf(1))
        assertFalse(one.contentEquals(crypto.seal(ByteArray(4), byteArrayOf(1))))
        val replacement = GroupCrypto.randomKey()
        crypto.rekey(replacement)
        crypto.open(one, byteArrayOf(1))
        val other = GroupCrypto(replacement)
        assertContentEquals(ByteArray(4), other.open(crypto.seal(ByteArray(4), byteArrayOf(1)), byteArrayOf(1)))
        crypto.rekey(GroupCrypto.randomKey())
        assertFailsWith<WireException> { crypto.open(one, byteArrayOf(1)) }
    }
}
