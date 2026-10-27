package fr.toolmap.core.geo

import java.util.Locale
import kotlin.math.abs

/** A position as text. [outsideValidity] is set when the system is not meant for that place (Lambert 93 abroad). */
data class FormattedPosition(
    val text: String,
    val outsideValidity: Boolean = false,
)

/** Writes a position in the format the user chose: degrees, UTM, MGRS or Lambert 93. */
object CoordinateFormat {
    /**
     * [lat], [lon] in [system]. UTM and MGRS stop at 80 S and 84 N: beyond them the text falls back to decimal
     * degrees, which every system can write.
     */
    fun format(
        system: CoordinateSystem,
        lat: Double,
        lon: Double,
    ): FormattedPosition =
        when (system) {
            CoordinateSystem.DD -> {
                FormattedPosition(decimalDegrees(lat, lon))
            }

            CoordinateSystem.DDM -> {
                FormattedPosition(degreesDecimalMinutes(lat, lon))
            }

            CoordinateSystem.DMS -> {
                FormattedPosition(degreesMinutesSeconds(lat, lon))
            }

            CoordinateSystem.UTM -> {
                utm(lat, lon)?.let(::FormattedPosition) ?: FormattedPosition(decimalDegrees(lat, lon))
            }

            CoordinateSystem.MGRS -> {
                Mgrs.of(lat, lon)?.format()?.let(::FormattedPosition) ?: FormattedPosition(decimalDegrees(lat, lon))
            }

            CoordinateSystem.LAMBERT93 -> {
                val p = Lambert93.toLambert(lat, lon)
                FormattedPosition(
                    String.format(Locale.ROOT, "L93 %d E %d N", Math.round(p.easting), Math.round(p.northing)),
                    outsideValidity = !Lambert93.covers(lat, lon),
                )
            }
        }

    /** `31T 452482 E 5011717 N`: zone and latitude band, then meters east and north; `null` outside 80 S .. 84 N. */
    fun utm(
        lat: Double,
        lon: Double,
    ): String? {
        val band = Utm.bandLetter(lat) ?: return null
        val u = Utm.toUtm(lat, lon)
        return String.format(Locale.ROOT, "%d%s %d E %d N", u.zone, band, Math.round(u.easting), Math.round(u.northing))
    }

    /** `44.80000, 5.90000` (latitude first, dot decimal separator whatever the device locale). */
    fun decimalDegrees(
        latitude: Double,
        longitude: Double,
        decimals: Int = 5,
    ): String = String.format(Locale.ROOT, "%.${decimals}f, %.${decimals}f", latitude, longitude)

    /** `44.80000° N, 5.90000° E`. */
    fun decimalDegreesWithHemisphere(
        latitude: Double,
        longitude: Double,
        decimals: Int = 5,
    ): String {
        val ns = if (latitude >= 0) "N" else "S"
        val ew = if (longitude >= 0) "E" else "W"
        return String.format(
            Locale.ROOT,
            "%.${decimals}f° %s, %.${decimals}f° %s",
            abs(latitude),
            ns,
            abs(longitude),
            ew,
        )
    }

    /** `44° 48.000' N, 5° 54.000' E`. */
    fun degreesDecimalMinutes(
        latitude: Double,
        longitude: Double,
        decimals: Int = 3,
    ): String = "${ddm(latitude, "N", "S", decimals)}, ${ddm(longitude, "E", "W", decimals)}"

    /** `44° 48' 00.0" N, 5° 54' 00.0" E`. */
    fun degreesMinutesSeconds(
        latitude: Double,
        longitude: Double,
        decimals: Int = 1,
    ): String = "${dms(latitude, "N", "S", decimals)}, ${dms(longitude, "E", "W", decimals)}"

    private fun ddm(
        value: Double,
        positive: String,
        negative: String,
        decimals: Int,
    ): String {
        val unit = Math.pow(10.0, decimals.toDouble())
        // Work in rounded minute units so that 59.9996' carries into the degree instead of printing 60.000'.
        val total = Math.round(abs(value) * 60.0 * unit)
        val perDegree = (60.0 * unit).toLong()
        val degrees = total / perDegree
        val minutes = (total % perDegree) / unit
        return String.format(
            Locale.ROOT,
            "%d° %0${decimals + 3}.${decimals}f' %s",
            degrees,
            minutes,
            if (value >=
                0
            ) {
                positive
            } else {
                negative
            },
        )
    }

    private fun dms(
        value: Double,
        positive: String,
        negative: String,
        decimals: Int,
    ): String {
        val unit = Math.pow(10.0, decimals.toDouble())
        val total = Math.round(abs(value) * 3600.0 * unit)
        val perDegree = (3600.0 * unit).toLong()
        val perMinute = (60.0 * unit).toLong()
        val degrees = total / perDegree
        val minutes = (total % perDegree) / perMinute
        val seconds = (total % perMinute) / unit
        return String.format(
            Locale.ROOT,
            "%d° %02d' %0${decimals + 3}.${decimals}f\" %s",
            degrees,
            minutes,
            seconds,
            if (value >= 0) positive else negative,
        )
    }
}
