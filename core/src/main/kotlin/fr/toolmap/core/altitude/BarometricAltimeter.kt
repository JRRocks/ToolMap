package fr.toolmap.core.altitude

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/** Where a reference altitude, used to calibrate the barometer, comes from. */
enum class ReferenceSource {
    /** The elevation model at the position (Terrarium tiles): above mean sea level, a few tens of meters off at worst. */
    TERRAIN,

    /** The GPS, known to be above mean sea level (Android 14+ computes it). */
    GPS_MSL,

    /** The GPS on a device that may report the height above the ellipsoid (up to ±100 m away from sea level). */
    GPS_UNKNOWN_DATUM,

    /** The user typed it. */
    MANUAL,
}

/** What happened to a reference altitude. */
enum class ReferenceResult { ACCEPTED, THROTTLED, REJECTED_OUTLIER, NO_PRESSURE, INVALID }

/** The barometer's altitude and how far off it may be. */
data class BaroReading(
    val altitudeMeters: Double,
    val uncertaintyMeters: Double,
    val pressureHpa: Double,
    val seaLevelHpa: Double,
)

/** The tuning of [BarometricAltimeter]; the defaults suit walking in changing weather. */
data class BaroConfig(
    /** How much the sea-level pressure may wander, in hPa over an hour (one standard deviation). */
    val driftHpaPerHour: Double = 1.0,
    /** Time constant of the pressure smoothing, in seconds. */
    val pressureTimeConstantSeconds: Double = 4.0,
    /** Two references from the same source closer in time than this are not independent: the later is dropped. */
    val minReferenceGapMillis: Long = 30_000L,
    /** A reference further than this many standard deviations from the estimate is an outlier. */
    val gateSigmas: Double = 4.0,
    /** After this many outliers in a row the estimate is the one that is wrong: start again from the reference. */
    val maxRejections: Int = 5,
    /** A pressure reading older than this is not current: no altitude from it. */
    val staleMillis: Long = 30_000L,
    /** What smoothed pressure noise adds to the uncertainty, in meters. */
    val noiseMeters: Double = 1.0,
)

/**
 * A barometric altimeter (feature P9.5) that calibrates itself. The pressure gives the altitude only against the
 * sea-level pressure of the moment, which changes with the weather by a few hectopascals a day (a hPa is about 8 m),
 * so that pressure is estimated continuously from reference altitudes (the elevation model, the GPS) with a
 * one-dimensional Kalman filter: each reference pulls the estimate by its weight, and the estimate's uncertainty
 * grows with time at the drift rate of the weather. Short-term changes of altitude (ascent, descent) are exact
 * to a meter or two whatever the calibration; the calibration only fixes the absolute value.
 *
 * Not thread-safe: the caller serialises the calls.
 */
