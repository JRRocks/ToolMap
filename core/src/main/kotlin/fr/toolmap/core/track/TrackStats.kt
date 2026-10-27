package fr.toolmap.core.track

import kotlin.math.sqrt

/** Numbers describing a track. Elevations, durations and speeds are `null` when the points do not carry them. */
data class TrackStats(
    val pointCount: Int,
    val segmentCount: Int,
    val distance2dMeters: Double,
    /** 2D distance combined with the climb between points (smoothed elevations). */
    val distance3dMeters: Double,
    val ascentMeters: Double,
    val descentMeters: Double,
    val minEleMeters: Double?,
    val maxEleMeters: Double?,
    val startTimeMillis: Long?,
    val endTimeMillis: Long?,
    /** From the first to the last timed point, pauses included. */
    val totalDurationMillis: Long?,
    /** Only the intervals walked at more than [StatsOptions.movingSpeedMps]. */
    val movingDurationMillis: Long?,
    /** Distance over total time. */
    val averageSpeedMps: Double?,
    /** Distance over moving time. */
    val averageMovingSpeedMps: Double?,
    /**
     * Fastest stretch of at least [StatsOptions.maxSpeedWindowMillis], from the displacement between its two ends:
     * a few meters of GPS jitter at every fix does not inflate it.
     */
    val maxSpeedMps: Double?,
    /** Walking time by DIN 33466, from the 2D distance and the smoothed ascent and descent. */
    val estimatedWalkMillis: Long,
)

/** Tuning of [TrackStatsCalculator]. The defaults suit hiking with a phone's GPS. */
data class StatsOptions(
    /** Odd number of neighbours of the median filter applied to elevations before anything else. */
    val medianWindow: Int = 5,
    /** Elevation changes smaller than this are noise, not climb. */
    val hysteresisMeters: Double = 5.0,
    /** Below this speed an interval counts as a stop. */
    val movingSpeedMps: Double = 0.3,
    /** The maximum speed is measured over at least this long, so that GPS jitter does not set the record. */
    val maxSpeedWindowMillis: Long = 10_000,
)

object TrackStatsCalculator {
    /** DIN 33466: 4 km/h on the flat, 300 m/h up, 500 m/h down. */
    const val WALK_FLAT_METERS_PER_HOUR = 4000.0
    const val WALK_UP_METERS_PER_HOUR = 300.0
    const val WALK_DOWN_METERS_PER_HOUR = 500.0

    private const val MILLIS_PER_HOUR = 3_600_000.0

    fun compute(
        segments: List<List<TrackPoint>>,
        options: StatsOptions = StatsOptions(),
    ): TrackStats {
        val usable = segments.filter { it.isNotEmpty() }
        var d2 = 0.0
        var d3 = 0.0
        var ascent = 0.0
        var descent = 0.0
        var minEle: Double? = null
        var maxEle: Double? = null
        var moving = 0L
        var anyMovingData = false
        var maxSpeed: Double? = null
        for (segment in usable) {
            val smooth = smoothedElevations(segment, options.medianWindow)
            val swings = Hysteresis(options.hysteresisMeters)
            for (i in segment.indices) {
                smooth[i]?.let {
                    swings.add(it)
                    minEle = minOf(minEle ?: it, it)
                    maxEle = maxOf(maxEle ?: it, it)
                }
            }
            ascent += swings.ascent
            descent += swings.descent
            for (i in 1 until segment.size) {
                val flat = Geodesy.distance(segment[i - 1], segment[i])
                d2 += flat
                val e0 = smooth[i - 1]
                val e1 = smooth[i]
                d3 += if (e0 != null && e1 != null) sqrt(flat * flat + (e1 - e0) * (e1 - e0)) else flat
                val t0 = segment[i - 1].timeMillis
                val t1 = segment[i].timeMillis
                if (t0 != null && t1 != null && t1 > t0) {
                    anyMovingData = true
                    if (flat / ((t1 - t0) / 1000.0) >= options.movingSpeedMps) moving += t1 - t0
                }
            }
            maxSpeed =
                maxOf(maxSpeed ?: 0.0, windowedMaxSpeed(segment, options.maxSpeedWindowMillis) ?: 0.0)
        }
        val times = usable.flatten().mapNotNull { it.timeMillis }
        val start = times.firstOrNull()
        val end = times.lastOrNull()
        val total = if (start != null && end != null && end > start) end - start else null
        return TrackStats(
            pointCount = usable.sumOf { it.size },
            segmentCount = usable.size,
            distance2dMeters = d2,
            distance3dMeters = d3,
            ascentMeters = ascent,
            descentMeters = descent,
            minEleMeters = minEle,
            maxEleMeters = maxEle,
            startTimeMillis = start,
            endTimeMillis = end,
            totalDurationMillis = total,
            movingDurationMillis = if (anyMovingData) moving else null,
            averageSpeedMps = total?.let { d2 / (it / 1000.0) },
            averageMovingSpeedMps = if (anyMovingData && moving > 0) d2 / (moving / 1000.0) else null,
            maxSpeedMps = maxSpeed?.takeIf { it > 0.0 },
            estimatedWalkMillis = estimateWalkMillis(d2, ascent, descent),
        )
    }

