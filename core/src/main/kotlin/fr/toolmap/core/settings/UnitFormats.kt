package fr.toolmap.core.settings

import fr.toolmap.core.geo.Utm
import fr.toolmap.core.track.TrackFormat
import java.util.Locale
import kotlin.math.atan

/**
 * Bearings as the user reads them (AlpineQuest's « Bearing unit »): degrees, NATO mils (6 400 per turn) or gradians
 * (400 per turn). Pure functions.
 */
object BearingFormat {
    fun perTurn(unit: BearingUnit): Int =
        when (unit) {
            BearingUnit.DEGREES -> 360
            BearingUnit.MILS -> 6400
            BearingUnit.GRADIANS -> 400
        }

    /** The bearing in `[0, 360)` degrees, whatever the input. */
    fun normalize(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

    /** The bearing in the unit, in `[0, perTurn)` after rounding: `359.7°` is `0°`, not `360°`. */
    fun value(
        degrees: Double,
        unit: BearingUnit,
    ): Int {
        val turn = perTurn(unit)
        return Math.round(normalize(degrees) * turn / 360.0).toInt() % turn
    }

    /** `128°`, `2 844 mil`, `142 gon`. */
    fun format(
        degrees: Double,
        unit: BearingUnit,
        locale: Locale = Locale.getDefault(),
    ): String {
        val v = value(degrees, unit)
        return when (unit) {
            BearingUnit.DEGREES -> String.format(locale, "%d°", v)
            BearingUnit.MILS -> String.format(locale, "%d mil", v)
            BearingUnit.GRADIANS -> String.format(locale, "%d gon", v)
        }
    }
}

/** Slopes (AlpineQuest's « Slope unit »): a percentage, as `+12 %`, or an angle, as `+6.8°`. */
object SlopeFormat {
    fun format(
        percent: Double,
        unit: SlopeUnit,
        locale: Locale = Locale.getDefault(),
    ): String =
        when (unit) {
            SlopeUnit.PERCENT -> TrackFormat.grade(percent, locale)
            SlopeUnit.DEGREES -> String.format(locale, "%+.1f°", Math.toDegrees(atan(percent / 100.0)))
        }
}

/** Pressures (AlpineQuest's « Pressure unit »). The value is kept in hectopascals, as the barometer gives it. */
object PressureFormat {
    const val MMHG_PER_HPA = 0.750062
    const val INHG_PER_HPA = 0.0295300

    fun value(
        hectopascals: Double,
        unit: PressureUnit,
    ): Double =
        when (unit) {
            PressureUnit.HPA -> hectopascals
            PressureUnit.MMHG -> hectopascals * MMHG_PER_HPA
            PressureUnit.INHG -> hectopascals * INHG_PER_HPA
        }

    /** `953.54 hPa`, `715.12 mmHg`, `28.15 inHg`. */
    fun format(
        hectopascals: Double,
        unit: PressureUnit,
        locale: Locale = Locale.getDefault(),
    ): String =
        when (unit) {
            PressureUnit.HPA -> String.format(locale, "%.2f hPa", value(hectopascals, unit))
            PressureUnit.MMHG -> String.format(locale, "%.2f mmHg", value(hectopascals, unit))
            PressureUnit.INHG -> String.format(locale, "%.2f inHg", value(hectopascals, unit))
        }
}

/**
 * The north the bearings are measured from (AlpineQuest's « Reference north »). The offset is the angle from true
 * north to the reference north, positive clockwise: a magnetic bearing is the true one minus the declination
 * (east positive), a grid bearing is the true one minus the convergence of the grid.
 */
object NorthReferenceMath {
    fun offsetDegrees(
        north: NorthReference,
        declinationDegrees: Double,
        convergenceDegrees: Double,
    ): Double =
        when (north) {
            NorthReference.TRUE -> 0.0
            NorthReference.MAGNETIC -> declinationDegrees
            NorthReference.GRID -> convergenceDegrees
        }

    /** A true bearing read from the reference north. */
    fun relative(
        trueDegrees: Double,
        offsetDegrees: Double,
    ): Double = BearingFormat.normalize(trueDegrees - offsetDegrees)

    /**
     * Convergence of the UTM grid at a position: the angle between true north and grid north, east positive, in the
     * zone of the position (Norway and Svalbard exceptions included).
     */
    fun utmConvergenceDegrees(
        lat: Double,
        lon: Double,
    ): Double = Utm.convergenceDegrees(lat, lon)
}
