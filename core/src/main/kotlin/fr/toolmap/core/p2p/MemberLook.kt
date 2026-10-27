package fr.toolmap.core.p2p

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * How a member is drawn for the others, like a game token on a minimap: a silhouette (or its initials) in a free
 * colour on a contrasting disc ringed with that colour, or its own picture. Icon and colour travel as one 32-bit
 * number (the `color` of pairing codes, HELLO, PEERS and of the server's presence beacon): the icon id in the top
 * byte, the RGB colour below. A top byte that is not a known icon (`0xFF` from the ARGB colours of version 0.4.0)
 * means the initials. The picture itself travels in an AVATAR message ([Msg.Avatar]); until it arrives, the initials
 * stand in.
 */
object MemberLook {
    /** The two letters of the name, as before icons existed. */
    const val INITIALS = 0

    /** The member's own picture ([Msg.Avatar]). */
    const val PICTURE = 0x7F

    /** The largest picture accepted, encoded (a 64 px square JPEG is about 2 KB). */
    const val MAX_PICTURE_BYTES = 6 * 1024

    /** The side of the square a picture is reduced to before it is shared. */
    const val PICTURE_PX = 64

    /**
     * The tokens of the picker, at most 15 with the initials; the id is the index and travels on the wire, so a token
     * is only ever appended. The keys name the drawables (`ic_token_<key>`, silhouettes of game-icons.net).
     */
    val ICONS =
        listOf(
            "initials",
            "chess_knight",
            "wolf_head",
            "fox_head",
            "eagle_head",
            "octopus",
            "dinosaur_rex",
            "unicorn",
            "dragon_head",
            "ghost",
            "alien_skull",
            "robot_golem",
            "wizard_face",
            "ninja_head",
            "viking_head",
        )

    private const val RGB_MASK = 0xFFFFFF
    private const val OPAQUE = 0xFF000000.toInt()
    private const val ICON_SHIFT = 24

    fun pack(
        icon: Int,
        argb: Int,
    ): Int = (iconOf(icon) shl ICON_SHIFT) or (argb and RGB_MASK)

    /** The icon id of [look]; an unknown one gives [INITIALS]. */
    fun icon(look: Int): Int = iconOf(look ushr ICON_SHIFT)

    /** The opaque colour of [look]. */
    fun argb(look: Int): Int = look or OPAQUE

    /** `true` when white reads better than black on [argb]. */
    fun isDark(argb: Int): Boolean {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return r * LUMA_R + g * LUMA_G + b * LUMA_B < LUMA_THRESHOLD
    }

    /**
     * The disc under a silhouette of colour [argb]: white under a dark colour, near-black under a light one, so that
     * every colour of the wheel stays readable on any map.
     */
    fun backdrop(argb: Int): Int = if (isDark(argb)) WHITE else NEAR_BLACK

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val NEAR_BLACK = 0xFF202124.toInt()
    private const val LUMA_R = 0.299
    private const val LUMA_G = 0.587
    private const val LUMA_B = 0.114
    private const val LUMA_THRESHOLD = 140

    private fun iconOf(id: Int) = if (id in ICONS.indices || id == PICTURE) id else INITIALS

    /**
     * The name of the map image of a member: one per icon and colour, per name for [INITIALS] (and for a picture not
     * received yet), per picture content for [PICTURE].
     */
    fun imageKey(
        look: Int,
        name: String,
        picture: ByteArray? = null,
    ): String {
        val icon = icon(look)
        val rgb = String.format(Locale.ROOT, "%06x", look and RGB_MASK)
        return when {
            icon == PICTURE && picture != null -> {
                String.format(Locale.ROOT, "tm-p-%08x-%d", picture.contentHashCode(), picture.size)
            }

            icon == INITIALS || icon == PICTURE -> {
                "tm-m-$rgb-${MemberPalette.initials(name)}"
            }

            else -> {
                "tm-m-$rgb-${ICONS[icon]}"
            }
        }
    }
}

/** The colour wheel of the picker: hue around the circle, saturation from the centre (white) to the rim. */
object ColorWheel {
    private const val DEGREES = 360.0
    private const val SECTOR = 60.0
    private const val CHANNEL = 255.0
    private const val OPAQUE = 0xFF000000.toInt()

    /** Hue (degrees), saturation and value (0..1) of an ARGB colour. */
    fun toHsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / CHANNEL
        val g = (argb shr 8 and 0xFF) / CHANNEL
        val b = (argb and 0xFF) / CHANNEL
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue =
            when {
                delta == 0.0 -> 0.0
                max == r -> SECTOR * (((g - b) / delta).mod(6.0))
                max == g -> SECTOR * ((b - r) / delta + 2)
                else -> SECTOR * ((r - g) / delta + 4)
            }
        val saturation = if (max == 0.0) 0.0 else delta / max
        return floatArrayOf(hue.toFloat(), saturation.toFloat(), max.toFloat())
    }

    /** The opaque ARGB colour of [hue] (degrees), [saturation] and [value] (0..1). */
    fun fromHsv(
        hue: Float,
        saturation: Float,
        value: Float,
    ): Int {
        val h = hue.toDouble().mod(DEGREES) / SECTOR
        val s = saturation.toDouble().coerceIn(0.0, 1.0)
        val v = value.toDouble().coerceIn(0.0, 1.0)
        val c = v * s
        val x = c * (1 - kotlin.math.abs(h.mod(2.0) - 1))
        val (r, g, b) =
            when (h.toInt()) {
                0 -> Triple(c, x, 0.0)
                1 -> Triple(x, c, 0.0)
                2 -> Triple(0.0, c, x)
                3 -> Triple(0.0, x, c)
                4 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
        val m = v - c

        fun channel(f: Double) = Math.round((f + m) * CHANNEL).toInt().coerceIn(0, 0xFF)
        return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /**
     * Hue and saturation under a touch at ([dx], [dy]) from the centre of a wheel of [radius] (screen axes, y down,
     * red to the right and hues turning clockwise); a touch outside the wheel gives the rim.
     */
    fun hueSatAt(
        dx: Float,
        dy: Float,
        radius: Float,
    ): Pair<Float, Float> {
        val hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).mod(DEGREES).toFloat()
        val saturation = (hypot(dx.toDouble(), dy.toDouble()) / radius).coerceIn(0.0, 1.0).toFloat()
        return hue to saturation
    }

    /** Where [hue] and [saturation] lie on a wheel of [radius], from its centre: the inverse of [hueSatAt]. */
    fun pointOf(
        hue: Float,
        saturation: Float,
        radius: Float,
    ): Pair<Float, Float> {
        val a = Math.toRadians(hue.toDouble())
        val d = saturation.coerceIn(0f, 1f) * radius
        return (cos(a) * d).toFloat() to (sin(a) * d).toFloat()
    }
}
