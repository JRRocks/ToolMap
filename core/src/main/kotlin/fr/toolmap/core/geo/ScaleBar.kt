package fr.toolmap.core.geo

import fr.toolmap.core.settings.UnitSystem
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/** Scale bar maths: ground distance of one density-independent pixel, then a rounded 1-2-5 distance. */
object ScaleBar {
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.685578488
    private const val CAMERA_TILE_SIZE = 512.0
    private const val TILE_PIXELS = 256.0

    /** Printed length of one dp: Android's dp is 1/160 inch. */
    private const val DP_METERS = 0.0254 / 160.0

    /**
     * Meters covered by one dp at [latitude] for a MapLibre camera [cameraZoom]. The camera works with 512 dp
     * tiles: at zoom 0 the whole world is 512 dp wide.
     */
    fun metersPerDp(
        latitude: Double,
        cameraZoom: Double,
    ): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latitude)) / (CAMERA_TILE_SIZE * 2.0.pow(cameraZoom))

    /** The camera zoom at which one dp covers [metersPerDp] meters at [latitude]: the inverse of [metersPerDp]. */
    fun cameraZoomFor(
        latitude: Double,
        metersPerDp: Double,
    ): Double = ln(EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latitude)) / (CAMERA_TILE_SIZE * metersPerDp)) / ln(2.0)

    /**
     * Ground meters under one dp at [latitude] when the map is shown at the displayed zoom [displayedZoom] (the `z` of
     * 256 px tiles, [fr.toolmap.core.geo.ZoomMapping]) on a screen of [density]: one tile pixel per screen pixel, as
     * AlpineQuest draws it.
     */
    fun groundMetersPerDp(
        latitude: Double,
        displayedZoom: Double,
        density: Double,
    ): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latitude)) / (TILE_PIXELS * 2.0.pow(displayedZoom)) * density

    /**
     * The scale denominator, the `n` of "1:n": [groundMetersPerDp] over the printed length of a dp, which is 1/160 inch
     * on Android whatever the density. At zoom 16 near 46° on a 2.625 density screen, about 27 370.
     */
    fun denominator(
        latitude: Double,
        displayedZoom: Double,
        density: Double,
    ): Double = groundMetersPerDp(latitude, displayedZoom, density) / DP_METERS

    /** "1:27,410" for a denominator of 27 410.4: the thousands are separated by a comma, whatever the locale. */
    fun ratioText(denominator: Double): String = "1:" + String.format(Locale.ROOT, "%,d", Math.round(denominator))

    /** Largest 1, 2 or 5 × 10^n that does not exceed [maxMeters] (0 for a non-positive input). */
    fun niceDistance(maxMeters: Double): Double {
        if (!(maxMeters > 0.0) || maxMeters.isInfinite()) return 0.0
        val exponent = floor(log10(maxMeters))
        val base = 10.0.pow(exponent)
        val mantissa = maxMeters / base
        // Guard against log10 rounding at exact powers of ten.
        val step =
            when {
                mantissa >= 5.0 -> 5.0
                mantissa >= 2.0 -> 2.0
                mantissa >= 1.0 -> 1.0
                else -> 0.5
            }
        return step * base
    }

    /** "50 cm", "200 m", "2 km". */
    fun format(meters: Double): String =
        when {
            meters < 1.0 -> "${trim(meters * 100.0)} cm"
            meters < 1000.0 -> "${trim(meters)} m"
            else -> "${trim(meters / 1000.0)} km"
        }

    private fun trim(value: Double): String {
        val rounded = Math.round(value * 100.0) / 100.0
        return if (rounded == floor(rounded)) rounded.toLong().toString() else rounded.toString()
    }

    data class Bar(
        val meters: Double,
        val widthDp: Double,
        val label: String,
    )

    /** "100 ft", "500 ft", "2 mi": the imperial counterpart of [format], for a length given in meters. */
    fun formatImperial(meters: Double): String {
        val feet = meters / METERS_PER_FOOT
        return if (feet < FEET_PER_MILE_SWITCH) "${trim(feet)} ft" else "${trim(feet / FEET_PER_MILE)} mi"
    }

    /**
     * The bar to draw: at most [maxWidthDp] wide, whose length is a round ground distance in the unit of [units]
     * (1-2-5 meters or kilometres, or feet or miles). [Bar.meters] is always the length in meters.
     */
    fun compute(
        latitude: Double,
        cameraZoom: Double,
        maxWidthDp: Double,
        units: UnitSystem = UnitSystem.METRIC,
    ): Bar = barFor(metersPerDp(latitude, cameraZoom), maxWidthDp, units)

    /**
     * The bar of a map shown at the displayed zoom [displayedZoom] on a screen of [density], the same way as
     * [denominator]: its length is a round ground distance, and a bar of [maxWidthDp] dp is the widest it may be.
     */
    fun forDisplayedZoom(
        latitude: Double,
        displayedZoom: Double,
        density: Double,
        maxWidthDp: Double,
        units: UnitSystem = UnitSystem.METRIC,
    ): Bar = barFor(groundMetersPerDp(latitude, displayedZoom, density), maxWidthDp, units)

    private fun barFor(
        perDp: Double,
        maxWidthDp: Double,
        units: UnitSystem,
    ): Bar {
        val maxMeters = perDp * maxWidthDp
        if (units == UnitSystem.METRIC) {
            val meters = niceDistance(maxMeters)
            return Bar(meters, if (perDp > 0.0) meters / perDp else 0.0, format(meters))
        }
        val maxFeet = maxMeters / METERS_PER_FOOT
        val meters =
            if (maxFeet < FEET_PER_MILE_SWITCH) {
                niceDistance(maxFeet) * METERS_PER_FOOT
            } else {
                niceDistance(maxFeet / FEET_PER_MILE) * FEET_PER_MILE * METERS_PER_FOOT
            }
        return Bar(meters, if (perDp > 0.0) meters / perDp else 0.0, formatImperial(meters))
    }

    private const val METERS_PER_FOOT = 0.3048
    private const val FEET_PER_MILE = 5280.0
    private const val FEET_PER_MILE_SWITCH = 1000.0
}
