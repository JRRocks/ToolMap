package fr.toolmap.data.altitude

import fr.toolmap.core.altitude.Altitude
import fr.toolmap.core.altitude.AltitudeSelector
import fr.toolmap.core.altitude.AltitudeSource
import fr.toolmap.core.altitude.BaroConfig
import fr.toolmap.core.altitude.BarometricAltimeter
import fr.toolmap.core.altitude.ReferenceResult
import fr.toolmap.core.altitude.ReferenceSource
import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AltitudeMode
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.track.Geodesy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The device's barometer. */
interface PressureSource {
    /** Whether the device has a pressure sensor. */
    val available: Boolean

    /** The pressure in hPa, about once a second for as long as the flow is collected (the sensor is off otherwise). */
    fun pressures(): Flow<Double>
}

/** For a device without a barometer. */
object NoPressureSource : PressureSource {
    override val available: Boolean = false

    override fun pressures(): Flow<Double> = emptyFlow()
}

/** What the altitude boxes of the map show (the GPS box and the barometer box). */
data class AltitudeStatus(
    /** The altitude of the last position; `null` when nothing is known (no GPS altitude, barometer not calibrated). */
    val altitude: Altitude? = null,
    val mode: AltitudeMode = AltitudeMode.AUTO,
    val barometerAvailable: Boolean = false,
    val calibrated: Boolean = false,
    val seaLevelHpa: Double? = null,
    /** Horizontal accuracy (68 %) of the last position, in meters, when the receiver says. */
    val accuracyMeters: Double? = null,
    /** The last pressure the barometer read, in hPa; `null` until it has read one. */
    val pressureHpa: Double? = null,
)

/**
 * The altitude of every position the app uses (feature P9.5): the GPS's own, or the barometer's, as the settings say.
 * Positions go through [enrich], which also feeds the barometer's calibration with what is known about the altitude
 * (the GPS when it says it is above sea level, the elevation model at the position, the user's own value).
 *
 * The sensor runs only while something is [attach]ed (a position is being listened to) and the mode is not "GPS only".
 * Thread-safe: positions, pressure readings and terrain answers arrive on different threads.
 */
