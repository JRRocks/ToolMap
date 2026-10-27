package fr.toolmap.core.geo

import kotlin.math.floor

/** A Military Grid Reference System reference: grid zone, 100 km square and the position inside it. */
data class MgrsReference(
    val zone: Int,
    val band: Char,
    val columnLetter: Char,
    val rowLetter: Char,
    /** Meters east of the square's west edge, 0 until 100 000. */
    val easting: Int,
    /** Meters north of the square's south edge, 0 until 100 000. */
    val northing: Int,
) {
    /** `31U DQ 52482 11717` (digits truncated, never rounded, like a printed map). */
    fun format(
        digits: Int = 5,
        separators: Boolean = true,
    ): String {
        require(digits in 0..5) { "digits must be 0..5" }
        val divisor = POWERS[5 - digits]
        val e = if (digits == 0) "" else (easting / divisor).toString().padStart(digits, '0')
        val nn = if (digits == 0) "" else (northing / divisor).toString().padStart(digits, '0')
        val gzd = "$zone$band"
        val square = "$columnLetter$rowLetter"
        return if (separators) {
            listOf(gzd, square, e, nn).filter { it.isNotEmpty() }.joinToString(" ")
        } else {
            gzd + square +
                e +
                nn
        }
    }

    private companion object {
        val POWERS = intArrayOf(1, 10, 100, 1000, 10_000, 100_000)
    }
}

/** MGRS on top of [Utm] (WGS84, between 80 S and 84 N). */
object Mgrs {
    private const val COLUMN_SETS = "ABCDEFGH" + "JKLMNPQR" + "STUVWXYZ"
    private const val ROWS = "ABCDEFGHJKLMNPQRSTUV"

    /** The reference of a position; `null` outside the UTM latitudes. */
    fun of(
        lat: Double,
        lon: Double,
    ): MgrsReference? {
        val band = Utm.bandLetter(lat) ?: return null
        return of(Utm.toUtm(lat, lon), band)
    }

    fun of(
        utm: UtmCoordinate,
        band: Char,
    ): MgrsReference {
        val e100 = floor(utm.easting / 100_000.0).toInt()
        val n100 = floor(utm.northing / 100_000.0).toInt()
        val set = (utm.zone - 1) % 3
        val column = COLUMN_SETS[set * 8 + (e100 - 1).coerceIn(0, 7)]
        val rowOffset = if (utm.zone % 2 == 0) ROW_OFFSET_EVEN else 0
        val row = ROWS[Math.floorMod(n100 + rowOffset, ROWS.length)]
        return MgrsReference(
            zone = utm.zone,
            band = band,
            columnLetter = column,
            rowLetter = row,
            easting = floor(utm.easting - e100 * 100_000.0).toInt(),
            northing = floor(utm.northing - n100 * 100_000.0).toInt(),
        )
    }

    /**
     * The centre of the square a reference designates, at the precision it was written with ([digits] per axis, 0..5:
     * a reference with fewer digits names a bigger square). `null` when the letters cannot go together (a column
     * letter that does not exist in this zone, a band the position does not fall in).
     */
    fun toLonLat(
        zone: Int,
        band: Char,
        columnLetter: Char,
        rowLetter: Char,
        easting: Int,
        northing: Int,
        digits: Int = 5,
    ): LonLat? {
        if (zone !in 1..60 || digits !in 0..5) return null
        val bandIndex = BANDS.indexOf(band)
        val columnIndex = COLUMN_SETS.indexOf(columnLetter)
        val rowIndex = ROWS.indexOf(rowLetter)
        if (bandIndex < 0 || columnIndex < 0 || rowIndex < 0) return null
        // 32X, 34X and 36X do not exist: Svalbard's zones are 9° and 12° wide (P24.1).
        if (GridZones.get(zone, band) == null) return null
        val set = (zone - 1) % 3
        if (columnIndex / 8 != set) return null
        val e100 = columnIndex % 8 + 1
        val rowOffset = if (zone % 2 == 0) ROW_OFFSET_EVEN else 0
        val n100InCycle = Math.floorMod(rowIndex - rowOffset, ROWS.length)
        val cell = POWERS[5 - digits].toDouble()
        val north = bandIndex >= NORTH_FIRST_BAND
        // The band tells which of the 2 000 km repetitions of the row letters is meant: the lowest one that is not
        // south of the band (the band's southern edge is lowest on the central meridian).
        val southLat = -80.0 + 8.0 * bandIndex
        val minNorthing = Utm.toUtm(southLat, Utm.centralMeridian(zone), zone).northing
        var base = n100InCycle * 100_000.0
        val first = floor(minNorthing / 100_000.0) * 100_000.0
        while (base < first) base += ROW_CYCLE
        val utm =
            UtmCoordinate(
                zone = zone,
                north = north,
                easting = e100 * 100_000.0 + easting + cell / 2,
                northing = base + northing + cell / 2,
            )
        val position = Utm.toLonLat(utm)
        val tolerance = BAND_TOLERANCE_DEGREES
        val topLat = if (band == 'X') 84.0 else southLat + 8.0
        return position.takeIf { it.lat >= southLat - tolerance && it.lat <= topLat + tolerance }
    }

    private val POWERS = intArrayOf(1, 10, 100, 1000, 10_000, 100_000)
    private const val BANDS = "CDEFGHJKLMNPQRSTUVWX"
    private const val NORTH_FIRST_BAND = 10
    private const val ROW_CYCLE = 2_000_000.0
    private const val BAND_TOLERANCE_DEGREES = 0.5
    private const val ROW_OFFSET_EVEN = 5
}
