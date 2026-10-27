package fr.toolmap.core.geo

import fr.toolmap.core.geo.ParsedCoordinate.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CoordinateParserTest {
    private fun ok(text: String): Pair<Double, Double> {
        val r = CoordinateParser.parse(text)
        assertIs<ParsedCoordinate.Success>(r, "'$text' -> $r")
        return r.lat to r.lon
    }

    private fun assertPos(
        text: String,
        lat: Double,
        lon: Double,
    ) {
        val (a, b) = ok(text)
        assertEquals(lat, a, 1e-6, "lat of '$text'")
        assertEquals(lon, b, 1e-6, "lon of '$text'")
    }

    private fun fails(
        text: String,
        reason: Reason,
    ) {
        val r = CoordinateParser.parse(text)
        assertIs<ParsedCoordinate.Failure>(r, "'$text' -> $r")
        assertEquals(reason, r.reason, "'$text'")
    }

    @Test
    fun `decimal degrees in the usual spellings`() {
        assertPos("44.8, 5.9", 44.8, 5.9)
        assertPos("44.8 5.9", 44.8, 5.9)
        assertPos("44.8;5.9", 44.8, 5.9)
        assertPos("44.8,5.9", 44.8, 5.9)
        assertPos("-44.8, -5.9", -44.8, -5.9)
        assertPos("  +44.8   ,  +5.9 ", 44.8, 5.9)
        assertPos("44.8° N, 5.9° E", 44.8, 5.9)
        assertPos("45 6", 45.0, 6.0)
    }

    @Test
    fun `hemisphere letters before or after, French words, and swapped axes`() {
        assertPos("N44.8 E5.9", 44.8, 5.9)
        assertPos("44.8S 5.9W", -44.8, -5.9)
        assertPos("44.8 N 5.9 O", 44.8, -5.9)
        assertPos("5.9E 44.8N", 44.8, 5.9)
        assertPos("Nord 44.8 Est 5.9", 44.8, 5.9)
        assertPos("44.8 sud, 5.9 ouest", -44.8, -5.9)
    }

    @Test
    fun `degrees decimal minutes`() {
        assertPos("N 44° 48.000' E 5° 54.000'", 44.8, 5.9)
        assertPos("44 48.0 N 5 54.0 E", 44.8, 5.9)
        assertPos("44°48.5'N 5°54.25'W", 44.808333, -5.904167)
    }

    @Test
    fun `degrees minutes seconds`() {
        assertPos("44°48'00\"N 5°54'00\"E", 44.8, 5.9)
        assertPos("44° 48' 36\" N, 5° 54' 36\" E", 44.81, 5.91)
        assertPos("44 48 36 N 5 54 36 W", 44.81, -5.91)
        assertPos("S 33°51'54\" E 151°12'36\"", -33.865, 151.21)
        assertPos("44º48′36″N 5º54′36″E", 44.81, 5.91)
    }

    @Test
    fun `decimal comma when the pair is told apart otherwise`() {
        assertPos("44,8 5,9", 44.8, 5.9)
        assertPos("44,8; 5,9", 44.8, 5.9)
        assertPos("44,8 N 5,9 E", 44.8, 5.9)
        assertPos("44,5", 44.0, 5.0)
        assertPos("44, 5", 44.0, 5.0)
    }

    @Test
    fun `out of range and malformed input is refused with a reason`() {
        fails("91 5", Reason.RANGE)
        fails("44 181", Reason.RANGE)
        fails("44°60'00\"N 5°0'0\"E", Reason.SEXAGESIMAL)
        fails("44°10'60\"N 5°0'0\"E", Reason.SEXAGESIMAL)
        fails("44.5°10' N 5° E", Reason.SEXAGESIMAL)
        fails("44 N 45 N", Reason.AXES)
        fails("5 E 6 W", Reason.AXES)
        fails("-44 S 5 E", Reason.AXES)
        fails("", Reason.SYNTAX)
        fails("hello", Reason.SYNTAX)
        fails("44.8", Reason.SYNTAX)
        fails("44 5 6 7 8 9 10", Reason.SYNTAX)
        fails("44.8 5.9 7.0", Reason.SYNTAX)
    }

    @Test
    fun `what the formatter writes the parser reads back`() {
        val samples =
            listOf(44.80001 to 5.9, -33.8688 to 151.2093, 0.0 to 0.0, 47.99999 to -179.99999, -89.5 to 0.00001)
        for ((lat, lon) in samples) {
            val dd = CoordinateFormat.decimalDegreesWithHemisphere(lat, lon, 6)
            val ddm = CoordinateFormat.degreesDecimalMinutes(lat, lon, 5)
            val dms = CoordinateFormat.degreesMinutesSeconds(lat, lon, 3)
            for ((text, tolerance) in listOf(dd to 1e-6, ddm to 1e-6, dms to 1e-5)) {
                val (a, b) = ok(text)
                assertEquals(lat, a, tolerance, "lat of '$text'")
                assertEquals(lon, b, tolerance, "lon of '$text'")
            }
        }
    }

    @Test
    fun `formatters carry rounding into the larger unit`() {
        assertEquals("44° 48.000' N, 5° 54.000' E", CoordinateFormat.degreesDecimalMinutes(44.8, 5.9))
        assertEquals("45° 00.000' N, 0° 00.000' E", CoordinateFormat.degreesDecimalMinutes(44.9999999, 0.0))
        assertEquals("44° 48' 36.0\" N, 5° 54' 36.0\" W", CoordinateFormat.degreesMinutesSeconds(44.81, -5.91))
        assertEquals(
            "45° 00' 00.0\" S, 1° 00' 00.0\" E",
            CoordinateFormat.degreesMinutesSeconds(-44.99999999, 0.99999999),
        )
    }
}
