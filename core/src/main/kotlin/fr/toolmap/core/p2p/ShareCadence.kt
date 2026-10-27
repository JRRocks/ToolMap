package fr.toolmap.core.p2p

import fr.toolmap.core.track.Geodesy

/** What the last sent position was, to decide about the next. */
class SentPosition(
    val timeSec: Long,
    val lat: Double,
    val lon: Double,
)

/**
 * When a new GPS fix is worth sending: not before the interval has passed (the GPS is asked for that rhythm, a fix
 * can come up to a second early), only if the device moved at least [minMoveMeters] since the last one sent, and in any
 * case after [KEEP_ALIVE_FACTOR] intervals so the others know it is still there.
 */
class ShareCadence(
    val intervalSec: Int,
    val minMoveMeters: Double,
) {
    init {
        require(intervalSec >= MIN_INTERVAL_S) { "interval below $MIN_INTERVAL_S s" }
    }

    fun shouldSend(
        last: SentPosition?,
        fix: PeerFix,
    ): Boolean {
        if (last == null) return true
        val elapsed = fix.timeSec - last.timeSec
        // The clock went back (set by hand or by the network): the interval counts as passed (P21.5 n).
        if (elapsed < 0) return true
        if (elapsed < intervalSec - EARLY_TOLERANCE_S) return false
        if (elapsed >= intervalSec.toLong() * KEEP_ALIVE_FACTOR) return true
        return minMoveMeters <= 0.0 || Geodesy.distance(last.lat, last.lon, fix.lat, fix.lon) >= minMoveMeters
    }

    companion object {
        const val MIN_INTERVAL_S = 5
        const val KEEP_ALIVE_FACTOR = 5
        private const val EARLY_TOLERANCE_S = 1
        val PRESETS_S = listOf(5, 10, 15, 30, 60, 120, 300, 600, 900, 1800)
        val MOVE_PRESETS_M = listOf(0, 5, 10, 25, 50, 100)
    }
}

/** The colours a member can pick (ARGB), indexed by the byte sent in pairing codes and HELLO messages. */
object MemberPalette {
    val colors =
        intArrayOf(
            0xFFD32F2F.toInt(),
            0xFF1976D2.toInt(),
            0xFF388E3C.toInt(),
            0xFFF57C00.toInt(),
            0xFF7B1FA2.toInt(),
            0xFF00838F.toInt(),
            0xFFC2185B.toInt(),
            0xFF5D4037.toInt(),
            0xFF455A64.toInt(),
            0xFFAFB42B.toInt(),
            0xFF512DA8.toInt(),
            0xFF00796B.toInt(),
        )

    fun argb(index: Int): Int = colors[Math.floorMod(index, colors.size)]

    /** Two letters for the marker: the initials of the first two words, else the first two letters. */
    fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val letters =
            when {
                words.isEmpty() -> "?"
                words.size == 1 -> words[0].take(2)
                else -> "${words[0].first()}${words[1].first()}"
            }
        return letters.uppercase()
    }
}
