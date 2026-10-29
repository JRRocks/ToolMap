import fr.toolmap.core.eventlog.Event
import fr.toolmap.core.eventlog.EventLine
import fr.toolmap.core.p2p.Base45
import fr.toolmap.core.p2p.CandidateType
import fr.toolmap.core.p2p.ColorWheel
import fr.toolmap.core.p2p.DtlsSetup
import fr.toolmap.core.p2p.GroupCrypto
import fr.toolmap.core.p2p.GroupRecord
import fr.toolmap.core.p2p.IceCandidate
import fr.toolmap.core.p2p.MemberLook
import fr.toolmap.core.p2p.MemberPalette
import fr.toolmap.core.p2p.MemberStatus
import fr.toolmap.core.p2p.MemberView
import fr.toolmap.core.p2p.Msg
import fr.toolmap.core.p2p.PairingCodec
import fr.toolmap.core.p2p.PairingKind
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.PeerInfo
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RoomInvite
import fr.toolmap.core.p2p.RoomInviteCodec
import fr.toolmap.core.p2p.Sdp
import fr.toolmap.core.p2p.ShareGeoJson
import fr.toolmap.core.p2p.ShareSettings
import fr.toolmap.core.p2p.ShareState
import fr.toolmap.core.p2p.Wire
import java.io.File
import java.security.SecureRandom
import java.time.ZoneId
import java.time.ZoneOffset

/** Nonces of the vectors: call k fills byte j with (k * 31 + j * 7 + 1) mod 256. The Swift tests use the same. */
class FixedRandom : SecureRandom() {
    private var call = 0

    override fun nextBytes(bytes: ByteArray) {
        for (j in bytes.indices) bytes[j] = ((call * 31 + j * 7 + 1) and 0xFF).toByte()
        call++
    }
}

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

private fun swift(s: String): String {
    val sb = StringBuilder("\"")
    for (c in s.codePoints().toArray()) {
        when {
            c == '"'.code -> sb.append("\\\"")
            c == '\\'.code -> sb.append("\\\\")
            c == '\n'.code -> sb.append("\\n")
            c == '\r'.code -> sb.append("\\r")
            c == '\t'.code -> sb.append("\\t")
            c in 0xD800..0xDFFF -> sb.append("\\u{fffd}")
            c < 0x20 || c > 0x7E -> sb.append("\\u{").append(Integer.toHexString(c)).append('}')
            else -> sb.appendCodePoint(c)
        }
    }
    return sb.append('"').toString()
}

private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

fun samplePayload(
    kind: PairingKind,
    name: String = "Jean",
    candidates: List<IceCandidate> =
        listOf(
            IceCandidate(CandidateType.HOST, ip(192, 168, 1, 23), 51_234, 0),
            IceCandidate(CandidateType.SRFLX, ip(203, 0, 113, 7), 40_001, 1),
            IceCandidate(CandidateType.HOST, ip(10, 0, 0, 5), 52_000, 2),
        ),
) = PairingPayload(
    kind = kind,
    createdAtSec = 1_790_000_000L,
    groupId = ByteArray(16) { (it + 1).toByte() },
    groupKey = if (kind == PairingKind.OFFER) ByteArray(32) { (100 + it).toByte() } else null,
    memberId = 0x1122334455667788L,
    name = name,
    color = 3,
    iceUfrag = "aB3d",
    icePwd = "x7Yq0pLm9Rt2VnB4cD6eF8gH",
    dtlsFingerprint = ByteArray(32) { (it * 7).toByte() },
    dtlsSetup = if (kind == PairingKind.ANSWER) DtlsSetup.ACTIVE else DtlsSetup.ACTPASS,
    candidates = candidates,
)

