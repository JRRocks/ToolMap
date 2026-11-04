package fr.toolmap.core.settings

/**
 * A whole number typed in a value window (P23): read from the text of a field and checked against its limits.
 * Spaces (thin ones too) are ignored, so « 10 000 » reads as 10000; a comma or a point is refused, the values are whole.
 */
object ValueEntry {
    sealed interface Result {
        /** The field is empty: the bound is lifted (« sans limite ») where the window allows it. */
        data object Empty : Result

        data class Value(
            val value: Int,
        ) : Result

        /** A number outside [min]..[max]: the window says the limits and keeps OK greyed. */
        data class OutOfRange(
            val min: Int,
            val max: Int,
        ) : Result

        data object Invalid : Result
    }

    /** The time units of « Fréquence d'envoi », in seconds. */
    enum class TimeUnit(
        val seconds: Int,
    ) {
        SECONDS(1),
        MINUTES(60),
        HOURS(3600),
    }

    fun parse(
        text: String,
        min: Int,
        max: Int,
    ): Result {
        val digits = text.filterNot { it.isWhitespace() || it == ' ' || it == ' ' }
        if (digits.isEmpty()) return Result.Empty
        // Typed with the minus of the keyboard or a hyphen.
        val n = digits.replace('−', '-').toLongOrNull() ?: return Result.Invalid
        return if (n < min || n > max) Result.OutOfRange(min, max) else Result.Value(n.toInt())
    }

    /** A duration typed as [text] in [unit], checked in seconds against [minSec]..[maxSec]. */
    fun parseSeconds(
        text: String,
        unit: TimeUnit,
        minSec: Int,
        maxSec: Int,
    ): Result =
        when (val r = parse(text, 0, Int.MAX_VALUE / unit.seconds)) {
            is Result.Value -> {
                val s = r.value * unit.seconds
                if (s < minSec || s > maxSec) Result.OutOfRange(minSec, maxSec) else Result.Value(s)
            }

            is Result.OutOfRange -> {
                Result.OutOfRange(minSec, maxSec)
            }

            else -> {
                r
            }
        }

    /** The largest unit that writes [seconds] as a whole number: 7200 → 2 h, 90 → 90 s, 120 → 2 min. */
    fun unitOf(seconds: Int): TimeUnit =
        when {
            seconds > 0 && seconds % TimeUnit.HOURS.seconds == 0 -> TimeUnit.HOURS
            seconds > 0 && seconds % TimeUnit.MINUTES.seconds == 0 -> TimeUnit.MINUTES
            else -> TimeUnit.SECONDS
        }
}
