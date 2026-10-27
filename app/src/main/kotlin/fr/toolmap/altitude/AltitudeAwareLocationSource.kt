package fr.toolmap.altitude

import fr.toolmap.core.record.Fix
import fr.toolmap.data.altitude.AltitudeService
import fr.toolmap.data.altitude.AltitudeStatus
import fr.toolmap.record.LocationSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * The positions of [upstream] with the altitude the settings ask for (GPS or barometer), so that the recorder, the
 * navigation, the sharing and the alerts all see the same altitude. The barometer is on while a flow is collected.
 */
class AltitudeAwareLocationSource(
    private val upstream: LocationSource,
    private val altitude: AltitudeService,
) : LocationSource {
    override fun fixes(
        minIntervalMillis: Long,
        minDistanceMeters: Float,
    ): Flow<Fix> =
        flow {
            altitude.attach()
            try {
                upstream.fixes(minIntervalMillis, minDistanceMeters).map { altitude.enrich(it) }.collect { emit(it) }
            } finally {
                altitude.detach()
            }
        }
}

/** What the map screen needs of the altitude: the status for the chip, a way to follow the position, a calibration. */
class AltitudeController(
    private val service: AltitudeService,
    private val location: LocationSource,
) {
    val status: StateFlow<AltitudeStatus> = service.status

    /**
     * Follows the position for as long as the caller lives, so that [status] stays current (the positions themselves
     * go through [LocationSource], which is what feeds the status). A GPS that cannot start is not an error here.
     */
    suspend fun watch() {
        try {
            location.fixes(WATCH_INTERVAL_MILLIS, 0f).catch { }.collect { }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No permission or no GPS: the chip simply stays empty.
        }
    }

    /** `true` when the barometer now reads [altitudeMeters] here. */
    fun calibrate(altitudeMeters: Double): Boolean = service.calibrateManually(altitudeMeters)

    private companion object {
        const val WATCH_INTERVAL_MILLIS = 2_000L
    }
}
