package fr.toolmap.core.track

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Times in GPX, KML and GeoJSON files: ISO 8601, with or without offset, with up to nine fractional digits. */
object TrackTime {
    /** Milliseconds since the epoch, or `null` when [text] is not a date-time. A date-time without offset is UTC. */
    fun parse(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            parseWithOffset(value)
        }
    }

    private fun parseWithOffset(value: String): Long? =
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /** ISO 8601 in UTC, e.g. `2012-10-24T23:29:40Z` (fraction only when there is one). */
    fun format(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
