package fr.toolmap.core.alert

import fr.toolmap.core.record.Fix
import fr.toolmap.core.settings.AlertSettings
import fr.toolmap.core.track.Geodesy

/** A waypoint the user wants to be warned about when getting close to it. */
data class WatchedPlace(
    val id: Long,
    val name: String?,
    val lat: Double,
    val lon: Double,
    val radiusMeters: Double,
)

/** What the engine tells the app to raise. */
sealed interface AlertEvent {
    /** A watched place was entered. */
    data class Proximity(
        val place: WatchedPlace,
        val distanceMeters: Double,
    ) : AlertEvent

    /** The position has been further than the limit from the followed line for a while; repeats while it lasts. */
    data class OffTrack(
        val distanceMeters: Double,
        val limitMeters: Double,
    ) : AlertEvent

    /** The position came back within reach of the line after an [OffTrack]. */
    data object BackOnTrack : AlertEvent

    /** The end of the followed line was reached. */
    data object Arrived : AlertEvent
}

/** Where the walker stands on the followed line. */
data class FollowStatus(
    val offMeters: Double,
    val alongMeters: Double,
    val remainingMeters: Double,
    val totalMeters: Double,
    val offTrack: Boolean,
    val arrived: Boolean,
) {
    val fraction: Double get() = if (totalMeters <= 0.0) 0.0 else (alongMeters / totalMeters).coerceIn(0.0, 1.0)
}

/** What one position changed. */
data class AlertUpdate(
    val events: List<AlertEvent> = emptyList(),
    /** `null` while no line is followed or no usable position has come in yet. */
    val status: FollowStatus? = null,
)

/**
 * The rules of the alerts (feature P9.4), free of the platform: positions go in, events come out.
 *
 * - Fixes less precise than [MAX_ACCURACY_METERS] are ignored: they would make alerts jump in and out.
 * - **Proximity**: one event when a watched place is entered; it fires again only after the position went beyond
 *   [EXIT_FACTOR] times the radius (hysteresis against GPS jitter at the edge). A place that is already entered when
 *   it is first seen is entered silently: switching a watch on next to it is not a surprise to announce.
 * - **Off the line**: the limit must be exceeded, and be more than the fix's own accuracy, by [CONFIRM_FIXES] fixes in
 *   a row before the event fires; it repeats every [REPEAT_MILLIS] while it lasts and a [AlertEvent.BackOnTrack]
 *   follows once the position is within [BACK_FACTOR] times the limit.
 * - **Arrival**: once, when the end of the line is within [ARRIVE_METERS] after more than half of it was covered (so
 *   that a loop does not "arrive" at its own start).
 */
class AlertEngine(
    settings: AlertSettings = AlertSettings(),
) {
    private var settings = settings
    private var places: List<WatchedPlace> = emptyList()
    private val inside = HashSet<Long>()
    private val seen = HashSet<Long>()

    private var follower: TrackFollower? = null
    private var along: Double? = null
    private var furthest = 0.0
    private var beyondInARow = 0
    private var offTrack = false
    private var lastOffEventMillis = 0L
    private var arrivedSent = false
    private var lastStatus: FollowStatus? = null

    fun updateSettings(settings: AlertSettings) {
        this.settings = settings
    }

    /** The places to watch now; those no longer listed are forgotten, the others keep their state. */
    fun setWatched(list: List<WatchedPlace>) {
        places = list
        val ids = list.map { it.id }.toSet()
        inside.retainAll(ids)
        seen.retainAll(ids)
    }

    /** The line to follow, or `null` to stop following. Progress starts again. */
    fun setFollow(line: TrackFollower?) {
        follower = line?.takeIf { it.usable }
        along = null
        furthest = 0.0
        beyondInARow = 0
        offTrack = false
        lastOffEventMillis = 0L
        arrivedSent = false
        lastStatus = null
    }

    fun onFix(fix: Fix): AlertUpdate {
        val accuracy = fix.accuracyMeters
        if (accuracy != null && accuracy > MAX_ACCURACY_METERS) return AlertUpdate(status = lastStatus)
        val events = ArrayList<AlertEvent>()
        if (settings.proximityEnabled) checkProximity(fix, events) else inside.clear()
        val status = follow(fix, events)
        return AlertUpdate(events, status)
    }

    private fun checkProximity(
        fix: Fix,
        events: MutableList<AlertEvent>,
    ) {
        for (place in places) {
            val d = Geodesy.distance(fix.lat, fix.lon, place.lat, place.lon)
            val first = seen.add(place.id)
            if (place.id !in inside) {
                if (d <= place.radiusMeters) {
                    inside += place.id
                    if (!first) events += AlertEvent.Proximity(place, d)
                }
            } else if (d > place.radiusMeters * EXIT_FACTOR) {
                inside -= place.id
            }
        }
    }

    private fun follow(
        fix: Fix,
        events: MutableList<AlertEvent>,
    ): FollowStatus? {
        val line = follower ?: return null
        // Without progress yet the walk is taken to begin at the start, which settles a loop's start against its end.
        val projection = line.project(fix.lat, fix.lon, along ?: 0.0) ?: return null
        along = projection.alongMeters
        furthest = maxOf(furthest, projection.alongMeters)
        val remaining = (line.totalMeters - projection.alongMeters).coerceAtLeast(0.0)
        val limit = settings.offTrackMeters.toDouble()

        if (settings.offTrackEnabled) {
            val accuracy = fix.accuracyMeters ?: 0.0
            val beyond = projection.offMeters > limit && projection.offMeters > accuracy
            if (beyond) {
                beyondInARow++
                if (!offTrack && beyondInARow >= CONFIRM_FIXES) {
                    offTrack = true
                    lastOffEventMillis = fix.timeMillis
                    events += AlertEvent.OffTrack(projection.offMeters, limit)
                } else if (offTrack && fix.timeMillis - lastOffEventMillis >= REPEAT_MILLIS) {
                    lastOffEventMillis = fix.timeMillis
                    events += AlertEvent.OffTrack(projection.offMeters, limit)
                }
            } else {
                if (projection.offMeters <= limit) beyondInARow = 0
                if (offTrack && projection.offMeters <= limit * BACK_FACTOR) {
                    offTrack = false
                    beyondInARow = 0
                    events += AlertEvent.BackOnTrack
                }
            }
        } else {
            beyondInARow = 0
            offTrack = false
        }

        val reached =
            !arrivedSent &&
                furthest > line.totalMeters / 2 &&
                remaining <= ARRIVE_METERS &&
                projection.offMeters <= maxOf(limit, ARRIVE_METERS)
        if (reached) {
            arrivedSent = true
            events += AlertEvent.Arrived
        }

        val status =
            FollowStatus(
                offMeters = projection.offMeters,
                alongMeters = projection.alongMeters,
                remainingMeters = remaining,
                totalMeters = line.totalMeters,
                offTrack = offTrack,
                arrived = arrivedSent,
            )
        lastStatus = status
        return status
    }

    companion object {
        const val MAX_ACCURACY_METERS = 150.0
        const val EXIT_FACTOR = 1.25
        const val BACK_FACTOR = 0.8
        const val CONFIRM_FIXES = 3
        const val REPEAT_MILLIS = 60_000L
        const val ARRIVE_METERS = 30.0
    }
}