fun main(args: Array<String>) {
    val out = StringBuilder()
    fun line(s: String = "") = out.append(s).append('\n')

    val groupKey = ByteArray(32) { it.toByte() }
    val crypto = GroupCrypto(groupKey, FixedRandom())
    val fix =
        PeerFix(45.123456789, 5.987654321, altM = 1234, accuracyM = 8, speedMs = 1.37, bearingDeg = 271.4,
            timeSec = 1_790_000_123L, battery = 64)
    val bare = PeerFix(-33.8, -70.6, timeSec = 5)
    val edges =
        PeerFix(0.00000005, -0.00000005, altM = -420, accuracyM = 70_000, speedMs = 0.005, bearingDeg = 359.96,
            timeSec = 0, battery = 150)
    val clamps =
        PeerFix(-90.0, 180.0, altM = 40_000, accuracyM = -3, speedMs = 655.36, bearingDeg = -90.05,
            timeSec = 0xFFFF_FFFFL, battery = -1)
    val low = PeerFix(12.3456789, -0.000000149999, altM = -40_000, speedMs = -1.0, bearingDeg = 720.04, timeSec = 7)
    val answer = samplePayload(PairingKind.ANSWER)
    val frames =
        listOf<Pair<String, ByteArray>>(
            "hello" to Wire.encode(Msg.Hello(0x1122334455667788L, "Jean", MemberLook.pack(3, 0xFF12AB34.toInt())), crypto),
            "helloLong" to Wire.encode(Msg.Hello(-2, "Élodie-Anne de la Croix-Rousse", 0xFFD32F2F.toInt()), crypto),
            "peers" to
                Wire.encode(
                    Msg.Peers(listOf(PeerInfo(1, "A", 0, 12), PeerInfo(Long.MIN_VALUE, "Éva", -1, 3600))),
                    crypto,
                ),
            "pos" to Wire.encode(Msg.Pos(7, 42, fix), crypto),
            "posBare" to Wire.encode(Msg.Pos(-1, 0xFFFF_FFFFL, bare), crypto),
            "posEdges" to Wire.encode(Msg.Pos(3, 1, edges), crypto),
            "posClamps" to Wire.encode(Msg.Pos(3, 2, clamps), crypto),
            "posLow" to Wire.encode(Msg.Pos(3, 3, low), crypto),
            "trail" to Wire.encode(Msg.Trail(3, listOf(fix, bare, edges)), crypto),
            "ping" to Wire.encode(Msg.Ping(3, 1_790_000_000), crypto),
            "text" to Wire.encode(Msg.Text(3, 5, "On se retrouve au refuge à 14 h ☀"), crypto),
            "textLong" to Wire.encode(Msg.Text(4, 6, "x".repeat(300)), crypto),
            "textSplitPair" to Wire.encode(Msg.Text(4, 7, "a" + "😀".repeat(80)), crypto),
            "sos" to Wire.encode(Msg.Sos(3, 6, fix, "Cheville cassée"), crypto),
            "sosBare" to Wire.encode(Msg.Sos(3, 7, null, ""), crypto),
            "sosEnd" to Wire.encode(Msg.SosEnd(0x1234_5678_9ABCL, 0xFFFF_FFFFL), crypto),
            "stateOff" to Wire.encode(Msg.State(3, 9, false), crypto),
            "stateOn" to Wire.encode(Msg.State(3, 10, true), crypto),
            "rekey" to Wire.encode(Msg.Rekey(1, 9, 5, ByteArray(32) { (200 + it).toByte() }), crypto),
            "bye" to Wire.encode(Msg.Bye(8), crypto),
            "kick" to Wire.encode(Msg.Kick(6), crypto),
            "avatar" to Wire.encode(Msg.Avatar(5, 99, ByteArray(300) { (it * 7).toByte() }), crypto),
            "avatarEmpty" to Wire.encode(Msg.Avatar(5, 100, ByteArray(0)), crypto),
            "avatarTooBig" to Wire.encode(Msg.Avatar(5, 101, ByteArray(MemberLook.MAX_PICTURE_BYTES + 1)), crypto),
            "diag" to Wire.encode(Msg.Diag(0x1234_5678_9ABCL, 77, ByteArray(100) { (it * 31).toByte() }), crypto),
            "signal" to Wire.encode(Msg.Signal(77, 1, 2, PairingCodec.encodeBytes(answer)), crypto),
            "ttl3" to Wire.encode(Msg.Text(1, 1, "hi"), crypto, ttl = 3),
            "ttl300" to Wire.encode(Msg.Text(1, 2, "hi"), crypto, ttl = 300),
        )
    // After a re-key: sealed with the new key, which the old crypto keeps opening too.
    crypto.rekey(ByteArray(32) { (it + 50).toByte() })
    val rekeyed = Wire.encode(Msg.Text(1, 3, "nouvelle clé"), crypto)

    line("// Generated by the Kotlin code of :core (scratch build p2p-vectors, Vectors.kt); do not edit by hand.")
    line("// Each value is what the Android app produces for the inputs named in P2PVectorsTests.swift.")
    line()
    line("enum P2PVectors {")
    line("    static let groupKey = ${swift(hex(groupKey))}")
    line(
        "    static let derivedKey = ${swift(hex(GroupCrypto.hkdfSha256(groupKey, ByteArray(0), "toolmap-p2p-v1".toByteArray(), 32)))}",
    )
    line("    static let frames: [(String, String)] = [")
    for ((name, f) in frames) line("        (${swift(name)}, ${swift(hex(f))}),")
    line("    ]")
    line("    static let rekeyedText = ${swift(hex(rekeyed))}")
    line("    static let frameIds: [String] = [${frames.take(3).joinToString { swift(Wire.frameId(it.second)) }}]")

    // Pairing codes: the binary form, deflated by the JVM, and what is inside.
    line("    static let pairing: [(String, String, String, String, String)] = [")
    val v6 = IceCandidate(CandidateType.HOST, ByteArray(16) { (0x20 + it).toByte() }, 9, 0)
    val payloads =
        listOf(
            "offer" to samplePayload(PairingKind.OFFER),
            "answer" to answer,
            "mesh" to samplePayload(PairingKind.MESH_OFFER),
            "v6" to samplePayload(PairingKind.OFFER, name = "Élodie-Anne de la Croix-Rousse", candidates = listOf(v6)),
            "none" to samplePayload(PairingKind.ANSWER, candidates = emptyList()),
            "packed" to
                PairingPayload(
                    PairingKind.MESH_OFFER, 1_790_000_000L, ByteArray(16), null, 7L, "a".repeat(24), 0,
                    "u".repeat(60), "p".repeat(60), ByteArray(32), DtlsSetup.PASSIVE,
                    (0 until 8).map { IceCandidate(CandidateType.HOST, ip(10, 0, 0, 1), 50_000, it) },
                ),
        )
    for ((name, p) in payloads) {
        val bytes = PairingCodec.encodeBytes(p)
        val body =
            if (bytes[2].toInt() and 0x80 != 0) {
                val inf = java.util.zip.Inflater(true)
                inf.setInput(bytes.copyOfRange(3, bytes.size) + 0)
                val buf = ByteArray(4096)
                val n = inf.inflate(buf)
                inf.end()
                buf.copyOf(n)
            } else {
                bytes.copyOfRange(3, bytes.size)
            }
        line(
            "        (${swift(name)}, ${swift(hex(bytes))}, ${swift(hex(body))}, ${swift(PairingCodec.toQrText(p))}, " +
                "${swift(PairingCodec.toLink(p))}),",
        )
    }
    line("    ]")
    val sample = "ToolMap ".repeat(50).toByteArray()
    val d = java.util.zip.Deflater(9, true)
    d.setInput(sample)
    d.finish()
    val dbuf = ByteArray(1024)
    val dn = d.deflate(dbuf)
    d.end()
    line("    /// A raw DEFLATE stream of the JVM (Deflater level 9, nowrap) and its text.")
    line("    static let deflatedSample = ${swift(hex(dbuf.copyOf(dn)))}")
    line("    static let deflatedText = ${swift(String(sample))}")
    line("    static let sdpAnswer = ${swift(Sdp.build(answer))}")
    line("    static let sdpV6 = ${swift(Sdp.build(payloads[3].second, sessionId = 42))}")

    // Server invitations: no compression, so the Swift code must give the very same text.
    val server = RelayConfig.of("https://toolmap-1234-default-rtdb.europe-west1.firebasedatabase.app/", " fake-key ")
    val invite =
        RoomInvite(1_800_000_000L, ByteArray(16) { it.toByte() }, ByteArray(32) { (it * 3).toByte() }, 0x1234ABCDL,
            "Jean", server)
    val invite2 =
        RoomInvite(1_800_000_001L, ByteArray(16) { (255 - it).toByte() }, ByteArray(32) { (it * 5 + 1).toByte() },
            -0x1234ABCDL, "Élodie-Anne de la Croix-Rousse", server)
    line("    static let invites: [(String, String, String)] = [")
    for (i in listOf(invite, invite2)) {
        line(
            "        (${swift(hex(RoomInviteCodec.encodeBytes(i)))}, ${swift(RoomInviteCodec.toQrText(i))}, " +
                "${swift(RoomInviteCodec.toLink(i))}),",
        )
    }
    line("    ]")
    line("    static let memberKeys: [(Int64, String)] = [")
    for (id in listOf(1L, -1L, 0x1234567890123456L, Long.MIN_VALUE, Long.MAX_VALUE, 0L, 255L)) {
        line("        (${if (id == Long.MIN_VALUE) "Int64.min" else id.toString()}, ${swift(RelayPaths.member(id))}),")
    }
    line("    ]")

    // The saved state: the Swift code writes the same JSON text and reads what the Android app wrote.
    val group = GroupRecord(ByteArray(16) { (it * 9).toByte() }, ByteArray(32) { (it * 11).toByte() }, -0x5EADBEEFL,
        RelayConfig.of("https://a.firebaseio.com", "key-1"))
    val states =
        listOf(
            ShareState(),
            ShareState(ShareSettings("Jean", 3, 60, 25, stun = false), group),
            ShareState(
                ShareSettings(name = "Jo \"le\" \\ Fou\n\u0001\b\u000c", icon = 4, color = 0xFF123456.toInt(), showTrails = false,
                    server = RelayConfig.of("https://b.firebaseio.com/", "k")),
                GroupRecord(ByteArray(16), ByteArray(32), Long.MIN_VALUE),
            ),
            ShareState(ShareSettings(icon = 3).withPicture(byteArrayOf(9, 8, 7, 6))),
        )
    line("    static let states: [String] = [")
    for (s in states) line("        ${swift(s.toJson())},")
    line("    ]")
    val inputs =
        listOf(
            """{"settings":{"name":"${"x".repeat(60)}","colorIndex":-1,"intervalSec":1,"minMoveMeters":-5}}""",
            """{"settings":{"icon":99,"color":0}}""",
            """{"settings":{"icon":127,"picture":"AQID"}}""",
            """{"settings":{"icon":127,"picture":"@@"}}""",
            """{"settings":{"intervalSec":"60"}}""",
            """{"settings":{"intervalSec":1e2}}""",
            """{"settings":{"colorIndex":1.0}}""",
            """{"settings":{"colorIndex":3000000000}}""",
            """{"settings":{"name":5}}""",
            """{"settings":{"name":null}}""",
            """{"settings":null}""",
            """{"group":null,"settings":{"stun":false}}""",
            """{"settings":{"stun":"true"}}""",
            """{"settings":{"stun":1}}""",
            """{"settings":{"color":null,"picture":null,"server":null}}""",
            """{"settings":{"server":{"dbUrl":"http://x.y","apiKey":"k"}}}""",
            """{"settings":{"server":{"dbUrl":"https://x.y/"}}}""",
            """{"settings":{"server":{"dbUrl":" https://x.y/ ","apiKey":" k "}}}""",
            """{"group":{"id":"${"0F".repeat(16)}","key":"${"aB".repeat(32)}","self":"ffffffffffffffff"}}""",
            """{"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"+1"}}""",
            """{"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"10000000000000000"}}""",
            """{"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"1","server":{"dbUrl":"ftp://x","apiKey":"k"}}}""",
            """{"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"1","server":{"dbUrl":"https://x.y","apiKey":"k"}}}""",
            """{"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}"}}""",
            """{"group":{"id":"${"-1".repeat(16)}","key":"${"00".repeat(32)}","self":"1"}}""",
            """{"settings":{"name":"a"},"settings":{"name":"b"}}""",
            """{"settings":{"name":"é😀"}} """,
            """ {"settings":{}} x""",
            """{"settings":{"name":"a",}}""",
            """{'settings':{}}""",
            """{"settings":{"minMoveMeters":-0}}""",
            """{"settings":{"name":"${"é".repeat(30)}"}}""",
            """{"settings":{"name":"z","stun":"false"}}""",
            """{"settings":{"name":"z","stun":0}}""",
            """{"settings":{"name":"z","stun":FALSE}}""",
            """{"settings":{"name":"z","showTrails":"False"}}""",
            """{"settings":{"name":"z","showTrails":fals}}""",
            """{"settings":{"name":"z","intervalSec":1.5e1}}""",
            """{"settings":{"name":"z","intervalSec":15e-1}}""",
            """{"settings":{"name":"z","intervalSec":150e-1}}""",
            """{"settings":{"name":"z","intervalSec":1E+2}}""",
            """{"settings":{"name":"z","intervalSec":"+5"}}""",
            """{"settings":{"name":"z","intervalSec":05}}""",
            """{"settings":{"name":"z","intervalSec":" 5"}}""",
            """{"settings":{"name":"z","intervalSec":-}}""",
            """{"settings":{"name":"z","intervalSec":1e}}""",
            """{"settings":{"name":"z","intervalSec":1e2x}}""",
            """{"settings":{"name":"z","intervalSec":-0060}}""",
            """{"settings":{"name":"z","color":"-16777216"}}""",
            """{"settings":{"name":"z","color":4294967295}}""",
            """{"settings":{"name":"z","icon":true}}""",
            """{"settings":{"name":"z","colorIndex":2147483648}}""",
            """{"settings":{"name":"z","colorIndex":-2147483648}}""",
            """{"settings":{"name":"q"}} x""",
            "{\"settings\":{\"name\":\"\\u00e9\\ud83d\\ude00\\n\\/\\t\\b\\f\\u0001\"}}",
            "{\"settings\":{\"name\":\"a\tb\"}}",
            """{"settings":{"name":"z","server":{"dbUrl":"https://x.y","apiKey":"k","extra":1}}}""",
            """{"settings":{"name":"z"},"group":{"id":"","key":"","self":"1"}}""",
            """{"settings":{"name":"z","picture":"AQID","icon":3}}""",
            """{"settings":{"name":"z","picture":"AQID=","icon":127}}""",
            """{"settings":{"name":"z","picture":"","icon":127}}""",
            """{"settings":{"name":"z","other":[1,{"a":null},"x"]}}""",
            """{"settings":{"name":"z"},"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":1}}""",
            """{"settings":{"name":"z"},"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"-1"}}""",
            """{"settings":{"name":"z"},"group":{"id":"${"0".repeat(31)}","key":"${"00".repeat(32)}","self":"1"}}""",
            """{"settings":{"name":"z","intervalSec":null}}""",
            """{"settings":{"name":"z"},"group":{"id":"${"00".repeat(16)}","key":"${"00".repeat(32)}","self":"1","server":null}}""",
            """{"settings":{"name":"z"}}""" + "\n",
            """[{"settings":{}}]""",
            """{"settings":{"name":"z","server":{"dbUrl":"https://x.y","apiKey":"has space"}}}""",
            """{"settings":{"name":"  a  b "}}""",
        )
    line("    /// Documents read by the Android app, and the state it keeps from each (written back).")
    line("    static let readStates: [(String, String)] = [")
    for (i in inputs) line("        (${swift(i)}, ${swift(ShareState.fromJson(i).toJson())}),")
    line("    ]")

    // Event log lines.
    val zones = listOf(ZoneOffset.UTC, ZoneId.of("Europe/Paris"), ZoneId.of("America/St_Johns"))
    val events =
        listOf(
            Event(1_790_000_000_123L, "relay.write", listOf("ch" to "p", "ms" to 85, "why" to "socket closed",
                "lat" to 45.123456789, "n" to null)),
            Event(1_700_000_000_000L, "test.values", listOf("a" to 0.1 + 0.2, "b" to 1.0, "c" to 100.0,
                "d" to 1e-8, "e" to 1.1f, "f" to -0.0, "g" to 1e12, "h" to Double.NaN, "i" to -12.50000004,
                "j" to 123456.7, "k" to 0.00000015, "l" to Double.NEGATIVE_INFINITY, "m" to 99_999_999_999.99,
                "n" to 5e-8, "o" to -5e-8, "p" to 2.5e-7)),
            Event(-1L, "test.text", listOf("t" to "a \"b\" c\\d=e\nf\rg", "e" to "", "u" to "é☀", "l" to Long.MIN_VALUE,
                "b" to true)),
            Event(1_711_846_800_000L, "dst.start", emptyList()),
        )
    line("    static let eventLines: [String] = [")
    for (z in zones) for ((i, e) in events.withIndex()) line("        ${swift(EventLine.format(i + 1L, e, z))},")
    line("    ]")

    // Map features.
    val now = 1_790_000_000_000L
    fun member(id: Long, name: String, color: Int, status: MemberStatus, fix: PeerFix?, trail: List<PeerFix>,
        picture: ByteArray? = null, sharing: Boolean? = null, self: Boolean = false) =
        MemberView(id, name, color, status, self, now, fix, trail, picture, sharing)
    val f1 = PeerFix(45.1234567, 6.7654321, timeSec = now / 1000)
    val f2 = PeerFix(-12.00000005, -0.5, timeSec = now / 1000 - 700)
    val members =
        listOf(
            member(1, "Moi", 0, MemberStatus.DIRECT, f1, listOf(f1, f2), self = true),
            member(2, "Alice \"A\"", MemberLook.pack(2, 0xFFAA0000.toInt()), MemberStatus.DIRECT, f1, listOf(f2, f1)),
            member(Long.MAX_VALUE - 5, "Bob", MemberLook.pack(MemberLook.PICTURE, 0xFF00FF00.toInt()),
                MemberStatus.LOST, f2, listOf(f1), picture = byteArrayOf(1, 2, 3, -4)),
            member(-7, "Zoé Martin", 0xFFD32F2F.toInt(), MemberStatus.RELAYED, f2, listOf(f1, f2, f1), sharing = false),
            member(9, "Écoute", 0, MemberStatus.LEFT, f1, listOf(f1, f2), sharing = false),
        )
    line("    static let geoMembers = ${swift(ShareGeoJson.members(members, setOf(-7L), now))}")
    line("    static let geoTrails = ${swift(ShareGeoJson.trails(members))}")
    line("    static let imageKeys: [String] = [")
    for (pic in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(3000) { (it * 13).toByte() })) {
        line("        ${swift(MemberLook.imageKey(MemberLook.pack(MemberLook.PICTURE, 0x123456), "x", pic))},")
    }
    line("    ]")
    line("    static let initials: [(String, String)] = [")
    for (n in listOf("Jean Dupont", "camille", "   ", "élodie anne", "ß x", "😀 smile", "a\tb\u000bc", "Ⅷ")) {
        line("        (${swift(n)}, ${swift(MemberPalette.initials(n))}),")
    }
    line("    ]")
    line("    static let hsv: [(Int32, Float, Float, Float, Int32)] = [")
    for (c in listOf(0xFFD32F2F.toInt(), 0xFF1976D2.toInt(), 0xFF123456.toInt(), 0xFF808080.toInt(), 0xFFFFEB3B.toInt(), 0xFF00838F.toInt())) {
        val h = ColorWheel.toHsv(c)
        line("        (Int32(bitPattern: 0x${Integer.toHexString(c)}), Float(bitPattern: 0x${Integer.toHexString(h[0].toRawBits())}), " +
            "Float(bitPattern: 0x${Integer.toHexString(h[1].toRawBits())}), Float(bitPattern: 0x${Integer.toHexString(h[2].toRawBits())}), " +
            "Int32(bitPattern: 0x${Integer.toHexString(ColorWheel.fromHsv(h[0] + 17.3f, h[1] * 0.7f, h[2]))})),")
    }
    line("    ]")
    line("    static let base45: [(String, String)] = [")
    for (s in listOf("", "A", "AB", "Hello!!", "ÿþ\u0000")) {
        val b = s.toByteArray(Charsets.ISO_8859_1)
        line("        (${swift(hex(b))}, ${swift(Base45.encode(b))}),")
    }
    line("    ]")
    line("}")
    File(args.getOrElse(0) { "P2PVectors.swift" }).writeText(out.toString())

    // The QR capacity checked in the Swift tests in place of ZXing (CodecsTest).
    val writer = com.google.zxing.qrcode.QRCodeWriter()
    for (n in listOf(600, 601)) {
        val m =
            writer.encode("A".repeat(n), com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
                    com.google.zxing.EncodeHintType.MARGIN to 0))
        println("QR $n alphanumeric characters at level M: version ${(m.width - 17) / 4}")
    }
    // Lengths of the QR texts of the CodecsTest case (five host candidates), for the record.
    val p5 = samplePayload(PairingKind.OFFER, candidates = (0 until 5).map {
        IceCandidate(CandidateType.HOST, ip(192, 168, 1, 20 + it), 50_000 + it, it)
    })
    println("QR text of five candidates: ${PairingCodec.toQrText(p5).length} characters, ${PairingCodec.encodeBytes(p5).size} bytes")
}
