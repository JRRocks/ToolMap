package fr.toolmap.core.track

import fr.toolmap.core.settings.UnitSystem
import java.util.Locale

/**
 * Distances, durations and speeds as a hiker reads them. Pure functions: the caller picks the [Locale] and the
 * [UnitSystem] (metric unless told otherwise).
 */
object TrackFormat {
    /**
     * Metric: `850 m` below 1 km, then `12,3 km` (two decimals under 10 km, one above). Imperial: `850 ft` below
     * 1 000 ft, then `3.25 mi` (same decimals rule).
     */
    fun distance(
        meters: Double,
        locale: Locale = Locale.getDefault(),
        units: UnitSystem = UnitSystem.METRIC,
    ): String =
        when (units) {
            UnitSystem.METRIC -> {
                when {
                    meters < 1000 -> String.format(locale, "%d m", Math.round(meters))
                    meters < 10_000 -> String.format(locale, "%.2f km", meters / 1000)
                    else -> String.format(locale, "%.1f km", meters / 1000)
                }
            }

            UnitSystem.IMPERIAL -> {
                val feet = meters * FEET_PER_METER
                when {
                    feet < FEET_PER_MILE_SWITCH -> String.format(locale, "%d ft", Math.round(feet))
                    feet < TEN_MILES_FEET -> String.format(locale, "%.2f mi", feet / FEET_PER_MILE)
                    else -> String.format(locale, "%.1f mi", feet / FEET_PER_MILE)
                }
            }
        }

    fun elevation(
        meters: Double,
        locale: Locale = Locale.getDefault(),
        units: UnitSystem = UnitSystem.METRIC,
    ): String =
        when (units) {
            UnitSystem.METRIC -> String.format(locale, "%d m", Math.round(meters))
            UnitSystem.IMPERIAL -> String.format(locale, "%d ft", Math.round(meters * FEET_PER_METER))
        }

    /**
     * Metric: `850 m²` below 1 ha, `12,3 ha` below 1 km², then `2,35 km²`. Imperial: `5 400 ft²` below an acre,
     * `12.3 ac` below a square mile, then `1.25 mi²`.
     */
    fun area(
        squareMeters: Double,
        locale: Locale = Locale.getDefault(),
        units: UnitSystem = UnitSystem.METRIC,
    ): String =
        when (units) {
            UnitSystem.METRIC -> {
                when {
                    squareMeters < SQUARE_METERS_PER_HECTARE -> {
                        String.format(locale, "%d m²", Math.round(squareMeters))
                    }

                    squareMeters < SQUARE_METERS_PER_KM2 -> {
                        String.format(locale, "%.2f ha", squareMeters / SQUARE_METERS_PER_HECTARE)
                    }

                    else -> {
                        String.format(locale, "%.3f km²", squareMeters / SQUARE_METERS_PER_KM2)
                    }
                }
            }

            UnitSystem.IMPERIAL -> {
                val squareFeet = squareMeters * FEET_PER_METER * FEET_PER_METER
                when {
                    squareFeet < SQUARE_FEET_PER_ACRE -> {
                        String.format(locale, "%d ft²", Math.round(squareFeet))
                    }

                    squareFeet < SQUARE_FEET_PER_SQUARE_MILE -> {
                        String.format(locale, "%.2f ac", squareFeet / SQUARE_FEET_PER_ACRE)
                    }

                    else -> {
                        String.format(locale, "%.3f mi²", squareFeet / SQUARE_FEET_PER_SQUARE_MILE)
                    }
                }
            }
        }

    /** `45 min`, `2 h 05`, `26 h 10`: hours and minutes, no seconds. */
    fun duration(millis: Long): String {
        val minutes = (millis + MILLIS_PER_MINUTE / 2) / MILLIS_PER_MINUTE
        val h = minutes / MINUTES_PER_HOUR
        val m = minutes % MINUTES_PER_HOUR
        return if (h == 0L) "$m min" else "$h h ${m.toString().padStart(2, '0')}"
    }

    /** Kilometres (or miles) per hour with one decimal: `4,3 km/h`, `2.7 mph`. */
    fun speed(
        metersPerSecond: Double,
        locale: Locale = Locale.getDefault(),
        units: UnitSystem = UnitSystem.METRIC,
    ): String =
        when (units) {
            UnitSystem.METRIC -> String.format(locale, "%.1f km/h", metersPerSecond * MPS_TO_KMH)
            UnitSystem.IMPERIAL -> String.format(locale, "%.1f mph", metersPerSecond * MPS_TO_MPH)
        }

    /** Slope as a signed percentage: `+12 %`, `-4 %`. */
    fun grade(
        percent: Double,
        locale: Locale = Locale.getDefault(),
    ): String = String.format(locale, "%+d %%", Math.round(percent))

    private const val MILLIS_PER_MINUTE = 60_000L
    private const val MINUTES_PER_HOUR = 60L
    private const val MPS_TO_KMH = 3.6
    private const val MPS_TO_MPH = 2.2369362920544

    /** International foot: exactly 0.3048 m. */
    const val FEET_PER_METER = 1 / 0.3048
    const val FEET_PER_MILE = 5280.0
    private const val FEET_PER_MILE_SWITCH = 1000.0
    private const val SQUARE_METERS_PER_HECTARE = 10_000.0
    private const val SQUARE_METERS_PER_KM2 = 1_000_000.0
    private const val SQUARE_FEET_PER_ACRE = 43_560.0
    private const val SQUARE_FEET_PER_SQUARE_MILE = 27_878_400.0
    private const val TEN_MILES_FEET = 10 * FEET_PER_MILE
}
