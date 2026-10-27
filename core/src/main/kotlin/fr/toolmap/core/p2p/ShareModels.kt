package fr.toolmap.core.p2p

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom

/** What the user chose for sharing; kept between launches. */
@Serializable
data class ShareSettings(
    val name: String = "",
    /** The palette colour of version 0.4.0, used until a [color] is chosen. */
    val colorIndex: Int = 0,
    val intervalSec: Int = DEFAULT_INTERVAL_S,
    val minMoveMeters: Int = DEFAULT_MIN_MOVE_M,
    /** Use public STUN servers to learn the public address (nothing else goes through them). */
    val stun: Boolean = true,
    /** The Firebase project used for new groups; `null` means the one built into the app, or none (direct mode). */
    val server: RelayConfig? = null,
    /** The icon the others see ([MemberLook.ICONS]). */
    val icon: Int = MemberLook.INITIALS,
    /** The colour of the icon's disc (ARGB), chosen on the colour wheel; `null` keeps [colorIndex]. */
    val color: Int? = null,
    /** Draw the path of each member on the map; off, only their current position shows. */
    val showTrails: Boolean = true,
    /** The member's own picture as Base64 (a small JPEG, [MemberLook.MAX_PICTURE_BYTES] at most). */
    val picture: String? = null,
) {
    /** The bytes of [picture]; `null` when there is none or it is damaged. */
    fun pictureBytes(): ByteArray? =
        picture?.let {
            try {
                java.util.Base64.getDecoder().decode(it).takeIf { b ->
                    b.isNotEmpty() &&
                        b.size <= MemberLook.MAX_PICTURE_BYTES
                }
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    /** These settings with [bytes] as the picture, chosen as the icon; `null` removes it (back to the initials). */
    fun withPicture(bytes: ByteArray?): ShareSettings =
        if (bytes == null) {
            copy(picture = null, icon = if (icon == MemberLook.PICTURE) MemberLook.INITIALS else icon)
        } else {
            copy(picture = java.util.Base64.getEncoder().encodeToString(bytes), icon = MemberLook.PICTURE)
        }

    /** The colour of the disc, from the wheel or else the palette. */
    fun colorArgb(): Int = color?.let(MemberLook::argb) ?: MemberPalette.argb(colorIndex)

    /** The packed icon and colour sent to the others. */
    fun look(): Int = MemberLook.pack(icon, colorArgb())

    fun cadence() = ShareCadence(intervalSec.coerceAtLeast(ShareCadence.MIN_INTERVAL_S), minMoveMeters.toDouble())

    companion object {
        const val DEFAULT_INTERVAL_S = 30
        const val DEFAULT_MIN_MOVE_M = 10
        val STUN_SERVERS = listOf("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302")
    }
}

/** The group this device belongs to, enough to come back to it after a restart. */
class GroupRecord(
    val groupId: ByteArray,
    val key: ByteArray,
    val selfId: Long,
    /** The server this group lives on; `null` for a group of the direct (QR, WebRTC) mode. */
    val server: RelayConfig? = null,
    /** The icon the others see ([MemberLook.ICONS]). */
    val icon: Int = MemberLook.INITIALS,
    /** The colour of the icon's disc (ARGB), chosen on the colour wheel; `null` keeps [colorIndex]. */
    val color: Int? = null,
    /** Draw the path of each member on the map; off, only their current position shows. */
    val showTrails: Boolean = true,
) {
    companion object {
        /** A group created now by this device: random id, key and member id. */
        fun create(
            server: RelayConfig? = null,
            random: SecureRandom = SecureRandom(),
        ) = GroupRecord(
            ByteArray(PairingPayload.GROUP_ID_BYTES).also(random::nextBytes),
            GroupCrypto.randomKey(random),
            newMemberId(random),
            server,
        )

        /** A random member id (never 0, which stands for "nobody"). */
        fun newMemberId(random: SecureRandom = SecureRandom()): Long {
            var id: Long
            do {
                id = random.nextLong()
            } while (id == 0L)
            return id
        }
    }
}

@Serializable
private class GroupJson(
    @SerialName("id") val id: String,
    val key: String,
    val self: String,
    val server: RelayConfig? = null,
)

@Serializable
private class ShareStateJson(
    val settings: ShareSettings = ShareSettings(),
    val group: GroupJson? = null,
)

/** The persisted form: a JSON document with the settings and the current group, if any. */
class ShareState(
    val settings: ShareSettings = ShareSettings(),
    val group: GroupRecord? = null,
) {
    fun toJson(): String =
        JSON.encodeToString(
            ShareStateJson.serializer(),
            ShareStateJson(
                settings,
                group?.let {
                    GroupJson(hex(it.groupId), hex(it.key), java.lang.Long.toHexString(it.selfId), it.server)
                },
            ),
        )

    companion object {
        private val JSON =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** The state in [json]; a damaged or empty document gives the defaults (and no group). */
        fun fromJson(json: String): ShareState =
            try {
                val raw = JSON.decodeFromString(ShareStateJson.serializer(), json)
                val group =
                    raw.group?.let {
                        val id = unhex(it.id)
                        val key = unhex(it.key)
                        if (id.size == PairingPayload.GROUP_ID_BYTES && key.size == GroupCrypto.KEY_BYTES) {
                            GroupRecord(
                                id,
                                key,
                                java.lang.Long.parseUnsignedLong(it.self, HEX),
                                it.server?.takeIf(RelayConfig::isUsable),
                            )
                        } else {
                            null
                        }
                    }
                ShareState(raw.settings.sanitized(), group)
            } catch (e: kotlinx.serialization.SerializationException) {
                ShareState()
            } catch (e: IllegalArgumentException) {
                ShareState()
            }

        private fun ShareSettings.sanitized() =
            copy(
                name = name.take(PairingCodec.MAX_NAME_BYTES),
                colorIndex = Math.floorMod(colorIndex, MemberPalette.colors.size),
                icon =
                    when {
                        icon in MemberLook.ICONS.indices -> icon
                        icon == MemberLook.PICTURE && pictureBytes() != null -> icon
                        else -> MemberLook.INITIALS
                    },
                picture = picture?.takeIf { pictureBytes() != null },
                color = color?.let(MemberLook::argb),
                intervalSec = intervalSec.coerceIn(ShareCadence.MIN_INTERVAL_S, MAX_INTERVAL_S),
                minMoveMeters = minMoveMeters.coerceIn(0, MAX_MOVE_M),
                server = server?.let { RelayConfig.of(it.dbUrl, it.apiKey) }?.takeIf(RelayConfig::isUsable),
            )

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        private fun unhex(s: String): ByteArray {
            require(s.length % 2 == 0) { "odd hex" }
            return ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(HEX).toByte() }
        }

        private const val HEX = 16
        private const val MAX_INTERVAL_S = 1800
        private const val MAX_MOVE_M = 1000
    }
}
