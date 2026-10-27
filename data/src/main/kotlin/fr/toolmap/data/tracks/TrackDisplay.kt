package fr.toolmap.data.tracks

import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.print.PrintTrack
import fr.toolmap.core.print.PrintWaypoint
import fr.toolmap.core.route.RouteMarkers
import fr.toolmap.core.route.RoutePlanGpx
import fr.toolmap.core.track.LineFeature
import fr.toolmap.core.track.PathSimplifier
import fr.toolmap.core.track.PointFeature
import fr.toolmap.core.track.TrackGeoJson
import fr.toolmap.core.track.TrackJson
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.WaypointSymbols
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.util.concurrent.atomic.AtomicInteger

/** What the map layers of the library are fed with. */
class DisplayData(
    val linesGeoJson: String,
    val pointsGeoJson: String,
    val lineCount: Int,
    val pointCount: Int,
    /** Ids of the waypoint icons the points use: the map loads those glyphs only. */
    val pointIcons: Set<String> = emptySet(),
)

/** The visible tracks, routes and waypoints as the print draws them. */
class PrintItems(
    val tracks: List<PrintTrack>,
    val waypoints: List<PrintWaypoint>,
)

/**
 * Builds the GeoJSON of the visible tracks, routes and waypoints on a background thread. Decoding and simplifying a
 * track is the expensive part and only depends on its points, so the simplified geometry is kept (within a budget of
 * points) and showing, hiding or recolouring never decodes again.
 */
