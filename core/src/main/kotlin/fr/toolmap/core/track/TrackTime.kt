package fr.toolmap.core.track

import fr.toolmap.core.problem.Problems
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
        } catch (e: DateTimeParseException) {
            // Not an instant in UTC: the next forms are tried, and this failure is kept in case none fits.
            parseWithOffset(value, e)
        }
    }

    private fun parseWithOffset(
        value: String,
        notInstant: DateTimeParseException,
    ): Long? =
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (notLocal: DateTimeParseException) {
                // The file's time is no date-time at all: the point keeps no time. The log says why each form failed.
                notLocal.addSuppressed(notInstant)
                notLocal.addSuppressed(e)
                Problems.expected("track.time.parse", notLocal)
                null
            }
        }

    /** ISO 8601 in UTC, e.g. `2012-10-24T23:29:40Z` (fraction only when there is one). */
    fun format(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
