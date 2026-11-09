package fr.toolmap.core.track

import fr.toolmap.core.problem.Problems
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Times in GPX, KML and GeoJSON files: ISO 8601, with or without offset, with up to nine fractional digits. */
object TrackTime {
    /** Milliseconds since the epoch, or `null` when [text] is not a date-time. A date-time without offset is UTC. */
    fun parse(text: String?): Long? {
        val value = text?.trim().orEmpty()
        if (value.isEmpty()) return null
        // The form that fits is tried first, read from the shape of the text: on Android an offset is not an instant,
        // and trying the forms in the same order for every text threw and caught one exception for each point of a
        // file written with offsets (P36 review). The other forms still follow: the same texts are read as before.
        val time = value.indexOfFirst { it == 'T' || it == 't' }
        val first =
            when {
                value.endsWith('Z', ignoreCase = true) -> INSTANT
                time >= 0 && value.indexOfAny(SIGNS, time) >= 0 -> OFFSET
                else -> LOCAL
            }
        val failures = ArrayList<RuntimeException>(FORMS.size)
        for (form in listOf(first) + FORMS.filter { it !== first }) {
            try {
                return form(value)
            } catch (e: DateTimeException) {
                failures += e
            } catch (e: ArithmeticException) {
                // A date-time that reads but does not fit in milliseconds (the year 300 000 000): no time either.
                // Not caught, it stopped the app on opening the file (P36 audit).
                failures += e
            }
        }
        // The file's time is no date-time at all: the point keeps no time. The log says why each form failed.
        val last = failures.last()
        failures.dropLast(1).forEach(last::addSuppressed)
        Problems.expected("track.time.parse", last)
        return null
    }

    private val INSTANT: (String) -> Long = { Instant.parse(it).toEpochMilli() }
    private val OFFSET: (String) -> Long = { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
    private val LOCAL: (String) -> Long = { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC).toEpochMilli() }
    private val FORMS = listOf(INSTANT, OFFSET, LOCAL)
    private val SIGNS = charArrayOf('+', '-')

    /** ISO 8601 in UTC, e.g. `2012-10-24T23:29:40Z` (fraction only when there is one). */
    fun format(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