class AltitudeService(
    private val settings: StateFlow<AppSettings>,
    private val pressure: PressureSource,
    private val terrain: TerrainElevation,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    config: BaroConfig = BaroConfig(),
    private val geoid: GeoidModel = GeoidModel.NONE,
) {
    private val lock = Any()
    private val altimeter = BarometricAltimeter(config)
    private var users = 0
    private var sensorJob: Job? = null

    private var lookup: Job? = null
    private var lookupRetryAtMillis = 0L
    private var lastLookup: Lookup? = null
    private var lastTerrainReferenceMillis: Long? = null
    private var lastFix: Fix? = null
    private var lastPressure: Double? = null

    private val published = MutableStateFlow(AltitudeStatus(barometerAvailable = pressure.available))

    val status: StateFlow<AltitudeStatus> = published.asStateFlow()

    private class Lookup(
        val millis: Long,
        val lat: Double,
        val lon: Double,
    )

    /** Something now listens to positions: starts the sensor (when the mode wants it). Pair with [detach]. */
    fun attach() {
        synchronized(lock) {
            users++
            if (users == 1) sensorJob = scope.launch { runSensor() }
        }
    }

    fun detach() {
        synchronized(lock) {
            if (users == 0) return
            users--
            if (users == 0) {
                sensorJob?.cancel()
                sensorJob = null
            }
        }
    }

    private suspend fun runSensor() {
        settings
            .map { it.altitude != AltitudeMode.GPS }
            .distinctUntilChanged()
            .collectLatest { wanted ->
                if (wanted && pressure.available) {
                    pressure.pressures().collect { hpa ->
                        synchronized(lock) {
                            altimeter.onPressure(hpa, clock())
                            lastPressure = hpa
                        }
                    }
                }
            }
    }

    /** The position with the altitude the settings ask for (`null` when there is none to give). */
    fun enrich(received: Fix): Fix {
        val fix = withGeoidHeight(received)
        val mode = effectiveMode()
        if (mode == AltitudeMode.GPS) {
            synchronized(lock) { lastFix = fix }
            published.value =
                AltitudeStatus(
                    altitude = fix.ele?.let { Altitude(it, AltitudeSource.GPS, fix.verticalAccuracyMeters) },
                    mode = settings.value.altitude,
                    barometerAvailable = pressure.available,
                    accuracyMeters = fix.accuracyMeters,
                )
            return fix
        }
        val now = clock()
        synchronized(lock) {
            lastFix = fix
            feedFromGps(fix, now)
        }
        maybeLookUpTerrain(fix, now)
        return publish(fix, now)
    }

    /**
     * « Ajustement EGM96 » (P11.8): the GNSS gives the height above the ellipsoid. Subtracting the geoid undulation
     * gives the height above the sea level, as the maps and the barometer do. Left as it is when the receiver already
     * gives the sea level, when the option is off, or when there is no geoid model.
     */
    private fun withGeoidHeight(fix: Fix): Fix {
        val ele = fix.ele ?: return fix
        if (fix.eleIsMsl || !settings.value.positioning.egm96) return fix
        val undulation = geoid.undulation(fix.lat, fix.lon) ?: return fix
        return fix.copy(ele = ele - undulation, eleIsMsl = true)
    }

    /** Calibrates the barometer on an altitude the user knows. `false` when it cannot (no current pressure reading). */
    fun calibrateManually(altitudeMeters: Double): Boolean {
        val now = clock()
        val result =
            synchronized(lock) {
                altimeter.onReference(altitudeMeters, MANUAL_SIGMA_METERS, now, ReferenceSource.MANUAL)
            }
        if (result != ReferenceResult.ACCEPTED) return false
        val fix = synchronized(lock) { lastFix }
        if (fix != null) publish(fix, now)
        return true
    }

    private fun effectiveMode(): AltitudeMode = if (pressure.available) settings.value.altitude else AltitudeMode.GPS

    private fun publish(
        fix: Fix,
        now: Long,
    ): Fix {
        val mode = effectiveMode()
        val (reading, calibrated, seaLevel) =
            synchronized(lock) { Triple(altimeter.reading(now), altimeter.calibrated, altimeter.seaLevelHpa) }
        val chosen = AltitudeSelector.choose(mode, fix.ele, fix.verticalAccuracyMeters, reading)
        val hpa = synchronized(lock) { lastPressure }
        published.value =
            AltitudeStatus(
                altitude = chosen,
                mode = settings.value.altitude,
                barometerAvailable = pressure.available,
                calibrated = calibrated,
                seaLevelHpa = seaLevel,
                accuracyMeters = fix.accuracyMeters,
                pressureHpa = hpa,
            )
        return fix.copy(
            ele = chosen?.meters,
            verticalAccuracyMeters = chosen?.uncertaintyMeters,
            eleIsMsl = chosen != null && (chosen.source == AltitudeSource.BAROMETER || fix.eleIsMsl),
        )
    }

    /** Caller holds [lock]. */
    private fun feedFromGps(
        fix: Fix,
        now: Long,
    ) {
        val ele = fix.ele ?: return
        if (fix.accuracyMeters?.let { it > MAX_REFERENCE_ACCURACY_METERS } == true) return
        if (fix.eleIsMsl) {
            val sigma = maxOf(fix.verticalAccuracyMeters ?: DEFAULT_GPS_SIGMA_METERS, MIN_GPS_SIGMA_METERS)
            altimeter.onReference(ele, sigma, now, ReferenceSource.GPS_MSL)
        } else {
            // The receiver may give the height above the ellipsoid, up to ~100 m off the sea level: only a vague hint,
            // and not at all while the elevation model has calibrated recently.
            val terrainAt = lastTerrainReferenceMillis
            if (terrainAt == null || now - terrainAt > TERRAIN_FRESH_MILLIS) {
                altimeter.onReference(ele, UNKNOWN_DATUM_SIGMA_METERS, now, ReferenceSource.GPS_UNKNOWN_DATUM)
            }
        }
    }

    private fun maybeLookUpTerrain(
        fix: Fix,
        now: Long,
    ) {
        if (fix.accuracyMeters?.let { it <= MAX_REFERENCE_ACCURACY_METERS } != true) return
        synchronized(lock) {
            if (lookup?.isActive == true || now < lookupRetryAtMillis) return
            val last = lastLookup
            if (last != null &&
                now - last.millis < TERRAIN_REFRESH_MILLIS &&
                Geodesy.distance(last.lat, last.lon, fix.lat, fix.lon) < TERRAIN_REFRESH_METERS
            ) {
                return
            }
            lookup = scope.launch { lookUpTerrain(fix.lat, fix.lon) }
        }
    }

    private suspend fun lookUpTerrain(
        lat: Double,
        lon: Double,
    ) {
        val ele =
            try {
                terrain.at(lat, lon)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        val now = clock()
        synchronized(lock) {
            if (ele == null) {
                lookupRetryAtMillis = now + TERRAIN_RETRY_MILLIS
                return
            }
            when (altimeter.onReference(ele, TERRAIN_SIGMA_METERS, now, ReferenceSource.TERRAIN)) {
                ReferenceResult.ACCEPTED -> {
                    lastLookup = Lookup(now, lat, lon)
                    lastTerrainReferenceMillis = now
                }

                ReferenceResult.NO_PRESSURE -> {
                    // The sensor has not answered yet: ask again soon, without waiting for the usual refresh.
                    lookupRetryAtMillis = now + NO_PRESSURE_RETRY_MILLIS
                }

                else -> {
                    lastLookup = Lookup(now, lat, lon)
                }
            }
        }
    }

    private companion object {
        /** Positions less precise than this say little about the altitude either. */
        const val MAX_REFERENCE_ACCURACY_METERS = 50.0
        const val DEFAULT_GPS_SIGMA_METERS = 15.0
        const val MIN_GPS_SIGMA_METERS = 8.0
        const val UNKNOWN_DATUM_SIGMA_METERS = 60.0

        /** The Terrarium model is a few meters off in the open and tens of meters off on cliffs. */
        const val TERRAIN_SIGMA_METERS = 20.0
        const val MANUAL_SIGMA_METERS = 2.0
        const val TERRAIN_REFRESH_MILLIS = 5 * 60_000L
        const val TERRAIN_REFRESH_METERS = 500.0
        const val TERRAIN_RETRY_MILLIS = 60_000L
        const val NO_PRESSURE_RETRY_MILLIS = 10_000L
        const val TERRAIN_FRESH_MILLIS = 10 * 60_000L
    }
}

/** The geoid undulation at a position, in meters: the height of the geoid above the ellipsoid (EGM96). */
fun interface GeoidModel {
    /** `null` when the model does not cover the position or cannot be read. */
    fun undulation(
        lat: Double,
        lon: Double,
    ): Double?

    companion object {
        /** No geoid: the altitudes stay as the receiver gives them. */
        val NONE = GeoidModel { _, _ -> null }
    }
}
