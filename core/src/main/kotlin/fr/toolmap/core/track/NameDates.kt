package fr.toolmap.core.track

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dates in default names ("Sortie du 22 oct. 2026 à 17:02") and in the start and end rows of a trace: always French,
 * whatever the phone language, since every other text of the app is French.
 */
object NameDates {
    private val FORMAT = DateTimeFormatter.ofPattern("d MMM yyyy 'à' HH:mm", Locale.FRENCH)

    /** [millis] since the epoch, read in [zone] (the phone's by default), e.g. `22 oct. 2026 à 17:02`. */
    fun format(
        millis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = FORMAT.format(Instant.ofEpochMilli(millis).atZone(zone))
}
