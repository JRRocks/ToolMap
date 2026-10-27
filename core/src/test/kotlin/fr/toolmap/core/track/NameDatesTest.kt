package fr.toolmap.core.track

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class NameDatesTest {
    private val paris = ZoneId.of("Europe/Paris")

    private fun millis(
        text: String,
        zone: ZoneId = paris,
    ): Long = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `day, abbreviated French month, year and 24-hour time`() {
        assertEquals("22 oct. 2026 à 17:02", NameDates.format(millis("2026-10-22T17:02:59"), paris))
        assertEquals("1 mai 2027 à 08:05", NameDates.format(millis("2027-05-01T08:05"), paris))
        assertEquals("30 juin 2026 à 00:00", NameDates.format(millis("2026-06-30T00:00"), paris))
        assertEquals("15 févr. 2026 à 23:59", NameDates.format(millis("2026-02-15T23:59"), paris))
    }

    @Test
    fun `the phone language does not change the format`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.US, Locale.GERMANY, Locale.JAPAN)) {
                Locale.setDefault(locale)
                assertEquals("22 oct. 2026 à 17:02", NameDates.format(millis("2026-10-22T17:02"), paris))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the time is read in the given zone`() {
        val utc = millis("2026-10-22T15:02", ZoneId.of("UTC"))
        assertEquals("22 oct. 2026 à 17:02", NameDates.format(utc, paris))
        assertEquals("22 oct. 2026 à 11:02", NameDates.format(utc, ZoneId.of("America/New_York")))
    }
}
