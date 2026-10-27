package fr.toolmap.core.p2p

import kotlinx.serialization.Serializable
import java.util.Base64

/**
 * The Firebase project that coordinates a group (ADR-033): the address of its Realtime Database and its web API key
 * (an identifier, not a secret: what protects the data is the database rules and the encryption of every frame).
 */
@Serializable
data class RelayConfig(
    val dbUrl: String,
    val apiKey: String,
) {
    /** The database address without a trailing slash, in `https`. */
    val base: String get() = dbUrl.trim().trimEnd('/')

    val isUsable: Boolean get() = problem(this) == null

    companion object {
        const val MAX_URL_BYTES = 120
        const val MAX_KEY_BYTES = 60

        /** What is wrong with [config], or `null`. */
        fun problem(config: RelayConfig): Problem? {
            val url = config.base
            return when {
                !url.startsWith("https://") || url.length < "https://x.y".length || ' ' in url || '?' in url ||
                    '#' in url || url.length > MAX_URL_BYTES -> Problem.BAD_URL

                config.apiKey.isBlank() || config.apiKey.length > MAX_KEY_BYTES ||
                    config.apiKey.any { it.isWhitespace() } -> Problem.BAD_KEY

                else -> null
            }
        }

        fun of(
            dbUrl: String,
            apiKey: String,
        ): RelayConfig = RelayConfig(dbUrl.trim().trimEnd('/'), apiKey.trim())
    }

    enum class Problem { BAD_URL, BAD_KEY }
}

/** Where a group lives on the server: a room named after the group, and the paths of its nodes. */
object RelayPaths {
    /** The room of a group: 32 hexadecimal characters of the group id. */
    fun room(groupId: ByteArray): String = groupId.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The key of a member under `b`: prefixed so that Firebase never mistakes a run of digits for an array index. */
    fun member(memberId: Long): String = "m" + java.lang.Long.toHexString(memberId)

    fun memberOf(key: String): Long? =
        if (key.length in 2..MEMBER_KEY_MAX && key[0] == 'm') {
            runCatching { java.lang.Long.parseUnsignedLong(key.substring(1), HEX_RADIX) }.getOrNull()
        } else {
            null
        }

    private const val HEX_RADIX = 16
    private const val MEMBER_KEY_MAX = 17
}

/** An invitation to a group that lives on a server: the group key, who invites, and where the group is. */
class RoomInvite(
    val createdAtSec: Long,
    val groupId: ByteArray,
    val groupKey: ByteArray,
    val inviterId: Long,
    val inviterName: String,
    val server: RelayConfig,
) {
    init {
        require(groupId.size == PairingPayload.GROUP_ID_BYTES)
        require(groupKey.size == GroupCrypto.KEY_BYTES)
    }
}

/** The text of a [RoomInvite]: bytes → Base45 for a QR code, or `toolmap://join?d=…` (URL-safe Base64) for a message. */
object RoomInviteCodec {
    const val LINK_PREFIX = "toolmap://join?d="
    const val MAX_AGE_SEC = 7L * 24 * 3600
    private const val VERSION = 2

    fun encodeBytes(i: RoomInvite): ByteArray =
        ByteWriter()
            .u16(PairingCodec.MAGIC)
            .u8(VERSION)
            .u32(i.createdAtSec)
            .bytes(i.groupId)
            .bytes(i.groupKey)
            .u64(i.inviterId)
            .str(i.inviterName, PairingCodec.MAX_NAME_BYTES)
            .str(i.server.base, RelayConfig.MAX_URL_BYTES)
            .str(i.server.apiKey, RelayConfig.MAX_KEY_BYTES)
            .toByteArray()

    fun decodeBytes(data: ByteArray): RoomInvite {
        val r = ByteReader(data)
        if (r.u16() != PairingCodec.MAGIC) throw WireException("not a ToolMap code")
        if (r.u8() != VERSION) throw WireException("not a group invitation")
        val created = r.u32()
        val groupId = r.bytes(PairingPayload.GROUP_ID_BYTES)
        val key = r.bytes(GroupCrypto.KEY_BYTES)
        val inviter = r.u64()
        val name = r.str()
        val url = r.str()
        val apiKey = r.str()
        if (r.remaining != 0) throw WireException("trailing data")
        val server = RelayConfig(url, apiKey)
        if (!server.isUsable) throw WireException("bad server in invitation")
        return RoomInvite(created, groupId, key, inviter, name, server)
    }

    fun toQrText(i: RoomInvite): String = Base45.encode(encodeBytes(i))

    fun toLink(i: RoomInvite): String =
        LINK_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(encodeBytes(i))

