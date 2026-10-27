package fr.toolmap.core.record

import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStatsCalculator

/** A position as the phone's GPS reports it. */
data class Fix(
    val lat: Double,
    val lon: Double,
    val ele: Double? = null,
    val timeMillis: Long,
    /** Horizontal accuracy (68 % radius) in meters, when known. */
    val accuracyMeters: Double? = null,
    val speedMps: Double? = null,
    /** Accuracy of [ele] in meters, when the receiver says. */
    val verticalAccuracyMeters: Double? = null,
    /** Whether [ele] is known to be above mean sea level; `false` means the receiver may report the ellipsoid height. */
    val eleIsMsl: Boolean = false,
)

/** What the recorder keeps and what it throws away. The defaults suit walking; a bike wants a larger distance. */
data class RecorderSettings(
    /** Fixes closer in time than this to the last kept point are skipped. */
    val minIntervalMillis: Long = 2_000,
    /** Fixes closer in space than this to the last kept point are skipped (a stop does not draw a knot). */
    val minDistanceMeters: Double = 5.0,
    /** Fixes less precise than this are skipped. */
    val maxAccuracyMeters: Double = 50.0,
    /** A silence this long (tunnel, device sleeping) starts a new segment instead of a straight line across it. */
    val gapNewSegmentMillis: Long = 300_000,
    /** A jump faster than this is a bad fix, not movement. */
    val maxSpeedMps: Double = 100.0,
    /** Below this speed an interval counts as a stop. */
    val movingSpeedMps: Double = 0.3,
)

enum class RecorderState { IDLE, RECORDING, PAUSED }

enum class RejectReason { NOT_RECORDING, ACCURACY, TIME_BACKWARDS, TOO_SOON, TOO_CLOSE, JUMP }

/** What [RecorderCore.onFix] decided. */
sealed interface FixDecision {
    /** [newSegment]: the point starts a segment (first of the recording, after a pause, after a gap or a bad run). */
    data class Accepted(
        val point: TrackPoint,
        val newSegment: Boolean,
    ) : FixDecision

    data class Rejected(
        val reason: RejectReason,
    ) : FixDecision
}

/** Figures shown while recording. */
data class LiveStats(
    val pointCount: Int = 0,
    val segmentCount: Int = 0,
    val distanceMeters: Double = 0.0,
    /** Time spent recording: pauses are not counted. */
    val elapsedMillis: Long = 0,
    val movingMillis: Long = 0,
    val ascentMeters: Double = 0.0,
    val descentMeters: Double = 0.0,
    val currentSpeedMps: Double? = null,
    val minEleMeters: Double? = null,
    val maxEleMeters: Double? = null,
    val lastFix: Fix? = null,
)

/**
 * The decisions of the GPS recorder, free of Android: which fixes become track points, where segments start, and the
 * live figures. The service feeds it fixes and stores what it accepts; this class does not touch storage or clocks.
 */
