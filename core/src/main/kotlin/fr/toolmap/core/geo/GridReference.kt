package fr.toolmap.core.geo

import fr.toolmap.core.geo.ParsedCoordinate.Reason

/**
 * Reads the references of the projected grids: UTM (`31T 452482 E 5011717 N`), MGRS (`31T DQ 52482 11717`) and
 * Lambert 93 (`L93 E 452482 N 6411717`). Returns `null` when the text does not look like one of them, so that the
 * caller can try degrees; a text that looks like one but cannot be a position gives a [Reason.GRID] failure.
 *
 * A letter right after the zone number is a latitude band (C..M south, N..X north), as printed on maps.
 */
object GridReference {
    private val MGRS =
        Regex("""^(?:MGRS)?\s*(\d{1,2})\s*([C-HJ-NP-X])\s*([A-HJ-NP-Z])([A-HJ-NP-V])\s*((?:\d\s*){0,10})$""")
    private val UTM = Regex("""^(UTM)?\s*(\d{1,2})(?![\d.])\s*([C-HJ-NP-X])?\s*(.*)$""")
    private val LAMBERT = Regex("""^(?:L[\s-]?93|LAMBERT[\s-]*93|RGF[\s-]?93|EPSG:?2154)[\s:]*(.*)$""")
    private val NUMBER = Regex("""\d+(?:\.\d+)?""")
    private val MARKS = Regex("""M?[EN]|M\b""")

    fun parse(text: String): ParsedCoordinate? {
        val input = text.trim().uppercase().replace(',', ' ').replace(';', ' ').replace(Regex("""\s+"""), " ")
        if (input.isEmpty()) return null
        return parseMgrs(input) ?: parseLambert(input) ?: parseUtm(input)
    }

    private fun parseMgrs(input: String): ParsedCoordinate? {
        val m = MGRS.matchEntire(input) ?: return null
        val digits = m.groupValues[5].filter { it.isDigit() }
        if (digits.length % 2 != 0) return ParsedCoordinate.Failure(Reason.GRID)
        val half = digits.length / 2
        val e = if (half == 0) 0 else digits.take(half).toInt() * pow10(MGRS_DIGITS - half)
        val n = if (half == 0) 0 else digits.drop(half).toInt() * pow10(MGRS_DIGITS - half)
        val position =
            Mgrs.toLonLat(
                zone = m.groupValues[1].toInt(),
                band = m.groupValues[2][0],
                columnLetter = m.groupValues[3][0],
                rowLetter = m.groupValues[4][0],
                easting = e,
                northing = n,
                digits = half,
            ) ?: return ParsedCoordinate.Failure(Reason.GRID)
        return ParsedCoordinate.Success(position.lat, position.lon)
    }

    private fun parseUtm(input: String): ParsedCoordinate? {
        val m = UTM.matchEntire(input) ?: return null
        val explicit = m.groupValues[1].isNotEmpty()
        val bandText = m.groupValues[3]
        if (!explicit && bandText.isEmpty()) return null
        val numbers = numbersOf(m.groupValues[4]) ?: return null
        if (numbers.size != 2) return null
        val zone = m.groupValues[2].toInt()
        val (easting, northing) = numbers
        // A real easting has at least five digits; "44 N 5 6" is degrees and minutes. (A northing can be 0 on the equator.)
        if (!explicit && easting < MIN_EASTING) return null
        if (zone !in 1..60 || easting !in 0.0..EASTING_LIMIT || northing !in 0.0..NORTHING_LIMIT) {
            return ParsedCoordinate.Failure(Reason.GRID)
        }
        val north = bandText.isEmpty() || bandText[0] >= 'N'
        val position = Utm.toLonLat(UtmCoordinate(zone, north, easting, northing))
        if (position.lat !in -LAT_LIMIT..LAT_LIMIT) return ParsedCoordinate.Failure(Reason.GRID)
        return ParsedCoordinate.Success(position.lat, position.lon)
    }

    private fun parseLambert(input: String): ParsedCoordinate? {
        val m = LAMBERT.matchEntire(input) ?: return null
        val numbers = numbersOf(m.groupValues[1]) ?: return ParsedCoordinate.Failure(Reason.GRID)
        if (numbers.size != 2) return ParsedCoordinate.Failure(Reason.GRID)
        val (easting, northing) = numbers
        if (easting !in LAMBERT_E_RANGE || northing !in LAMBERT_N_RANGE) return ParsedCoordinate.Failure(Reason.GRID)
        val position = Lambert93.toLonLat(PlanarCoordinate(easting, northing))
        return ParsedCoordinate.Success(position.lat, position.lon)
    }

    /** The numbers of [text] once the E / N / m marks are removed; `null` when anything else is left in it. */
    private fun numbersOf(text: String): List<Double>? {
        val cleaned = MARKS.replace(text, " ")
        val numbers = NUMBER.findAll(cleaned).map { it.value.toDouble() }.toList()
        val leftover = NUMBER.replace(cleaned, "").trim()
        return numbers.takeIf { leftover.isEmpty() }
    }

    private fun pow10(n: Int): Int {
        var r = 1
        repeat(n) { r *= 10 }
        return r
    }

    private const val MGRS_DIGITS = 5
    private const val MIN_EASTING = 10_000.0
    private const val EASTING_LIMIT = 1_000_000.0
    private const val NORTHING_LIMIT = 10_000_000.0
    private const val LAT_LIMIT = 90.0
    private val LAMBERT_E_RANGE = 0.0..1_300_000.0
    private val LAMBERT_N_RANGE = 6_000_000.0..7_200_000.0
}