    /** DIN 33466: the longer of the horizontal and vertical times plus half of the shorter one. */
    fun estimateWalkMillis(
        distanceMeters: Double,
        ascentMeters: Double,
        descentMeters: Double,
    ): Long {
        val horizontal = distanceMeters / WALK_FLAT_METERS_PER_HOUR
        val vertical = ascentMeters / WALK_UP_METERS_PER_HOUR + descentMeters / WALK_DOWN_METERS_PER_HOUR
        val hours = maxOf(horizontal, vertical) + minOf(horizontal, vertical) / 2
        return Math.round(hours * MILLIS_PER_HOUR)
    }

    /** Elevation of every point after the median filter, `null` where the point has none. */
    internal fun smoothedElevations(
        segment: List<TrackPoint>,
        window: Int,
    ): Array<Double?> {
        val withEle = segment.indices.filter { segment[it].ele != null }
        val filtered = TrackPath.medianFilter(DoubleArray(withEle.size) { segment[withEle[it]].ele!! }, window)
        val out = arrayOfNulls<Double>(segment.size)
        for ((k, i) in withEle.withIndex()) out[i] = filtered[k]
        return out
    }

    private fun windowedMaxSpeed(
        segment: List<TrackPoint>,
        windowMillis: Long,
    ): Double? {
        var best: Double? = null
        var j = 0
        for (i in segment.indices) {
            val ti = segment[i].timeMillis ?: continue
            if (j < i) j = i
            while (j < segment.size && (segment[j].timeMillis ?: Long.MIN_VALUE) - ti < windowMillis) j++
            if (j >= segment.size) break
            val tj = segment[j].timeMillis ?: continue
            val speed = Geodesy.distance(segment[i], segment[j]) / ((tj - ti) / 1000.0)
            if (best == null || speed > best) best = speed
        }
        return best
    }

    /**
     * Climb and descent that ignore wobbles smaller than a threshold: an up-or-down swing only counts once the
     * elevation has moved by the threshold from the last turning point, and then counts in full.
     */
    internal class Hysteresis(
        private val threshold: Double,
    ) {
        private var settledUp = 0.0
        private var settledDown = 0.0
        private var anchor: Double? = null
        private var candidate = 0.0
        private var direction = 0

        /** Climb so far, including the swing in progress. */
        val ascent: Double get() = settledUp + if (direction == 1) candidate - anchor!! else 0.0

        /** Descent so far, including the swing in progress. */
        val descent: Double get() = settledDown + if (direction == -1) anchor!! - candidate else 0.0

        fun add(value: Double) {
            val a = anchor
            if (a == null) {
                anchor = value
                candidate = value
                return
            }
            when (direction) {
                0 -> {
                    if (value - a >= threshold) {
                        direction = 1
                        candidate = value
                    } else if (a - value >= threshold) {
                        direction = -1
                        candidate = value
                    }
                }

                1 -> {
                    if (value > candidate) {
                        candidate = value
                    } else if (candidate - value >= threshold) {
                        settledUp += candidate - a
                        anchor = candidate
                        direction = -1
                        candidate = value
                    }
                }

                else -> {
                    if (value < candidate) {
                        candidate = value
                    } else if (value - candidate >= threshold) {
                        settledDown += a - candidate
                        anchor = candidate
                        direction = 1
                        candidate = value
                    }
                }
            }
        }
    }
}
