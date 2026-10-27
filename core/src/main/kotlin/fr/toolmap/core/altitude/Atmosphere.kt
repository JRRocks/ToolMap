package fr.toolmap.core.altitude

import kotlin.math.pow

/** The international standard atmosphere below 11 km, which is what a phone barometer is read against. */
object Atmosphere {
    const val STANDARD_SEA_LEVEL_HPA = 1013.25

    /** Scale height of the troposphere formula, in meters. */
    private const val HEIGHT_SCALE = 44_330.77

    /** The exponent of the pressure ratio: `p / p0 = (1 - h / HEIGHT_SCALE) ^ EXPONENT`. */
    private const val EXPONENT = 5.255876

    /** Lowest and highest pressures a barometer on the ground can read; anything else is a faulty reading. */
    const val MIN_PLAUSIBLE_HPA = 300.0
    const val MAX_PLAUSIBLE_HPA = 1100.0

    /** Altitude where the pressure is [pressureHpa] when it is [seaLevelHpa] at sea level. */
    fun altitudeMeters(
        pressureHpa: Double,
        seaLevelHpa: Double,
    ): Double = HEIGHT_SCALE * (1.0 - (pressureHpa / seaLevelHpa).pow(1.0 / EXPONENT))

    /** The sea-level pressure that makes a barometer reading [pressureHpa] correspond to [altitudeMeters]. */
    fun seaLevelHpa(
        pressureHpa: Double,
        altitudeMeters: Double,
    ): Double = pressureHpa / (1.0 - altitudeMeters / HEIGHT_SCALE).pow(EXPONENT)

    /**
     * How many hectopascals of sea-level pressure one meter of altitude error stands for, at [altitudeMeters]: the
     * factor between an altitude uncertainty and the uncertainty of the calibration.
     */
    fun seaLevelHpaPerMeter(
        seaLevelHpa: Double,
        altitudeMeters: Double,
    ): Double = seaLevelHpa * EXPONENT / (HEIGHT_SCALE - altitudeMeters)
}
