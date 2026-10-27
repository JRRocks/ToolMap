package fr.toolmap.core.p2p

/** A position as it travels: about 34 bytes. Unknown optional values are `null`. */
class PeerFix(
    val lat: Double,
    val lon: Double,
    val altM: Int? = null,
    val accuracyM: Int? = null,
    val speedMs: Double? = null,
    val bearingDeg: Double? = null,
    /** Epoch seconds of the measure. */
    val timeSec: Long,
    /** Battery of the sender, 0..100. */
    val battery: Int? = null,
) {
    /** The values the wire keeps: rounded to 1e-7°, 1 m, 1 cm/s and 0.1°. */
    internal fun write(w: ByteWriter) {
        w.i32(Math.round(lat * COORD_SCALE).toInt()).i32(Math.round(lon * COORD_SCALE).toInt())
        w.i16(altM?.coerceIn(Short.MIN_VALUE + 1, Short.MAX_VALUE.toInt()) ?: ALT_UNKNOWN)
        w.u16(accuracyM?.coerceIn(0, UNKNOWN16 - 1) ?: UNKNOWN16)
        w.u16(speedMs?.let { Math.round(it * 100).toInt().coerceIn(0, UNKNOWN16 - 1) } ?: UNKNOWN16)
        w.u16(bearingDeg?.let { Math.round(((it % 360 + 360) % 360) * 10).toInt() % 3600 } ?: UNKNOWN16)
        w.u32(timeSec).u8(battery?.coerceIn(0, 100) ?: UNKNOWN8)
    }

    override fun toString(): String = "PeerFix($lat, $lon @$timeSec)"

    companion object {
        private const val COORD_SCALE = 1e7
        private const val ALT_UNKNOWN = Short.MIN_VALUE.toInt()
        private const val UNKNOWN16 = 0xFFFF
        private const val UNKNOWN8 = 0xFF
        const val BYTES = 4 + 4 + 2 + 2 + 2 + 2 + 4 + 1

        internal fun read(r: ByteReader): PeerFix {
            val lat = r.i32() / COORD_SCALE
            val lon = r.i32() / COORD_SCALE
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw WireException("position out of range")
            val alt = r.i16()
            val accuracy = r.u16()
            val speed = r.u16()
            val bearing = r.u16()
            val time = r.u32()
            val battery = r.u8()
            return PeerFix(
                lat,
                lon,
                altM = alt.takeIf { it != ALT_UNKNOWN },
                accuracyM = accuracy.takeIf { it != UNKNOWN16 },
                speedMs = speed.takeIf { it != UNKNOWN16 }?.let { it / 100.0 },
                bearingDeg = bearing.takeIf { it != UNKNOWN16 && it < 3600 }?.let { it / 10.0 },
                timeSec = time,
                battery = battery.takeIf { it <= 100 },
            )
        }
    }
}

enum class MsgType(
    val code: Int,
) {
    HELLO(1),
    PEERS(2),
    SIGNAL(3),
    POS(4),
    TRAIL(5),
    PING(6),
    MSG(7),
    SOS(8),
    REKEY(9),
    BYE(10),
    KICK(11),
    AVATAR(12),
    DIAG(13),
    ;

    companion object {
        fun of(code: Int): MsgType? = entries.firstOrNull { it.code == code }
    }
}

class PeerInfo(
    val memberId: Long,
    val name: String,
    val color: Int,
    /** Seconds since this member was last heard of by the sender of the list. */
    val lastSeenAgoSec: Long,
)

/** A message between members. */
sealed interface Msg {
    val type: MsgType

    /** `true` for the unordered, unreliable channel; everything else goes on the reliable one. */
    val onPositionChannel: Boolean get() = false

    class Hello(
        val memberId: Long,
        val name: String,
        val color: Int,
        val protocol: Int = PROTOCOL,
    ) : Msg {
        override val type get() = MsgType.HELLO
    }

    class Peers(
        val peers: List<PeerInfo>,
    ) : Msg {
        override val type get() = MsgType.PEERS
    }

    /** Pairing data for [to], relayed hop by hop through the mesh. */
    class Signal(
        val seq: Long,
        val from: Long,
        val to: Long,
        val payload: ByteArray,
    ) : Msg {
        override val type get() = MsgType.SIGNAL
    }

    class Pos(
        val memberId: Long,
        val seq: Long,
        val fix: PeerFix,
    ) : Msg {
        override val type get() = MsgType.POS
        override val onPositionChannel get() = true
    }

    /** Positions kept while the sender had no link, oldest first. */
    class Trail(
        val memberId: Long,
        val fixes: List<PeerFix>,
    ) : Msg {
        override val type get() = MsgType.TRAIL
    }

    class Ping(
        val memberId: Long,
        val timeSec: Long,
    ) : Msg {
        override val type get() = MsgType.PING
        override val onPositionChannel get() = true
    }