class TrackDisplay(
    private val repository: TrackRepository,
    private val toleranceMeters: Double = DEFAULT_TOLERANCE_METERS,
) {
    /**
     * The simplified geometry of a track as of one revision. Finished segments are simplified once; the last one is
     * kept as a simplified part ([frozen]) followed by the raw points not simplified yet ([tail]), so that a recording
     * adding a batch only simplifies the tail and never the whole track (P15.8).
     */
    private class Cached(
        val revision: Int,
        /** Points of the track this was built from (the length of the raw track). */
        val rawCount: Int,
        val done: List<List<TrackPoint>>,
        val frozen: List<TrackPoint>,
        val tail: List<TrackPoint>,
    ) {
        /** The segments to draw; a segment of one point is not a line. */
        val segments: List<List<TrackPoint>> =
            (if (frozen.isEmpty() && tail.isEmpty()) done else done + listOf(frozen + tail)).filter { it.size >= 2 }
        val points: Int = segments.sumOf { it.size }
    }

    private val cache = LinkedHashMap<Long, Cached>(16, 0.75f, true)
    private var cachedPoints = 0

    /** How many times a whole track was read and simplified (for the tests). */
    internal val fullLoads = AtomicInteger()

    val data: Flow<DisplayData> =
        repository.library
            .map { lib -> build(lib) }
            .flowOn(Dispatchers.Default)

    /** What is shown on the map right now, ready for the page: line widths in paper millimeters. */
    suspend fun printItems(): PrintItems {
        val lib = repository.library.first()
        val tracks = ArrayList<PrintTrack>()
        for (t in lib.tracks) {
            if (!lib.isShown(t.folderId, t.visible)) continue
            val segments = simplified(t.id).map { seg -> seg.map { LonLat(it.lon, it.lat) } }
            tracks += PrintTrack(t.name.orEmpty(), t.colorArgb, paperWidthMm(t.widthDp), segments)
        }
        for (r in lib.routes) {
            if (!lib.isShown(r.folderId, r.visible)) continue
            val points = TrackJson.decodeWaypoints(r.pointsJson).map { LonLat(it.lon, it.lat) }
            tracks += PrintTrack(r.name.orEmpty(), r.colorArgb, paperWidthMm(r.widthDp), listOf(points))
        }
        val places =
            lib.waypoints
                .filter { lib.isShown(it.folderId, it.visible) }
                .map {
                    val icon = WaypointSymbols.iconOf(it.sym)
                    PrintWaypoint(it.name, it.lat, it.lon, icon.colorArgb, icon.id)
                }
        return PrintItems(tracks, places)
    }

    private fun paperWidthMm(widthDp: Float): Double =
        (widthDp * MM_PER_DP).toDouble().coerceIn(MIN_PAPER_WIDTH_MM, MAX_PAPER_WIDTH_MM)

    private suspend fun build(lib: TrackLibrary): DisplayData {
        val lines = ArrayList<LineFeature>()
        for (t in lib.tracks) {
            if (!lib.isShown(t.folderId, t.visible)) continue
            val segments = simplified(t.id)
            lines += LineFeature(t.id, t.colorArgb, t.widthDp, t.dashed, segments, t.arrows)
        }
        val places = ArrayList<PointFeature>()
        for (r in lib.routes) {
            if (!lib.isShown(r.folderId, r.visible)) continue
            val points = TrackJson.decodeWaypoints(r.pointsJson).map { TrackPoint(it.lat, it.lon, it.ele) }
            lines += LineFeature(-r.id, r.colorArgb, r.widthDp, r.dashed, listOf(points), r.arrows)
            // A computed route shows its start, its end and its named stops (P19).
            val extras = r.extrasJson
            if (extras != null && RoutePlanGpx.NAMESPACE in extras) {
                RoutePlanGpx.planOf(points, TrackJson.decodeExtras(extras))?.let {
                    places +=
                        RouteMarkers.saved(-r.id, it)
                }
            }
        }
        lib.waypoints
            .filter { lib.isShown(it.folderId, it.visible) }
            .mapTo(places) { PointFeature(it.id, it.name, it.lat, it.lon, it.sym) }
        return DisplayData(
            linesGeoJson = TrackGeoJson.lines(lines, toleranceMeters = 0.0),
            pointsGeoJson = TrackGeoJson.points(places),
            lineCount = lines.size,
            pointCount = places.size,
            pointIcons = places.mapTo(HashSet()) { it.iconId ?: WaypointSymbols.iconOf(it.sym).id },
        )
    }

    private suspend fun simplified(trackId: Long): List<List<TrackPoint>> {
        // The revision is read before the points: a write in between leaves an older number with newer points, which
        // only costs one more load the next time.
        val revision = repository.pointsRevision(trackId)
        val known = synchronized(cache) { cache[trackId] }
        if (known != null && known.revision == revision) return known.segments
        // A recording only adds points at the end: extend what is known instead of reading the track again.
        val extended = known?.let { extend(trackId, it) }
        if (extended != null) return store(trackId, extended).segments
        fullLoads.incrementAndGet()
        val track = repository.loadTrack(trackId) ?: return emptyList()
        val simplified = track.segments.map { PathSimplifier.simplify(it, toleranceMeters) }
        val loaded =
            Cached(
                revision = revision,
                rawCount = track.segments.sumOf { it.size },
                done = simplified.dropLast(1),
                frozen = simplified.lastOrNull().orEmpty(),
                tail = emptyList(),
            )
        return store(trackId, loaded).segments
    }

    private fun store(
        trackId: Long,
        entry: Cached,
    ): Cached {
        synchronized(cache) {
            cache.remove(trackId)?.let { cachedPoints -= it.points }
            cache[trackId] = entry
            cachedPoints += entry.points
            val iterator = cache.entries.iterator()
            while (cachedPoints > CACHE_POINT_BUDGET && cache.size > 1 && iterator.hasNext()) {
                val oldest = iterator.next()
                if (oldest.key == trackId) continue
                cachedPoints -= oldest.value.points
                iterator.remove()
            }
        }
        return entry
    }

    /** [known] brought up to date with the batches the recorder appended since, or `null` if it cannot be. */
    private fun extend(
        trackId: Long,
        known: Cached,
    ): Cached? {
        val appended = repository.appendedSince(trackId, known.revision) ?: return null
        var done = known.done
        var frozen = known.frozen
        var tail = known.tail
        var raw = known.rawCount
        for (batch in appended.batches) {
            // A batch the track already held when it was read (the revision is bumped after the write) is skipped.
            if (batch.endCount <= raw) continue
            if (batch.endCount - batch.points.size != raw) return null
            if (batch.startsSegment) {
                val closed = settle(frozen, tail)
                if (closed.isNotEmpty()) done = done + listOf(closed)
                frozen = emptyList()
                tail = batch.points
            } else {
                tail = tail + batch.points
            }
            raw = batch.endCount
            if (tail.size >= TAIL_POINTS) {
                frozen = settle(frozen, tail)
                tail = emptyList()
            }
        }
        return Cached(appended.revision, raw, done, frozen, tail)
    }

    /**
     * The simplified [frozen] part followed by the simplification of [tail], which starts at the last frozen point so
     * that the line stays in one piece. The result stays within the tolerance of the raw points.
     */
    private fun settle(
        frozen: List<TrackPoint>,
        tail: List<TrackPoint>,
    ): List<TrackPoint> {
        if (tail.isEmpty()) return frozen
        val anchor = frozen.lastOrNull() ?: return PathSimplifier.simplify(tail, toleranceMeters)
        return frozen + PathSimplifier.simplify(listOf(anchor) + tail, toleranceMeters).drop(1)
    }

    companion object {
        const val DEFAULT_TOLERANCE_METERS = 1.0
        private const val MM_PER_DP = 25.4f / 160f
        private const val MIN_PAPER_WIDTH_MM = 0.35
        private const val MAX_PAPER_WIDTH_MM = 1.5
        private const val CACHE_POINT_BUDGET = 400_000

        /** Raw points of a recording kept before they are simplified into the line (P15.8). */
        private const val TAIL_POINTS = 256
    }
}