class BarometricAltimeter(
    private val config: BaroConfig = BaroConfig(),
) {
    private var pressure: Double? = null
    private var pressureMillis = 0L

    private var seaLevel: Double? = null
    private var variance = 0.0
    private var filterMillis = 0L
    private var rejections = 0
    private val lastReference = HashMap<ReferenceSource, Long>()

    /** Whether there is a sea-level pressure estimate to read altitudes against. */
    val calibrated: Boolean get() = seaLevel != null

    /** The current sea-level pressure estimate in hPa, once calibrated. */
    val seaLevelHpa: Double? get() = seaLevel

    /** A pressure reading in hPa at [timeMillis]; implausible values are dropped. */
    fun onPressure(
        hpa: Double,
        timeMillis: Long,
    ) {
        if (!hpa.isFinite() || hpa < Atmosphere.MIN_PLAUSIBLE_HPA || hpa > Atmosphere.MAX_PLAUSIBLE_HPA) return
        val previous = pressure
        pressure =
            if (previous == null || timeMillis - pressureMillis > config.staleMillis) {
                hpa
            } else {
                val dt = ((timeMillis - pressureMillis) / MILLIS).coerceAtLeast(0.0)
                val alpha = 1.0 - exp(-dt / config.pressureTimeConstantSeconds)
                previous + alpha * (hpa - previous)
            }
        pressureMillis = timeMillis
    }

    /** Uses a known altitude to correct the sea-level pressure estimate. */
    fun onReference(
        altitudeMeters: Double,
        sigmaMeters: Double,
        timeMillis: Long,
        source: ReferenceSource,
    ): ReferenceResult {
        val p = currentPressure(timeMillis) ?: return ReferenceResult.NO_PRESSURE
        if (!altitudeMeters.isFinite() ||
            altitudeMeters < MIN_ALTITUDE ||
            altitudeMeters > MAX_ALTITUDE ||
            !sigmaMeters.isFinite() ||
            sigmaMeters <= 0.0
        ) {
            return ReferenceResult.INVALID
        }
        val last = lastReference[source]
        if (source != ReferenceSource.MANUAL && last != null && abs(timeMillis - last) < config.minReferenceGapMillis) {
            return ReferenceResult.THROTTLED
        }
        val measured = Atmosphere.seaLevelHpa(p, altitudeMeters)
        val noise = sigmaMeters * Atmosphere.seaLevelHpaPerMeter(measured, altitudeMeters)
        val measurementVariance = noise * noise
        val estimate = seaLevel
        if (estimate == null || source == ReferenceSource.MANUAL) {
            start(measured, measurementVariance, timeMillis)
        } else {
            val predicted = predictedVariance(timeMillis)
            val innovation = measured - estimate
            if (abs(innovation) > config.gateSigmas * sqrt(predicted + measurementVariance)) {
                rejections++
                if (rejections < config.maxRejections) return ReferenceResult.REJECTED_OUTLIER
                start(measured, measurementVariance, timeMillis)
            } else {
                val gain = predicted / (predicted + measurementVariance)
                seaLevel = estimate + gain * innovation
                variance = (1.0 - gain) * predicted
                filterMillis = timeMillis
                rejections = 0
            }
        }
        lastReference[source] = timeMillis
        return ReferenceResult.ACCEPTED
    }

    /** The altitude at [timeMillis]; `null` before calibration or when the pressure reading is not current. */
    fun reading(timeMillis: Long): BaroReading? {
        val p = currentPressure(timeMillis) ?: return null
        val estimate = seaLevel ?: return null
        val altitude = Atmosphere.altitudeMeters(p, estimate)
        val hpaPerMeter = Atmosphere.seaLevelHpaPerMeter(estimate, altitude)
        val uncertainty = sqrt(predictedVariance(timeMillis)) / hpaPerMeter + config.noiseMeters
        return BaroReading(altitude, uncertainty, p, estimate)
    }

    /** Forgets the calibration (and the pressure), as after a long gap or a change of mode. */
    fun reset() {
        pressure = null
        seaLevel = null
        variance = 0.0
        rejections = 0
        lastReference.clear()
    }

    private fun start(
        seaLevelHpa: Double,
        varianceHpa2: Double,
        timeMillis: Long,
    ) {
        seaLevel = seaLevelHpa
        variance = varianceHpa2
        filterMillis = timeMillis
        rejections = 0
    }

    private fun currentPressure(timeMillis: Long): Double? {
        val p = pressure ?: return null
        return if (abs(timeMillis - pressureMillis) > config.staleMillis) null else p
    }

    private fun predictedVariance(timeMillis: Long): Double {
        val seconds = ((timeMillis - filterMillis) / MILLIS).coerceAtLeast(0.0)
        val perSecond = config.driftHpaPerHour * config.driftHpaPerHour / SECONDS_PER_HOUR
        return variance + perSecond * seconds
    }

    private companion object {
        const val MILLIS = 1000.0
        const val SECONDS_PER_HOUR = 3600.0
        const val MIN_ALTITUDE = -500.0
        const val MAX_ALTITUDE = 9_000.0
    }
}
