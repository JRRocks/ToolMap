package fr.toolmap.core.track

import kotlin.math.abs

/** One point of the profile graph. [index] is the point of [TrackPath.points] it stands for. */
data class ProfileSample(
    val distanceMeters: Double,
    val elevationMeters: Double,
    /** Slope in percent over [ProfileOptions.gradeWindowMeters] around the sample; positive going up. */
    val gradePercent: Double,
    val index: Int,
)

/** Colour bands of the slope, from the steepness only (the sign is shown by the profile itself). */
enum class GradeBand {
    FLAT,
    GENTLE,
    MODERATE,
    STEEP,
    VERY_STEEP,
    ;

    companion object {
        fun of(gradePercent: Double): GradeBand {
            val g = abs(gradePercent)
            return when {
                g < FLAT_BELOW -> FLAT
                g < GENTLE_BELOW -> GENTLE
                g < MODERATE_BELOW -> MODERATE
                g < STEEP_BELOW -> STEEP
                else -> VERY_STEEP
            }
        }

        private const val FLAT_BELOW = 3.0
        private const val GENTLE_BELOW = 8.0
        private const val MODERATE_BELOW = 15.0
        private const val STEEP_BELOW = 25.0
    }
}

data class ProfileOptions(
    /** At most this many samples are produced; a 50 000-point track is drawn from a few hundred. */
    val maxSamples: Int = 600,
    val medianWindow: Int = 5,
    /** Slopes are measured over about this length: shorter and GPS noise makes them meaningless. */
    val gradeWindowMeters: Double = 60.0,
)

/** What the altitude graph needs, built from the points that have an elevation. */
class ElevationProfile(
    val samples: List<ProfileSample>,
    val totalDistanceMeters: Double,
) {
    val minElevation: Double? get() = samples.minOfOrNull { it.elevationMeters }
    val maxElevation: Double? get() = samples.maxOfOrNull { it.elevationMeters }

    /** The sample whose distance is the closest to [distanceMeters]; `null` for an empty profile. */
    fun sampleAt(distanceMeters: Double): ProfileSample? {
        if (samples.isEmpty()) return null
        var lo = 0
        var hi = samples.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (samples[mid].distanceMeters <= distanceMeters) lo = mid else hi = mid
        }
        val a = samples[lo]
        val b = samples[hi]
        return if (abs(distanceMeters - a.distanceMeters) <= abs(b.distanceMeters - distanceMeters)) a else b
    }

    companion object {
        /** `null` when fewer than two points have an elevation: there is nothing to draw. */
        fun build(
            path: TrackPath,
            options: ProfileOptions = ProfileOptions(),
        ): ElevationProfile? {
            val indices = path.points.indices.filter { path.points[it].ele != null }
            if (indices.size < 2) return null
            val ele =
                TrackPath.medianFilter(
                    DoubleArray(indices.size) {
                        path.points[indices[it]].ele!!
                    },
                    options.medianWindow,
                )
            val dist = DoubleArray(indices.size) { path.cumulative[indices[it]] }
            val grades = grades(dist, ele, options.gradeWindowMeters)
            val keep = pick(indices.size, options.maxSamples)
            val samples = keep.map { ProfileSample(dist[it], ele[it], grades[it], indices[it]) }
            return ElevationProfile(samples, path.length)
        }

        /** Slope at each sample: climb over the distance between the samples [window] meters apart around it. */
        internal fun grades(
            dist: DoubleArray,
            ele: DoubleArray,
            window: Double,
        ): DoubleArray {
            val out = DoubleArray(dist.size)
            var from = 0
            var to = 0
            for (i in dist.indices) {
                while (from < i && dist[i] - dist[from] > window / 2) from++
                if (to < i) to = i
                while (to < dist.size - 1 && dist[to] - dist[i] < window / 2) to++
                val run = dist[to] - dist[from]
                out[i] = if (run > 0.0) (ele[to] - ele[from]) / run * 100.0 else 0.0
            }
            return out
        }

        /** Evenly spread indices, first and last included. */
        internal fun pick(
            count: Int,
            max: Int,
        ): List<Int> {
            if (count <= max || max < 2) return List(count) { it }
            return List(max) { (it.toLong() * (count - 1) / (max - 1)).toInt() }.distinct()
        }
    }
}
