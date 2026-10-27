package fr.toolmap.core.source

import java.util.concurrent.TimeUnit

/**
 * AQX `<update-delay>`: how long a stored tile stays valid, written `<number><unit>` with the units
 * D (days), W (weeks), M (months, 30 days) and Y (years, 365 days), e.g. `0D`, `2W`, `3M`, `1Y`.
 */
object UpdateDelay {
    private val pattern = Regex("""(\d{1,5})\s*([DWMY])""", RegexOption.IGNORE_CASE)

    /**
     * Validity in milliseconds, or `null` when tiles never expire: no delay declared, `0` (we read it as
     * "never refresh", the safe reading for offline use) or text we cannot understand.
     */
    fun toMillis(text: String?): Long? {
        val match = pattern.matchEntire(text?.trim().orEmpty()) ?: return null
        val amount = match.groupValues[1].toLong()
        if (amount == 0L) return null
        val days =
            when (match.groupValues[2].uppercase()) {
                "D" -> 1L
                "W" -> 7L
                "M" -> 30L
                else -> 365L
            }
        return TimeUnit.DAYS.toMillis(amount * days)
    }
}