class RecorderCore(
    val settings: RecorderSettings = RecorderSettings(),
) {
    var state: RecorderState = RecorderState.IDLE
        private set

    private var activeSince: Long? = null
    private var activeBefore = 0L
    private var last: TrackPoint? = null
    private var awaitingSegment = true
    private var badRun = 0
    private var points = 0
    private var segments = 0
    private var distance = 0.0
    private var moving = 0L
    private var minEle: Double? = null
    private var maxEle: Double? = null
    private var lastFix: Fix? = null
    private var reportedSpeed: Double? = null
    private val recent = ArrayDeque<TrackPoint>()
    private val eleWindow = ArrayDeque<Double>()
    private val swings = TrackStatsCalculator.Hysteresis(HYSTERESIS_METERS)

    fun start(nowMillis: Long) {
        check(state == RecorderState.IDLE) { "already started" }
        state = RecorderState.RECORDING
        activeSince = nowMillis
    }

    fun pause(nowMillis: Long) {
        if (state != RecorderState.RECORDING) return
        activeBefore += nowMillis - (activeSince ?: nowMillis)
        activeSince = null
        state = RecorderState.PAUSED
    }

    /** After a pause the next kept fix opens a new segment: the way between is not part of the track. */
    fun resume(nowMillis: Long) {
        if (state != RecorderState.PAUSED) return
        activeSince = nowMillis
        awaitingSegment = true
        state = RecorderState.RECORDING
    }

    fun stop(nowMillis: Long) {
        pause(nowMillis)
        state = RecorderState.IDLE
    }

    /**
     * Rebuilds the figures from a track already stored (recovery after the process died). The recorder ends up
     * paused, so that the user chooses whether to carry on; the next fix after [resume] opens a new segment.
     */
    fun replay(
        stored: List<List<TrackPoint>>,
        activeMillis: Long,
    ) {
        check(state == RecorderState.IDLE) { "replay on a running recorder" }
        for (segment in stored) {
            var first = true
            for (point in segment) {
                keep(point, newSegment = first, speed = null)
                first = false
            }
        }
        activeBefore = activeMillis
        state = RecorderState.PAUSED
    }

    fun onFix(fix: Fix): FixDecision {
        if (state != RecorderState.RECORDING) return FixDecision.Rejected(RejectReason.NOT_RECORDING)
        if (fix.accuracyMeters != null && fix.accuracyMeters > settings.maxAccuracyMeters) {
            return FixDecision.Rejected(RejectReason.ACCURACY)
        }
        val candidate = TrackPoint(fix.lat, fix.lon, fix.ele, fix.timeMillis)
        val previous = last
        if (previous == null || awaitingSegment) {
            keep(candidate, newSegment = true, speed = fix.speedMps)
            lastFix = fix
            return FixDecision.Accepted(candidate, newSegment = true)
        }
        val dt = fix.timeMillis - (previous.timeMillis ?: fix.timeMillis)
        if (dt < 0) return FixDecision.Rejected(RejectReason.TIME_BACKWARDS)
        if (dt < settings.minIntervalMillis) return FixDecision.Rejected(RejectReason.TOO_SOON)
        val d = Geodesy.distance(previous, candidate)
        if (dt > 0 && d / (dt / 1000.0) > settings.maxSpeedMps) {
            // A bad fix is dropped, but a run of them means the earlier points were the wrong ones: follow the new place.
            badRun++
            if (badRun < MAX_BAD_RUN) return FixDecision.Rejected(RejectReason.JUMP)
            keep(candidate, newSegment = true, speed = fix.speedMps)
            lastFix = fix
            return FixDecision.Accepted(candidate, newSegment = true)
        }
        if (d < settings.minDistanceMeters) return FixDecision.Rejected(RejectReason.TOO_CLOSE)
        val newSegment = dt > settings.gapNewSegmentMillis
        keep(candidate, newSegment, fix.speedMps)
        lastFix = fix
        return FixDecision.Accepted(candidate, newSegment)
    }

    fun stats(nowMillis: Long): LiveStats =
        LiveStats(
            pointCount = points,
            segmentCount = segments,
            distanceMeters = distance,
            elapsedMillis = activeBefore + (activeSince?.let { nowMillis - it } ?: 0L),
            movingMillis = moving,
            ascentMeters = swings.ascent,
            descentMeters = swings.descent,
            currentSpeedMps = currentSpeed(),
            minEleMeters = minEle,
            maxEleMeters = maxEle,
            lastFix = lastFix,
        )

    private fun currentSpeed(): Double? {
        reportedSpeed?.let { return it }
        val newest = recent.lastOrNull() ?: return null
        val tNew = newest.timeMillis ?: return null
        val older = recent.firstOrNull { (tNew - (it.timeMillis ?: tNew)) in 1..SPEED_WINDOW_MILLIS } ?: return null
        val dt = tNew - (older.timeMillis ?: tNew)
        return Geodesy.distance(older, newest) / (dt / 1000.0)
    }

    private fun keep(
        point: TrackPoint,
        newSegment: Boolean,
        speed: Double?,
    ) {
        val previous = last
        badRun = 0
        awaitingSegment = false
        if (newSegment) {
            segments++
            recent.clear()
            eleWindow.clear()
        } else if (previous != null) {
            val d = Geodesy.distance(previous, point)
            distance += d
            val t0 = previous.timeMillis
            val t1 = point.timeMillis
            if (t0 != null && t1 != null && t1 > t0 && d / ((t1 - t0) / 1000.0) >= settings.movingSpeedMps) {
                moving += t1 - t0
            }
        }
        points++
        point.ele?.let { addElevation(it) }
        last = point
        reportedSpeed = speed
        recent.addLast(point)
        while (recent.size > RECENT_POINTS) recent.removeFirst()
    }

    /** Median of the last few altitudes, so that one wild reading does not count as climb. */
    private fun addElevation(ele: Double) {
        eleWindow.addLast(ele)
        while (eleWindow.size > ELEVATION_WINDOW) eleWindow.removeFirst()
        val sorted = eleWindow.sorted()
        val median = sorted[(sorted.size - 1) / 2]
        swings.add(median)
        minEle = minOf(minEle ?: median, median)
        maxEle = maxOf(maxEle ?: median, median)
    }

    companion object {
        const val HYSTERESIS_METERS = 5.0
        private const val MAX_BAD_RUN = 4
        private const val SPEED_WINDOW_MILLIS = 10_000L
        private const val RECENT_POINTS = 20
        private const val ELEVATION_WINDOW = 5
    }
}