    class Text(
        val memberId: Long,
        val seq: Long,
        val text: String,
    ) : Msg {
        override val type get() = MsgType.MSG
    }

    class Sos(
        val memberId: Long,
        val seq: Long,
        val fix: PeerFix?,
        val text: String,
    ) : Msg {
        override val type get() = MsgType.SOS
    }

    /** A new group key, sent when [excluded] (0 for nobody) leaves the group. */
    class Rekey(
        val memberId: Long,
        val seq: Long,
        val excluded: Long,
        val key: ByteArray,
    ) : Msg {
        override val type get() = MsgType.REKEY
    }

    class Bye(
        val memberId: Long,
    ) : Msg {
        override val type get() = MsgType.BYE
    }

    /** Sent to a member that has just been removed from the group, on its own link, before the link closes. */
    class Kick(
        val target: Long,
    ) : Msg {
        override val type get() = MsgType.KICK
    }

    /** The picture of [memberId] ([MemberLook.PICTURE]); empty when it has none any more. Flooded like a text. */
    class Avatar(
        val memberId: Long,
        val seq: Long,
        val image: ByteArray,
    ) : Msg {
        override val type get() = MsgType.AVATAR
    }

    /** A payload of the debug build's test tools ([DiagChannel]); flooded like a text, never shown. */
    class Diag(
        val memberId: Long,
        val seq: Long,
        val body: ByteArray,
    ) : Msg {
        override val type get() = MsgType.DIAG

        companion object {
            /** Fits a control frame of the server (6000 characters of Base64, `firebase/database.rules.json`). */
            const val MAX_BYTES = 4096
        }
    }

    companion object {
        /** 2: the colour became a packed icon and RGB colour ([MemberLook]) on 4 bytes. */
        const val PROTOCOL = 2
        const val MAX_TEXT_CHARS = 140
    }
}

/** A decoded frame: the message and how many more hops it may make. */
class Frame(
    val msg: Msg,
    val ttl: Int,
)

/**
 * Frames of the data channels: `type (1) | ttl (1) | nonce (12) | AES-GCM(payload) | tag (16)`. The type is
 * authenticated, the time to live is not (a relay lowers it without the key's help, and a non-member cannot do more
 * than shorten the life of a frame on a link it already controls).
 */
object Wire {
    const val DEFAULT_TTL = 4
    private const val MAX_FRAME = 16 * 1024
    private const val HEADER = 2

    fun encode(
        msg: Msg,
        crypto: GroupCrypto,
        ttl: Int = DEFAULT_TTL,
    ): ByteArray {
        val aad = byteArrayOf(msg.type.code.toByte())
        return aad + byteArrayOf(ttl.coerceIn(0, 255).toByte()) + crypto.seal(payload(msg), aad)
    }

    fun decode(
        frame: ByteArray,
        crypto: GroupCrypto,
    ): Frame {
        if (frame.size < HEADER || frame.size > MAX_FRAME) throw WireException("bad frame size")
        val type = MsgType.of(frame[0].toInt() and 0xFF) ?: throw WireException("unknown message type")
        val ttl = frame[1].toInt() and 0xFF
        val plain = crypto.open(frame.copyOfRange(HEADER, frame.size), byteArrayOf(type.code.toByte()))
        return Frame(parse(type, plain), ttl)
    }

    /** The type of [frame], read without the key; `null` when it is not one of ours. */
    fun typeOf(frame: ByteArray): MsgType? = frame.firstOrNull()?.let { MsgType.of(it.toInt() and 0xFF) }

    /**
     * A short name of [frame] for the event log: the start of its nonce in hexadecimal, the same on every device that
     * sends, relays or receives it (the relays only change the time to live).
     */
    fun frameId(frame: ByteArray): String =
        if (frame.size < HEADER + FRAME_ID_BYTES) {
            "-"
        } else {
            (HEADER until HEADER + FRAME_ID_BYTES).joinToString("") { "%02x".format(frame[it].toInt() and 0xFF) }
        }

    /** [frame] as a relay passes it on: same bytes, one hop less; `null` when it has no hop left. */
    fun relayed(frame: ByteArray): ByteArray? {
        val ttl = frame[1].toInt() and 0xFF
        if (ttl <= 1) return null
        return frame.copyOf().also { it[1] = (ttl - 1).toByte() }
    }

