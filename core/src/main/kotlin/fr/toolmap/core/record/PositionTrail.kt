package fr.toolmap.core.record

import fr.toolmap.core.track.Geodesy

/**
 * The last positions of the device, drawn as a trail behind the position marker (« Display previous locations »).
 * Bounded in number and in age, and a new point is kept only once the device has moved a few metres, so that a
 * motionless phone does not fill it.
 */
class PositionTrail(
    private val maxPoints: Int = DEFAULT_MAX_POINTS,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val minStepMeters: Double = DEFAULT_MIN_STEP_METERS,
) {
    data class Sample(
        val lat: Double,
        val lon: Double,
        val timeMillis: Long,
    )

    private val samples = ArrayDeque<Sample>()

    fun add(
        lat: Double,
        lon: Double,
        timeMillis: Long,
    ) {
        prune(timeMillis)
        val last = samples.lastOrNull()
        if (last != null && Geodesy.distance(last.lat, last.lon, lat, lon) < minStepMeters) return
        samples.addLast(Sample(lat, lon, timeMillis))
        while (samples.size > maxPoints) samples.removeFirst()
    }

    /** The samples still young enough at [nowMillis], oldest first. */
    fun points(nowMillis: Long): List<Sample> {
        prune(nowMillis)
        return samples.toList()
    }

    fun clear() = samples.clear()

    private fun prune(nowMillis: Long) {
        while (samples.isNotEmpty() && nowMillis - samples.first().timeMillis > maxAgeMillis) samples.removeFirst()
    }

    companion object {
        const val DEFAULT_MAX_POINTS = 500
        const val DEFAULT_MAX_AGE_MILLIS = 30 * 60_000L
        const val DEFAULT_MIN_STEP_METERS = 3.0
    }
}
