package fr.toolmap.data.tracks

import androidx.room.withTransaction
import fr.toolmap.core.route.RoutePlanGpx
import fr.toolmap.core.track.Geodesy
import fr.toolmap.core.track.GpxLink
import fr.toolmap.core.track.PathStyle
import fr.toolmap.core.track.PointCodec
import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.Route
import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackJson
import fr.toolmap.core.track.TrackOps
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.TrackStats
import fr.toolmap.core.track.TrackStatsCalculator
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.elevation.CompletionResult
import fr.toolmap.data.elevation.ElevationCompleter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

/**
 * A batch of points the recorder added at the end of a track. [startsSegment] is true when the batch opens a segment,
 * [endCount] is the number of points the whole track holds once the batch is in.
 */
class AppendedPoints(
    val revision: Int,
    val points: List<TrackPoint>,
    val startsSegment: Boolean,
    val endCount: Int,
)

/** The batches that lead a track from an older revision to [revision]. */
class AppendedSince(
    val revision: Int,
    val batches: List<AppendedPoints>,
)

/** The library of tracks, routes and waypoints: import, read back, edit, export. */
class TrackRepository(
    private val db: TrackDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.dao()

    /** How many times the points of each track were rewritten or extended since the process started. */
    private val revisions = ConcurrentHashMap<Long, Int>()

    /**
     * Changes whenever the points of [trackId] change (recording, edit, reverse, simplify), so that what is derived
     * from them (the simplified line on the map) can tell it is out of date. Every write of points goes through this
     * repository, so a counter in memory is enough; it starts again at 0 with the process, as do the caches it guards.
     */
    fun pointsRevision(trackId: Long): Int = revisions[trackId] ?: 0

    private fun pointsChanged(trackId: Long) {
        revisions.merge(trackId, 1, Int::plus)
        // A rewrite is not an append: what the display kept about this track can no longer be extended.
        appendLogs.remove(trackId)
    }

    /** The last batches the recorder appended to each track, so that the display can extend its line (P15.8). */
    private val appendLogs = ConcurrentHashMap<Long, ArrayDeque<AppendedPoints>>()

    private fun pointsAppended(
        trackId: Long,
        points: List<TrackPoint>,
        startsSegment: Boolean,
        endCount: Int,
    ) {
        val revision = revisions.merge(trackId, 1, Int::plus) ?: return
        val log = appendLogs.getOrPut(trackId) { ArrayDeque() }
        synchronized(log) {
            log.addLast(AppendedPoints(revision, points, startsSegment, endCount))
            var held = log.sumOf { it.points.size }
            while (held > APPEND_LOG_POINTS && log.size > 1) held -= log.removeFirst().points.size
        }
    }

    /**
     * The batches appended to [trackId] since [revision] (as returned by [pointsRevision]) with the revision they lead
     * to, or `null` when the points changed in another way or the log no longer reaches back that far: the caller then
     * loads the whole track.
     */
    fun appendedSince(
        trackId: Long,
        revision: Int,
    ): AppendedSince? {
        val current = revisions[trackId] ?: 0
        if (revision == current) return AppendedSince(current, emptyList())
        if (revision > current) return null
        val log = appendLogs[trackId] ?: return null
        synchronized(log) {
            val wanted = log.filter { it.revision > revision && it.revision <= current }
            val contiguous = wanted.size == current - revision && wanted.firstOrNull()?.revision == revision + 1
            return if (contiguous) AppendedSince(current, wanted) else null
        }
    }

    val library: Flow<TrackLibrary> =
        combine(dao.observeFolders(), dao.observeTracks(), dao.observeRoutes(), dao.observeWaypoints()) { f, t, r, w ->
            TrackLibrary(f, t, r, w)
        }

    /**
     * Puts everything of [document] in one new folder named after the document (or the file). KML folders become
     * sub-folders. File-level metadata other than the name (author, keywords, copyright) is not kept.
     */
    suspend fun import(
        document: TrackDocument,
        fileName: String?,
    ): ImportSummary =
        db.withTransaction {
            val created = now()
            val rootName =
                document.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: fileName?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: DEFAULT_FOLDER_NAME
            val rootId =
                dao.insertFolder(
                    FolderEntity(
                        name = rootName,
                        createdAt = created,
                        namespacesJson = TrackJson.encodeNamespaces(document.namespaces),
                    ),
                )
            val folders = HashMap<String, Long>()

            suspend fun folderFor(path: String?): Long {
                if (path.isNullOrBlank()) return rootId
                var parent = rootId
                var key = ""
                for (part in path.split(FOLDER_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }) {
                    key = if (key.isEmpty()) part else "$key$FOLDER_SEPARATOR$part"
                    parent =
                        folders.getOrPut(key) {
                            dao.insertFolder(FolderEntity(parentId = parent, name = part, createdAt = created))
                        }
                }
                return parent
            }

            val format = document.format?.name
            var points = 0
            // The points of interest of a route of the file are in its plan (P30.1): their `<wpt>` copies, written for
            // the other apps, are not taken a second time.
            val waypoints = document.waypoints.filterNot { RoutePlanGpx.isPoiOf(it, document.routes) }
            for (w in waypoints) {
                dao.insertWaypoint(waypointEntity(w, folderFor(w.folder), created, format))
            }
            document.routes.forEachIndexed { i, r ->
                dao.insertRoute(routeEntity(r, folderFor(r.folder), created, format, i))
                points += r.points.size
            }
            document.tracks.forEachIndexed { i, t ->
                val id = dao.insertTrack(trackEntity(t, folderFor(t.folder), created, format, i))
                dao.insertChunks(chunksOf(id, t))
                points += t.pointCount
            }
            ImportSummary(
                folderId = rootId,
                tracks = document.tracks.size,
                routes = document.routes.size,
                waypoints = waypoints.size,
                points = points + waypoints.size,
                issues = document.issues,
            )
        }

    suspend fun loadTrack(id: Long): Track? {
        val e = dao.track(id) ?: return null
        val segments =
            dao
                .chunks(id)
                .groupBy { it.segment }
                .toSortedMap()
                .values
                .map { chunks -> chunks.sortedBy { it.chunk }.flatMap { PointCodec.decode(it.data) } }
        return Track(
            name = e.name,
            cmt = e.cmt,
            desc = e.desc,
            src = e.src,
            links = TrackJson.decodeLinks(e.linksJson),
            number = e.number,
            type = e.type,
            segments = segments,
            extras = TrackJson.decodeExtras(e.extrasJson),
            style = PathStyle(e.colorArgb, e.widthDp, e.dashed, e.arrows),
        )
    }

    suspend fun loadRoute(id: Long): Route? {
        val e = dao.route(id) ?: return null
        return Route(
            name = e.name,
            cmt = e.cmt,
            desc = e.desc,
            src = e.src,
            links = TrackJson.decodeLinks(e.linksJson),
            number = e.number,
            type = e.type,
            points = TrackJson.decodeWaypoints(e.pointsJson),
            extras = TrackJson.decodeExtras(e.extrasJson),
            style = PathStyle(e.colorArgb, e.widthDp, e.dashed, e.arrows),
        )
    }

    /** Replaces the points of a track (editing, elevation completion) and refreshes its cached figures. */
    suspend fun replaceSegments(
        trackId: Long,
        segments: List<List<TrackPoint>>,
    ) {
        db.withTransaction {
            val entity = dao.track(trackId) ?: return@withTransaction
            dao.deleteChunks(trackId)
            dao.insertChunks(chunksOf(trackId, Track(segments = segments)))
            dao.update(entity.withStats(segments))
        }
        pointsChanged(trackId)
    }

    /** Fills the missing elevations of a track from elevation tiles; the track is left alone when nothing changed. */
    suspend fun completeElevation(
        trackId: Long,
        completer: ElevationCompleter,
    ): CompletionResult? {
        val track = loadTrack(trackId) ?: return null
        val result = completer.fill(track.segments)
        if (result.filled > 0) replaceSegments(trackId, result.segments)
        return result
    }

    /**
     * A track made in the app (drawn or being recorded): it has no file behind it and sits at the root of the library.
     * Returns its id.
     */
    suspend fun createTrack(
        name: String?,
        segments: List<List<TrackPoint>> = emptyList(),
        style: PathStyle = PathStyle(),
        type: String? = null,
    ): Long =
        db.withTransaction {
            val created = now()
            val id =
                dao.insertTrack(
                    TrackEntity(
                        name = name?.trim()?.takeIf { it.isNotEmpty() },
                        type = type,
                        colorArgb = style.colorArgb,
                        widthDp = style.widthDp,
                        dashed = style.dashed,
                        arrows = style.arrows,
                        createdAt = created,
                        pointCount = 0,
                        segmentCount = 0,
                        minLat = 0.0,
                        minLon = 0.0,
                        maxLat = 0.0,
                        maxLon = 0.0,
                        distanceMeters = 0.0,
                    ).withStats(segments),
                )
            dao.insertChunks(chunksOf(id, Track(segments = segments)))
            id
        }

    /**
     * Adds points at the end of a track, for the recorder: only the last chunk is rewritten, so the cost does not grow
     * with the length of the track. [newSegment] starts a new segment with the first of [points]. The cached figures
     * that are cheap to keep current (size, bounds, distance, dates) are updated; climb is recomputed by [finishTrack].
     */
    suspend fun appendPoints(
        trackId: Long,
        points: List<TrackPoint>,
        newSegment: Boolean = false,
    ) {
        if (points.isEmpty()) return
        var endCount = -1
        var startsNewSegment = false
        db.withTransaction {
            val entity = dao.track(trackId) ?: return@withTransaction
            val last = dao.lastChunk(trackId)
            val startsSegment = newSegment || last == null
            var segment = last?.segment ?: 0
            var chunk = last?.chunk ?: 0
            var kept = emptyList<TrackPoint>()
            if (startsSegment) {
                if (last != null) segment++
                chunk = 0
            } else if (last != null) {
                kept = PointCodec.decode(last.data)
            }
            endCount = entity.pointCount + points.size
            startsNewSegment = startsSegment
            val before = kept.lastOrNull()
            val all = kept + points
            val writes = ArrayList<TrackChunkEntity>()
            all.chunked(CHUNK_POINTS).forEachIndexed { i, part ->
                writes += TrackChunkEntity(trackId, segment, chunk + i, part.size, PointCodec.encode(part))
            }
            dao.insertChunks(writes)

            var distance = entity.distanceMeters
            var from = before
            for (p in points) {
                from?.let { distance += Geodesy.distance(it, p) }
                from = p
            }
            val first = entity.pointCount == 0
            dao.update(
                entity.copy(
                    pointCount = entity.pointCount + points.size,
                    segmentCount = entity.segmentCount + if (startsSegment) 1 else 0,
                    minLat = if (first) points.minOf { it.lat } else minOf(entity.minLat, points.minOf { it.lat }),
                    minLon = if (first) points.minOf { it.lon } else minOf(entity.minLon, points.minOf { it.lon }),
                    maxLat = if (first) points.maxOf { it.lat } else maxOf(entity.maxLat, points.maxOf { it.lat }),
                    maxLon = if (first) points.maxOf { it.lon } else maxOf(entity.maxLon, points.maxOf { it.lon }),
                    distanceMeters = distance,
                    startTimeMillis = entity.startTimeMillis ?: points.firstNotNullOfOrNull { it.timeMillis },
                    endTimeMillis = points.lastNotNullOfOrNull { it.timeMillis } ?: entity.endTimeMillis,
                ),
            )
        }
        if (endCount >= 0) pointsAppended(trackId, points, startsNewSegment, endCount) else pointsChanged(trackId)
    }

    /** Recomputes every cached figure of a track from its points (end of a recording). */
    suspend fun finishTrack(trackId: Long) {
        appendLogs.remove(trackId)
        val track = loadTrack(trackId) ?: return
        db.withTransaction { dao.track(trackId)?.let { dao.update(it.withStats(track.segments)) } }
    }

    /** The track walked the other way (its times are dropped, see [TrackOps.reverse]). */
    suspend fun reverseTrack(trackId: Long) {
        val track = loadTrack(trackId) ?: return
        replaceSegments(trackId, TrackOps.reverse(track.segments))
    }

    /**
     * Thins a track to [toleranceMeters] with Douglas-Peucker; returns the point counts before and after, or `null`
     * when the track does not exist. Nothing is written when no point can be dropped.
     */
    suspend fun simplifyTrack(
        trackId: Long,
        toleranceMeters: Double,
    ): Pair<Int, Int>? {
        val track = loadTrack(trackId) ?: return null
        val thinned = track.segments.map { TrackOps.simplify(it, toleranceMeters) }
        val before = track.segments.sumOf { it.size }
        val after = thinned.sumOf { it.size }
        if (after < before) replaceSegments(trackId, thinned)
        return before to after
    }

    /**
     * A new track made of [trackIds] one after the other (each stays a segment of its own: the way between two tracks
     * is not part of the result). The sources are left alone. Returns its id, or `null` with fewer than two tracks.
     */
    suspend fun mergeTracks(
        trackIds: List<Long>,
        name: String?,
    ): Long? {
        val tracks = trackIds.mapNotNull { loadTrack(it) }
        if (tracks.size < 2) return null
        val segments = tracks.map { it.segments }.reduce { a, b -> TrackOps.merge(a, b) }
        return createTrack(name, segments, tracks.first().style ?: PathStyle())
    }

    /** A route made in the app. Returns its id. */
    suspend fun createRoute(
        name: String?,
        points: List<Waypoint>,
        style: PathStyle = PathStyle(dashed = true),
        extras: List<RawXml> = emptyList(),
    ): Long =
        dao.insertRoute(
            routeEntity(
                Route(name = name?.trim()?.takeIf { it.isNotEmpty() }, points = points, extras = extras, style = style),
                folderId = null,
                created = now(),
                format = null,
                index = 0,
            ),
        )

    /**
     * Replaces the points of a route (editing on the map) and refreshes its cached figures. [extras], when given,
     * replace its GPX extensions too (the plan of a computed route, P19).
     */
    suspend fun replaceRoutePoints(
        routeId: Long,
        points: List<Waypoint>,
        extras: List<RawXml>? = null,
    ) {
        db.withTransaction {
            val entity = dao.route(routeId) ?: return@withTransaction
            val updated =
                routeEntity(
                    Route(points = points),
                    folderId = entity.folderId,
                    created = entity.createdAt,
                    format = entity.format,
                    index = 0,
                )
            dao.update(
                entity.copy(
                    pointCount = updated.pointCount,
                    minLat = updated.minLat,
                    minLon = updated.minLon,
                    maxLat = updated.maxLat,
                    maxLon = updated.maxLon,
                    distanceMeters = updated.distanceMeters,
                    pointsJson = updated.pointsJson,
                    extrasJson = if (extras != null) TrackJson.encodeExtras(extras) else entity.extrasJson,
                ),
            )
        }
    }

    /** A waypoint put on the map. Returns its id. */
    suspend fun createWaypoint(
        waypoint: Waypoint,
        folderId: Long? = null,
    ): Long = dao.insertWaypoint(waypointEntity(waypoint, folderId, now(), format = null))

    /** Rewrites a waypoint's own fields (name, notes, symbol, place, altitude); visibility and folder stay. */
    suspend fun updateWaypoint(
        id: Long,
        waypoint: Waypoint,
    ) {
        db.withTransaction {
            val entity = dao.waypoint(id) ?: return@withTransaction
            dao.update(
                entity.copy(
                    name = waypoint.name?.trim()?.takeIf { it.isNotEmpty() },
                    cmt = waypoint.cmt,
                    desc = waypoint.desc,
                    lat = waypoint.lat,
                    lon = waypoint.lon,
                    ele = waypoint.ele,
                    timeMillis = waypoint.timeMillis,
                    sym = waypoint.sym,
                    type = waypoint.type,
                ),
            )
        }
    }

    /** Description and comment of a track or route, shown in the detail screen and written to GPX. */
    suspend fun describe(
        ref: ItemRef,
        description: String?,
    ) {
        val value = description?.trim()?.takeIf { it.isNotEmpty() }
        when (ref.kind) {
            ItemKind.TRACK -> dao.track(ref.id)?.let { dao.update(it.copy(desc = value)) }
            ItemKind.ROUTE -> dao.route(ref.id)?.let { dao.update(it.copy(desc = value)) }
            ItemKind.WAYPOINT -> dao.waypoint(ref.id)?.let { dao.update(it.copy(desc = value)) }
        }
    }

    suspend fun loadWaypoint(id: Long): Waypoint? = dao.waypoint(id)?.let { waypointOf(it) }

    /** Statistics of a stored track, from its points. */
    suspend fun stats(trackId: Long): TrackStats? =
        loadTrack(trackId)?.let { TrackStatsCalculator.compute(it.segments) }

    /** The selection as a document ready for [fr.toolmap.core.track.GpxWriter]. */
    suspend fun document(selection: TrackSelection): TrackDocument {
        val lib = library.first()
        val folderIds = selection.folderIds.flatMapTo(linkedSetOf()) { lib.descendants(it) }
        // Ids grow in file order, so sorting them writes items in the order they were imported or drawn.
        val trackIds = lib.tracks.filter { it.folderId in folderIds }.mapTo(sortedSetOf()) { it.id }
        trackIds += selection.trackIds
        val routeIds = lib.routes.filter { it.folderId in folderIds }.mapTo(sortedSetOf()) { it.id }
        routeIds += selection.routeIds
        val waypointIds = lib.waypoints.filter { it.folderId in folderIds }.mapTo(sortedSetOf()) { it.id }
        waypointIds += selection.waypointIds

        val namespaces = NamespaceMerger(lib)
        val tracks =
            trackIds.mapNotNull { id ->
                val track = loadTrack(id) ?: return@mapNotNull null
                val folderId = lib.tracks.first { it.id == id }.folderId
                namespaces.adopt(folderId) { f ->
                    track.copy(
                        extras = f(track.extras),
                        segments = track.segments.map { s -> s.map { p -> p.copy(extras = f(p.extras)) } },
                    )
                }
            }
        val routes =
            routeIds.mapNotNull { id ->
                val route = loadRoute(id) ?: return@mapNotNull null
                val folderId = lib.routes.first { it.id == id }.folderId
                namespaces.adopt(folderId) { f ->
                    route.copy(
                        extras = f(route.extras),
                        points =
                            route.points.map { p ->
                                p.copy(extras = f(p.extras))
                            },
                    )
                }
            }
        val waypoints =
            waypointIds.mapNotNull { id ->
                val waypoint = loadWaypoint(id) ?: return@mapNotNull null
                val folderId = lib.waypoints.first { it.id == id }.folderId
                namespaces.adopt(folderId) { f -> waypoint.copy(extras = f(waypoint.extras)) }
            }
        // The points of interest of the routes (P30.1) as waypoints too, for the apps that do not read the plan.
        val pois = routes.flatMap { RoutePlanGpx.poiWaypoints(it) }
        val onlyFolder =
            selection.folderIds.singleOrNull()?.takeIf {
                selection.trackIds.isEmpty() && selection.routeIds.isEmpty() && selection.waypointIds.isEmpty()
            }
        return TrackDocument(
            name = onlyFolder?.let { id -> lib.folders.firstOrNull { it.id == id }?.name },
            waypoints = waypoints + pois,
            routes = routes,
            tracks = tracks,
            namespaces = namespaces.merged,
        )
    }

    suspend fun setVisible(
        ref: ItemRef,
        visible: Boolean,
    ) {
        when (ref.kind) {
            ItemKind.TRACK -> dao.track(ref.id)?.let { dao.update(it.copy(visible = visible)) }
            ItemKind.ROUTE -> dao.route(ref.id)?.let { dao.update(it.copy(visible = visible)) }
            ItemKind.WAYPOINT -> dao.waypoint(ref.id)?.let { dao.update(it.copy(visible = visible)) }
        }
    }

    suspend fun rename(
        ref: ItemRef,
        name: String,
    ) {
        val value = name.trim().takeIf { it.isNotEmpty() }
        when (ref.kind) {
            ItemKind.TRACK -> dao.track(ref.id)?.let { dao.update(it.copy(name = value)) }
            ItemKind.ROUTE -> dao.route(ref.id)?.let { dao.update(it.copy(name = value)) }
            ItemKind.WAYPOINT -> dao.waypoint(ref.id)?.let { dao.update(it.copy(name = value)) }
        }
    }

    /** Colour, width, dashes and direction arrows of a track or a route. */
    suspend fun setStyle(
        ref: ItemRef,
        style: PathStyle,
    ) {
        when (ref.kind) {
            ItemKind.TRACK -> {
                dao.track(ref.id)?.let {
                    dao.update(
                        it.copy(
                            colorArgb = style.colorArgb,
                            widthDp = style.widthDp,
                            dashed = style.dashed,
                            arrows = style.arrows,
                        ),
                    )
                }
            }

            ItemKind.ROUTE -> {
                dao.route(ref.id)?.let {
                    dao.update(
                        it.copy(
                            colorArgb = style.colorArgb,
                            widthDp = style.widthDp,
                            dashed = style.dashed,
                            arrows = style.arrows,
                        ),
                    )
                }
            }

            // Waypoints have no line to style.
            ItemKind.WAYPOINT -> {
                return
            }
        }
    }

    suspend fun delete(ref: ItemRef) {
        when (ref.kind) {
            ItemKind.TRACK -> dao.deleteTrack(ref.id)
            ItemKind.ROUTE -> dao.deleteRoute(ref.id)
            ItemKind.WAYPOINT -> dao.deleteWaypoint(ref.id)
        }
    }

    /** « Tout afficher sur la carte » of the tracks panel (P14.10): every folder, track, route and waypoint at once. */
    suspend fun setAllVisible(visible: Boolean) {
        db.withTransaction {
            dao.setAllFoldersVisible(visible)
            dao.setAllTracksVisible(visible)
            dao.setAllRoutesVisible(visible)
            dao.setAllWaypointsVisible(visible)
        }
    }

    suspend fun setFolderVisible(
        folderId: Long,
        visible: Boolean,
    ) {
        dao.folder(folderId)?.let { dao.update(it.copy(visible = visible)) }
    }

    suspend fun renameFolder(
        folderId: Long,
        name: String,
    ) {
        val value = name.trim().takeIf { it.isNotEmpty() } ?: return
        dao.folder(folderId)?.let { dao.update(it.copy(name = value)) }
    }

    /** Deletes the folder and everything in it. */
    suspend fun deleteFolder(folderId: Long) = dao.deleteFolder(folderId)

    /** A new empty folder under [parentId] (`null` = at the top of the library); `null` when the name is blank. */
    suspend fun createFolder(
        parentId: Long?,
        name: String,
    ): Long? {
        val value = name.trim().takeIf { it.isNotEmpty() } ?: return null
        if (parentId != null && dao.folder(parentId) == null) return null
        return dao.insertFolder(FolderEntity(parentId = parentId, name = value, createdAt = now()))
    }

    /** Puts a track, route or waypoint in [folderId] (`null` = top of the library). `false` when it or the folder is gone. */
    suspend fun moveItem(
        ref: ItemRef,
        folderId: Long?,
    ): Boolean =
        db.withTransaction {
            if (folderId != null && dao.folder(folderId) == null) return@withTransaction false
            val lib = foldersOnly()
            when (ref.kind) {
                ItemKind.TRACK -> {
                    val e = dao.track(ref.id) ?: return@withTransaction false
                    carryNamespaces(lib, e.folderId, folderId)
                    dao.update(e.copy(folderId = folderId))
                }

                ItemKind.ROUTE -> {
                    val e = dao.route(ref.id) ?: return@withTransaction false
                    carryNamespaces(lib, e.folderId, folderId)
                    dao.update(e.copy(folderId = folderId))
                }

                ItemKind.WAYPOINT -> {
                    val e = dao.waypoint(ref.id) ?: return@withTransaction false
                    carryNamespaces(lib, e.folderId, folderId)
                    dao.update(e.copy(folderId = folderId))
                }
            }
            true
        }

    /**
     * Moves a folder, with all it holds, under [parentId] (`null` = top of the library). `false` when it would end up
     * inside itself or one of its own sub-folders, or when either folder is gone.
     */
    suspend fun moveFolder(
        folderId: Long,
        parentId: Long?,
    ): Boolean =
        db.withTransaction {
            val folder = dao.folder(folderId) ?: return@withTransaction false
            val lib = foldersOnly()
            if (parentId != null && (dao.folder(parentId) == null || parentId in lib.descendants(folderId))) {
                return@withTransaction false
            }
            val oldRoot = lib.chain(folderId).lastOrNull()
            var moved = folder.copy(parentId = parentId)
            if (parentId == null) {
                // It becomes a top-level folder: it must now carry the prefixes of the import it came from.
                if (oldRoot != null && oldRoot.id != folderId) {
                    moved = moved.copy(namespacesJson = oldRoot.namespacesJson)
                }
            } else {
                carryNamespaces(lib, folderId, parentId)
            }
            dao.update(moved)
            true
        }

    private suspend fun foldersOnly() = TrackLibrary(dao.allFolders(), emptyList(), emptyList(), emptyList())

    /**
     * Raw XML of an item relies on the prefixes of the import at the top of its folder (see [NamespaceMerger]): when
     * the item changes top-level folder, the destination learns the prefixes of the old one (its own win on a clash).
     */
    private suspend fun carryNamespaces(
        lib: TrackLibrary,
        from: Long?,
        to: Long?,
    ) {
        val oldRoot = lib.chain(from).lastOrNull() ?: return
        val newRoot = lib.chain(to).lastOrNull() ?: return
        if (oldRoot.id == newRoot.id) return
        val old = TrackJson.decodeNamespaces(oldRoot.namespacesJson)
        val own = TrackJson.decodeNamespaces(newRoot.namespacesJson)
        val merged = old + own
        if (merged != own) dao.update(newRoot.copy(namespacesJson = TrackJson.encodeNamespaces(merged)))
    }

    private fun chunksOf(
        trackId: Long,
        track: Track,
    ): List<TrackChunkEntity> =
        track.segments.flatMapIndexed { s, points ->
            points.chunked(CHUNK_POINTS).mapIndexed { c, part ->
                TrackChunkEntity(trackId, s, c, part.size, PointCodec.encode(part))
            }
        }

    private fun trackEntity(
        t: Track,
        folderId: Long,
        created: Long,
        format: String?,
        index: Int,
    ): TrackEntity {
        val style = t.style ?: PathStyle(colorArgb = PathStyle.PALETTE[index % PathStyle.PALETTE.size])
        return TrackEntity(
            folderId = folderId,
            name = t.name,
            cmt = t.cmt,
            desc = t.desc,
            src = t.src,
            number = t.number,
            type = t.type,
            linksJson = TrackJson.encodeLinks(t.links),
            extrasJson = TrackJson.encodeExtras(t.extras),
            colorArgb = style.colorArgb,
            widthDp = style.widthDp,
            dashed = style.dashed,
            arrows = style.arrows,
            createdAt = created,
            format = format,
            pointCount = 0,
            segmentCount = 0,
            minLat = 0.0,
            minLon = 0.0,
            maxLat = 0.0,
            maxLon = 0.0,
            distanceMeters = 0.0,
        ).withStats(t.segments)
    }

    /** The entity with the figures a list shows (size, bounds, distance, climb, dates) computed from [segments]. */
    private fun TrackEntity.withStats(segments: List<List<TrackPoint>>): TrackEntity {
        val stats = TrackStatsCalculator.compute(segments)
        val points = segments.flatten()
        return copy(
            pointCount = stats.pointCount,
            segmentCount = stats.segmentCount,
            minLat = points.minOfOrNull { it.lat } ?: 0.0,
            minLon = points.minOfOrNull { it.lon } ?: 0.0,
            maxLat = points.maxOfOrNull { it.lat } ?: 0.0,
            maxLon = points.maxOfOrNull { it.lon } ?: 0.0,
            distanceMeters = stats.distance2dMeters,
            ascentMeters = stats.ascentMeters.takeIf { stats.minEleMeters != null },
            descentMeters = stats.descentMeters.takeIf { stats.minEleMeters != null },
            startTimeMillis = stats.startTimeMillis,
            endTimeMillis = stats.endTimeMillis,
        )
    }

    private fun routeEntity(
        r: Route,
        folderId: Long?,
        created: Long,
        format: String?,
        index: Int,
    ): RouteEntity {
        val style =
            r.style ?: PathStyle(colorArgb = PathStyle.PALETTE[(index + 1) % PathStyle.PALETTE.size], dashed = true)
        val track = listOf(r.points.map { TrackPoint(it.lat, it.lon) })
        return RouteEntity(
            folderId = folderId,
            name = r.name,
            cmt = r.cmt,
            desc = r.desc,
            src = r.src,
            number = r.number,
            type = r.type,
            linksJson = TrackJson.encodeLinks(r.links),
            extrasJson = TrackJson.encodeExtras(r.extras),
            colorArgb = style.colorArgb,
            widthDp = style.widthDp,
            dashed = style.dashed,
            arrows = style.arrows,
            createdAt = created,
            format = format,
            pointCount = r.points.size,
            minLat = r.points.minOfOrNull { it.lat } ?: 0.0,
            minLon = r.points.minOfOrNull { it.lon } ?: 0.0,
            maxLat = r.points.maxOfOrNull { it.lat } ?: 0.0,
            maxLon = r.points.maxOfOrNull { it.lon } ?: 0.0,
            distanceMeters = TrackStatsCalculator.compute(track).distance2dMeters,
            pointsJson = TrackJson.encodeWaypoints(r.points),
        )
    }

    private fun waypointEntity(
        w: Waypoint,
        folderId: Long?,
        created: Long,
        format: String?,
    ) = WaypointEntity(
        folderId = folderId,
        name = w.name,
        cmt = w.cmt,
        desc = w.desc,
        src = w.src,
        lat = w.lat,
        lon = w.lon,
        ele = w.ele,
        timeMillis = w.timeMillis,
        sym = w.sym,
        type = w.type,
        linksJson = TrackJson.encodeLinks(w.links),
        extrasJson = TrackJson.encodeExtras(w.extras),
        createdAt = created,
        format = format,
    )

    private fun waypointOf(e: WaypointEntity) =
        Waypoint(
            lat = e.lat,
            lon = e.lon,
            ele = e.ele,
            timeMillis = e.timeMillis,
            name = e.name,
            cmt = e.cmt,
            desc = e.desc,
            src = e.src,
            links = TrackJson.decodeLinks(e.linksJson),
            sym = e.sym,
            type = e.type,
            extras = TrackJson.decodeExtras(e.extrasJson),
        )

    private fun <T, R : Any> List<T>.lastNotNullOfOrNull(transform: (T) -> R?): R? {
        for (i in indices.reversed()) transform(this[i])?.let { return it }
        return null
    }

    companion object {
        /** Points per stored chunk: a few hundred kilobytes even with heavy per-point extensions. */
        const val CHUNK_POINTS = 2000

        /** Points kept in the append log of a track; a display that falls further behind loads the track again. */
        private const val APPEND_LOG_POINTS = 10_000
        const val DEFAULT_FOLDER_NAME = "Import"
        const val FOLDER_SEPARATOR = " / "
    }
}