    private fun payload(msg: Msg): ByteArray {
        val w = ByteWriter()
        when (msg) {
            is Msg.Hello -> {
                w.u64(msg.memberId).str(msg.name, PairingCodec.MAX_NAME_BYTES).i32(msg.color).u8(msg.protocol)
            }

            is Msg.Peers -> {
                w.u8(msg.peers.size.coerceAtMost(MAX_PEERS))
                for (p in msg.peers.take(MAX_PEERS)) {
                    w.u64(p.memberId).str(p.name, PairingCodec.MAX_NAME_BYTES).i32(p.color)
                    w.u32(p.lastSeenAgoSec.coerceIn(0, 0xFFFFFFFFL))
                }
            }

            is Msg.Signal -> {
                w.u32(msg.seq).u64(msg.from).u64(msg.to).u16(msg.payload.size).bytes(msg.payload)
            }

            is Msg.Pos -> {
                w.u64(msg.memberId).u32(msg.seq)
                msg.fix.write(w)
            }

            is Msg.Trail -> {
                w.u64(msg.memberId).u8(msg.fixes.size.coerceAtMost(MAX_TRAIL))
                for (f in msg.fixes.takeLast(MAX_TRAIL)) f.write(w)
            }

            is Msg.Ping -> {
                w.u64(msg.memberId).u32(msg.timeSec)
            }

            is Msg.Text -> {
                w.u64(msg.memberId).u32(msg.seq).longStr(msg.text.take(Msg.MAX_TEXT_CHARS), TEXT_BYTES)
            }

            is Msg.Sos -> {
                w.u64(msg.memberId).u32(msg.seq).u8(if (msg.fix != null) 1 else 0)
                msg.fix?.write(w)
                w.longStr(msg.text.take(Msg.MAX_TEXT_CHARS), TEXT_BYTES)
            }

            is Msg.Rekey -> {
                w.u64(msg.memberId).u32(msg.seq).u64(msg.excluded).bytes(msg.key)
            }

            is Msg.Bye -> {
                w.u64(msg.memberId)
            }

            is Msg.Kick -> {
                w.u64(msg.target)
            }

            is Msg.Avatar -> {
                val image = msg.image.takeIf { it.size <= MemberLook.MAX_PICTURE_BYTES } ?: ByteArray(0)
                w.u64(msg.memberId).u32(msg.seq).u16(image.size).bytes(image)
            }

            is Msg.Diag -> {
                require(msg.body.size <= Msg.Diag.MAX_BYTES) { "diagnostic payload too large" }
                w.u64(msg.memberId).u32(msg.seq).u16(msg.body.size).bytes(msg.body)
            }
        }
        return w.toByteArray()
    }

    private fun parse(
        type: MsgType,
        plain: ByteArray,
    ): Msg {
        val r = ByteReader(plain)
        val msg: Msg =
            when (type) {
                MsgType.HELLO -> {
                    Msg.Hello(r.u64(), r.str(), r.i32(), r.u8())
                }

                MsgType.PEERS -> {
                    Msg.Peers(List(r.u8()) { PeerInfo(r.u64(), r.str(), r.i32(), r.u32()) })
                }

                MsgType.SIGNAL -> {
                    val seq = r.u32()
                    val from = r.u64()
                    val to = r.u64()
                    Msg.Signal(seq, from, to, r.bytes(r.u16()))
                }

                MsgType.POS -> {
                    Msg.Pos(r.u64(), r.u32(), PeerFix.read(r))
                }

                MsgType.TRAIL -> {
                    val member = r.u64()
                    Msg.Trail(member, List(r.u8()) { PeerFix.read(r) })
                }

                MsgType.PING -> {
                    Msg.Ping(r.u64(), r.u32())
                }

                MsgType.MSG -> {
                    Msg.Text(r.u64(), r.u32(), r.longStr())
                }

                MsgType.SOS -> {
                    val member = r.u64()
                    val seq = r.u32()
                    val fix = if (r.u8() == 1) PeerFix.read(r) else null
                    Msg.Sos(member, seq, fix, r.longStr())
                }

                MsgType.REKEY -> {
                    Msg.Rekey(r.u64(), r.u32(), r.u64(), r.bytes(GroupCrypto.KEY_BYTES))
                }

                MsgType.BYE -> {
                    Msg.Bye(r.u64())
                }

                MsgType.KICK -> {
                    Msg.Kick(r.u64())
                }

                MsgType.AVATAR -> {
                    val member = r.u64()
                    val seq = r.u32()
                    val size = r.u16()
                    if (size > MemberLook.MAX_PICTURE_BYTES) throw WireException("picture too large")
                    Msg.Avatar(member, seq, r.bytes(size))
                }

                MsgType.DIAG -> {
                    val member = r.u64()
                    val seq = r.u32()
                    val size = r.u16()
                    if (size > Msg.Diag.MAX_BYTES) throw WireException("diagnostic payload too large")
                    Msg.Diag(member, seq, r.bytes(size))
                }
            }
        if (r.remaining != 0) throw WireException("trailing data in $type")
        return msg
    }

    private const val FRAME_ID_BYTES = 4
    private const val MAX_PEERS = 32
    private const val MAX_TRAIL = 60
    private const val TEXT_BYTES = Msg.MAX_TEXT_CHARS * 4
}