    /** `null` when [text] is not an invitation to a server group (it may be a pairing code of the direct mode). */
    fun parseOrNull(text: String): RoomInvite? {
        val t = text.trim()
        val bytes =
            try {
                if (t.startsWith(LINK_PREFIX, ignoreCase = true)) {
                    Base64.getUrlDecoder().decode(t.substring(LINK_PREFIX.length).substringBefore('&'))
                } else if (t.startsWith("toolmap://", ignoreCase = true)) {
                    return null
                } else {
                    Base45.decode(t)
                }
            } catch (e: IllegalArgumentException) {
                return null
            } catch (e: WireException) {
                return null
            }
        if (bytes.size < 3 || (bytes[2].toInt() and 0xFF) != VERSION) return null
        return decodeBytes(bytes)
    }

    fun staleness(
        i: RoomInvite,
        nowSec: Long,
    ): PairingCodec.Staleness? =
        when {
            nowSec - i.createdAtSec > MAX_AGE_SEC -> PairingCodec.Staleness.EXPIRED
            i.createdAtSec - nowSec > MAX_AGE_SEC -> PairingCodec.Staleness.FROM_THE_FUTURE
            else -> null
        }
}

/** How the connection to the coordination server is doing, for the whole group. */
enum class ServerState {
    /** The group does not use a server (direct mode, or no group). */
    NONE,

    /** Signing in or opening the room. */
    CONNECTING,

    /** The room is open and the heartbeat is accepted. */
    ONLINE,

    /** The network or the server does not answer; the app keeps trying. */
    UNREACHABLE,

    /** The server refused us (wrong API key, anonymous sign-in off, rules not published). */
    REFUSED,
}

/** The state of a member as the user reads it: one icon and one word per member. */
enum class PeerState {
    /** Linked and heard recently. */
    ONLINE,

    /** In the group but nothing yet received from it (just joined, or never connected since we came). */
    PAIRED,

    /** It was online and went silent for a short while: network cut, phone asleep, no GPS fix. */
    LOST_SYNC,

    /** Silent for a long time. */
    OFFLINE,

    /** It left the group or was removed, or this device is in no group. */
    UNPAIRED,
    ;

    companion object {
        /** Silent for longer than this, a member counts as offline rather than out of sync. */
        const val LOST_SYNC_WINDOW_MS = 10 * 60_000L

        /**
         * The state of [m] at [nowMs]; [serverUp] is `false` when this device itself cannot reach the server, in which
         * case it cannot vouch for anyone (including itself).
         */
        fun of(
            m: MemberView,
            nowMs: Long,
            serverUp: Boolean = true,
        ): PeerState =
            when {
                m.isSelf -> if (serverUp) ONLINE else OFFLINE
                m.status == MemberStatus.LEFT -> UNPAIRED
                !serverUp -> LOST_SYNC
                m.status == MemberStatus.DIRECT || m.status == MemberStatus.RELAYED -> ONLINE
                m.fix == null -> PAIRED
                nowMs - m.lastHeardMs <= LOST_SYNC_WINDOW_MS -> LOST_SYNC
                else -> OFFLINE
            }
    }
}

/** The link of a group to its coordination server, as [fr.toolmap.core.p2p.MeshNode] sees any other transport. */
interface RelayTransport : MeshTransport {
    var identity: TransportIdentity?

    /** Told whenever the state of the server connection changes (from any thread). */
    var onState: ((ServerState) -> Unit)?

    val state: ServerState

    /** Writes to the server not done yet (the test tools wait for none before sending theirs, P21). */
    val pendingWrites: Int get() = 0

    /** Signs in and opens the room; links to the other members then appear through the [LinkListener]. */
    fun start()

    /** Ignores [peer] from now on (it was removed from the group). */
    fun ban(peer: Long)

    /**
     * The phone's network changed (or came back): retry at once instead of waiting for the next attempt, and drop a
     * stream that may still be bound to the network that went away.
     */
    fun reconnectNow() = Unit

    /**
     * The phone's default network is now [network] (an opaque name): [reconnectNow] then drops a stream opened on
     * another one, however recent (P21.5 f).
     */
    fun onNetwork(network: String) = Unit

    /** Android cuts the app off the network ([blocked]) or lets it back: no attempt is made meanwhile (P21.5 h). */
    fun onBlocked(blocked: Boolean) = Unit

    /** The default network is gone and no other took its place: attempts wait for the next [onNetwork] (P21.5 p). */
    fun onOffline() = Unit

    /** The phone's clock was set (by hand or by the network): the offset to the server time is measured again. */
    fun onClockChanged() = Unit

    /** Leaves the room (the member disappears for the others) and releases the connection. */
    fun close()
}

fun interface RelayTransportFactory {
    fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
    ): RelayTransport
}
