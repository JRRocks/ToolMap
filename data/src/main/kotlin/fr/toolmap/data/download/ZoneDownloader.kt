package fr.toolmap.data.download

import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.DownloadArea
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TilePlanner
import fr.toolmap.core.tiles.TileResolver
import fr.toolmap.core.tiles.TooManyTilesException
import fr.toolmap.data.tiles.BulkFetch
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.SourceLookup
import fr.toolmap.data.tiles.TileHost
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.data.tiles.TileSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class RefusalReason {
    /** "Local storage only" is on: nothing may be downloaded. */
    OFFLINE,
    UNKNOWN_SOURCE,
    TOO_MANY_TILES,
    RESTRICTED_SOURCE,
    NOTHING_TO_DOWNLOAD,
}

sealed interface ZoneResult {
    data class Completed(
        val progress: ZoneProgress,
    ) : ZoneResult

    /** Stopped by too many consecutive failures (network down, server refusing): can be resumed later. */
    data class Interrupted(
        val progress: ZoneProgress,
        val reason: String,
    ) : ZoneResult

    data class Refused(
        val reason: RefusalReason,
        val detail: String,
    ) : ZoneResult
}

/** How many tiles a request needs, source by source. */
data class ZonePlan(
    /**
     * Requested map → displayed zoom → real tiles to fetch for it. A tile needed by several maps or zooms (shared
     * storage, forced zoom) is counted once, for the first one in request order, so the figures add up to [total].
     */
    val perSource: Map<String, Map<Int, Long>>,
    /** The real tiles to fetch: map whose server serves them → tile zooms. */
    val fetches: Map<String, Set<Int>> = emptyMap(),
    /** Real tiles per map whose server serves them (for the limits of [BulkPolicy]). */
    val perMember: Map<String, Long> = emptyMap(),
    /** Requested map → how many of its counted tiles are already stored (filled only when asked, for estimates). */
    val stored: Map<String, Long> = emptyMap(),
    /** Like [stored], by map whose server serves the tiles: a stored tile is not asked again ([BulkPolicy] limits). */
    val storedPerMember: Map<String, Long> = emptyMap(),
) {
    val total: Long get() = perSource.values.sumOf { it.values.sum() }
}

/** A map of a plan whose server limits bulk downloads to fewer tiles than the plan needs. */
data class RestrictedFetch(
    val source: TileSource,
    val tiles: Long,
    val limit: Long,
)

/**
 * Fetches every tile of a [ZoneRequest] through the same OkHttp client as the map, so the TileInterceptor adds the
 * Referer / headers / cookies, honours `max-threads` and stores each tile in the [MbTilesStore]. Tiles already
 * stored are skipped, which makes pausing and resuming a plain "run it again".
 *
 * A displayed zoom is expanded into the real tiles the map shows there (ADR-031): the tiles of the maps a
 * multi-zoom map is made of, at their forced zoom, or the neighbouring zoom used where the map has none, so that
 * the zone shows offline exactly as it does online. Tiles shared with an already stored map are not fetched again.
 */
class ZoneDownloader(
    private val client: OkHttpClient,
    private val sources: SourceLookup,
    private val store: MbTilesStore,
    private val offline: OfflineMode,
    private val failureStreakLimit: Int = DEFAULT_FAILURE_STREAK,
    private val retryDelaysMillis: List<Long> = listOf(1_000L, 3_000L),
    private val scaling: () -> ScalingPolicy = { ScalingPolicy() },
) {
    private val resolver = TileResolver(sources::find)

    /** Counts the tiles of [request]; throws [TooManyTilesException] for a zone beyond the safety limit. */
    fun plan(
        request: ZoneRequest,
        withStored: Boolean = false,
    ): ZonePlan {
        val area = request.shape.toArea()
        val policy = scaling()
        val perSource = LinkedHashMap<String, Map<Int, Long>>()
        val stored = LinkedHashMap<String, Long>()
        val storedPerMember = LinkedHashMap<String, Long>()
        val fetches = LinkedHashMap<String, MutableSet<Int>>()
        val perMember = LinkedHashMap<String, Long>()
        val counted = HashSet<Pair<String, Int>>()
        val counts = HashMap<Int, Long>()
        var total = 0L
        for (id in request.sourceIds) {
            val source = sources.find(id) ?: continue
            val zooms = zoomsOf(source, request)
            if (zooms.isEmpty()) continue
            val needs = zooms.associateWith { resolver.tileZooms(source, it, policy) }
            val perZoom = LinkedHashMap<Int, Long>()
            zooms.forEach { perZoom[it] = 0L }
            // Tiles of the displayed zoom itself first, so that a zoom drawn from its neighbours shows only what it
            // adds (nothing when the neighbour is downloaded too).
            for (direct in listOf(true, false)) {
                for ((z, members) in needs) {
                    for ((member, tileZooms) in members) {
                        for (tileZoom in tileZooms) {
                            if ((tileZoom == z) != direct) continue
                            if (member.levelFor(tileZoom)?.isLocalOnly != false) continue
                            val storage = store.storageIdOf(TileKey(member.id, tileZoom, 0, 0)) ?: continue
                            if (!counted.add(storage to tileZoom)) continue
                            val n =
                                counts.getOrPut(tileZoom) {
                                    TilePlanner.count(area, tileZoom..tileZoom)[tileZoom] ?: 0L
                                }
                            total += n
                            if (total > TilePlanner.MAX_TILES) throw TooManyTilesException(TilePlanner.MAX_TILES)
                            perZoom[z] = perZoom.getValue(z) + n
                            fetches.getOrPut(member.id) { sortedSetOf() } += tileZoom
                            perMember[member.id] = (perMember[member.id] ?: 0L) + n
                            if (withStored) {
                                val already = storedIn(storage, tileZoom, area)
                                stored[id] = (stored[id] ?: 0L) + already
                                storedPerMember[member.id] = (storedPerMember[member.id] ?: 0L) + already
                            }
                        }
                    }
                }
            }
            perSource[id] = perZoom
        }
        return ZonePlan(perSource, fetches, perMember, stored, storedPerMember)
    }

    /** Tiles of [area] at [zoom] already in the storage [storageId] (a download skips them). */
    private fun storedIn(
        storageId: String,
        zoom: Int,
        area: DownloadArea,
    ): Long {
        val file =
            try {
                store.peek(storageId)
            } catch (e: IllegalStateException) {
                null // the tile folder is not available: nothing can be counted as stored
            } ?: return 0L
        return TilePlanner.countStored(
            area,
            zoom,
            countIn = { xs, ys -> file.countTiles(zoom, xs, ys) },
            storedIn = { xs, ys ->
                val tiles = file.storedTiles(zoom, xs, ys)
                return@countStored { x, y -> TileSet.pack(x, y) in tiles }
            },
        )
    }

    /**
     * The first map of [plan] whose server allows fewer tiles than the plan needs from it, or `null`. Only the tiles
     * still missing count when the plan counted the stored ones (`plan(withStored = true)`): they alone are asked.
     */
    fun restrictedIn(plan: ZonePlan): RestrictedFetch? {
        for ((id, tiles) in plan.perMember) {
            val source = sources.find(id) ?: continue
            val limit = BulkPolicy.limitsFor(source).maxTiles ?: continue
            val missing = (tiles - (plan.storedPerMember[id] ?: 0L)).coerceAtLeast(0L)
            if (missing > limit) return RestrictedFetch(source, missing, limit)
        }
        return null
    }

    suspend fun run(
        request: ZoneRequest,
        onProgress: (ZoneProgress) -> Unit = {},
    ): ZoneResult {
        request.sourceIds.firstOrNull { sources.find(it) == null }?.let {
            return ZoneResult.Refused(RefusalReason.UNKNOWN_SOURCE, it)
        }
        if (offline.isOffline()) return ZoneResult.Refused(RefusalReason.OFFLINE, "offline mode is on")
        val plan =
            try {
                // Counting the stored tiles costs reads: only done when a limited server could refuse the zone.
                plan(request).let { counted -> if (restrictedIn(counted) == null) counted else plan(request, true) }
            } catch (e: TooManyTilesException) {
                return ZoneResult.Refused(RefusalReason.TOO_MANY_TILES, e.message.orEmpty())
            }
        restrictedIn(plan)?.let {
            return ZoneResult.Refused(
                RefusalReason.RESTRICTED_SOURCE,
                "${it.source.id}: ${it.tiles} tiles, limit ${it.limit}",
            )
        }
        if (plan.total == 0L) return ZoneResult.Refused(RefusalReason.NOTHING_TO_DOWNLOAD, "no tile in the zone")

        val counters = Counters(plan.total, onProgress)
        counters.report(force = true)
        val area = request.shape.toArea()
        try {
            for ((id, zooms) in plan.fetches) {
                val member = sources.find(id) ?: continue
                download(member, zooms, counters) { zooms.asSequence().flatMap { TilePlanner.tiles(area, it..it) } }
            }
        } catch (e: ZoneInterrupted) {
            counters.report(force = true)
            return ZoneResult.Interrupted(counters.snapshot(), e.message.orEmpty())
        } catch (e: CancellationException) {
            counters.report(force = true)
            throw e
        }
        counters.report(force = true)
        return ZoneResult.Completed(counters.snapshot())
    }

    /**
     * The real tiles that showing [wanted] (map id to tiles) needs and that are neither stored nor local-only, by
     * the map whose server serves them: what a download would fetch. A tile of a multi-zoom map or of a zoom the map
     * does not serve stands for the tiles it is drawn from. Used by the print to offer "download the missing tiles"
     * before an export.
     */
    fun missing(wanted: Map<String, Collection<TileId>>): Map<String, List<TileId>> {
        val policy = scaling()
        val out = LinkedHashMap<String, LinkedHashSet<TileId>>()
        for ((id, tiles) in wanted) {
            val source = sources.find(id) ?: continue
            for (tile in tiles) {
                for (leaf in resolver.resolve(source, tile.z, tile.x, tile.y, policy).leaves()) {
                    if (!leaf.level.hasServer) continue
                    val key = TileKey(leaf.source.id, leaf.tile.z, leaf.tile.x, leaf.tile.y)
                    if (!store.contains(key)) out.getOrPut(leaf.source.id) { LinkedHashSet() } += leaf.tile
                }
            }
        }
        return out.mapValues { it.value.toList() }
    }

    /**
     * Fetches the listed tiles under the same server policies as [run] (threads, pauses, limit on restricted servers
     * counted on the tiles actually missing), skipping those already stored.
     */
    suspend fun runTiles(
        wanted: Map<String, Collection<TileId>>,
        onProgress: (ZoneProgress) -> Unit = {},
    ): ZoneResult {
        wanted.keys.firstOrNull { sources.find(it) == null }?.let {
            return ZoneResult.Refused(RefusalReason.UNKNOWN_SOURCE, it)
        }
        if (offline.isOffline()) return ZoneResult.Refused(RefusalReason.OFFLINE, "offline mode is on")
        val absent = missing(wanted)
        // Keyed by the map whose server serves the tiles: for a multi-zoom map, one of its members, not a wanted id.
        val resolved = LinkedHashMap<String, TileSource>()
        for (id in absent.keys) {
            resolved[id] = sources.find(id) ?: return ZoneResult.Refused(RefusalReason.UNKNOWN_SOURCE, id)
        }
        for ((id, tiles) in absent) {
            val limit = BulkPolicy.limitsFor(resolved.getValue(id)).maxTiles
            if (limit != null && tiles.size > limit) {
                return ZoneResult.Refused(RefusalReason.RESTRICTED_SOURCE, "$id: ${tiles.size} tiles, limit $limit")
            }
        }
        val total = absent.values.sumOf { it.size.toLong() }
        if (total == 0L) return ZoneResult.Refused(RefusalReason.NOTHING_TO_DOWNLOAD, "no tile to download")
        val counters = Counters(total, onProgress)
        counters.report(force = true)
        try {
            for ((id, tiles) in absent) {
                download(resolved.getValue(id), tiles.map { it.z }.toSet(), counters) { tiles.asSequence() }
            }
        } catch (e: ZoneInterrupted) {
            counters.report(force = true)
            return ZoneResult.Interrupted(counters.snapshot(), e.message.orEmpty())
        } catch (e: CancellationException) {
            counters.report(force = true)
            throw e
        }
        counters.report(force = true)
        return ZoneResult.Completed(counters.snapshot())
    }

    private fun zoomsOf(
        source: TileSource,
        request: ZoneRequest,
    ): List<Int> {
        val limit = request.zoomLimits[source.id]
        return request.zooms.filter { limit == null || it in limit }
    }

    private suspend fun download(
        source: TileSource,
        zooms: Set<Int>,
        counters: Counters,
        tiles: () -> Sequence<TileId>,
    ) {
        val limits = BulkPolicy.limitsFor(source)
        val workers = minOf(source.levelFor(zooms.min())?.maxThreads ?: 1, limits.maxThreads).coerceAtLeast(1)
        val batch = TileBatch(write = { store.putAll(source.id, it) })
        try {
            coroutineScope {
                val queue = Channel<TileId>(capacity = workers * QUEUE_PER_WORKER)
                launch(Dispatchers.Default) {
                    try {
                        for (tile in tiles()) queue.send(tile)
                    } finally {
                        queue.close()
                    }
                }
                repeat(workers) {
                    launch(Dispatchers.IO) {
                        for (tile in queue) {
                            if (store.contains(TileKey(source.id, tile.z, tile.x, tile.y))) {
                                counters.stored.incrementAndGet()
                            } else {
                                fetch(source, tile, counters, batch)
                                if (limits.delayMillis > 0) delay(limits.delayMillis)
                            }
                            batch.flushIfDue()
                            counters.report()
                        }
                    }
                }
            }
        } finally {
            // Whatever the end (done, interrupted, paused), the tiles downloaded so far are stored: a resume skips them.
            withContext(NonCancellable + Dispatchers.IO) { batch.flush() }
        }
    }

    private suspend fun fetch(
        source: TileSource,
        tile: TileId,
        counters: Counters,
        batch: TileBatch,
    ) {
        val url = "https://${TileHost.HOST}/${source.id}/${tile.z}/${tile.x}/${tile.y}"
        var lastError = "no answer"
        for (attempt in 0..retryDelaysMillis.size) {
            if (attempt > 0) delay(retryDelaysMillis[attempt - 1])
            // The tile is handed to the batch instead of being written one by one (ADR-058).
            val request = Request.Builder().url(url).tag(BulkFetch::class.java, BulkFetch(batch::add)).build()
            val call = client.newCall(request)
            try {
                val (code, size) =
                    runInterruptible(Dispatchers.IO) {
                        call.execute().use { response ->
                            val bytes = if (response.isSuccessful) response.body?.bytes()?.size ?: 0 else 0
                            response.code to bytes
                        }
                    }
                when {
                    code in 200..299 -> {
                        counters.downloaded.incrementAndGet()
                        counters.bytes.addAndGet(size.toLong())
                        counters.streak.set(0)
                        return
                    }

                    code == 404 -> {
                        counters.missing.incrementAndGet()
                        counters.streak.set(0)
                        return
                    }

                    code == 400 || code == 502 -> {
                        // A bad template or an unusable answer will not improve by retrying.
                        lastError = "HTTP $code"
                        break
                    }

                    else -> {
                        lastError = "HTTP $code"
                    }
                }
            } catch (e: IOException) {
                if (call.isCanceled()) throw CancellationException("download cancelled")
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        counters.failed.incrementAndGet()
        if (counters.streak.incrementAndGet() >= failureStreakLimit) {
            throw ZoneInterrupted("$failureStreakLimit tiles in a row failed (last: $lastError)")
        }
    }

    private class ZoneInterrupted(
        message: String,
    ) : RuntimeException(message)

    private class Counters(
        val total: Long,
        private val listener: (ZoneProgress) -> Unit,
    ) {
        val downloaded = AtomicLong()
        val stored = AtomicLong()
        val missing = AtomicLong()
        val failed = AtomicLong()
        val bytes = AtomicLong()
        val streak = AtomicInteger()
        private val lastReport = AtomicLong()

        fun snapshot() = ZoneProgress(total, downloaded.get(), stored.get(), missing.get(), failed.get(), bytes.get())

        fun report(force: Boolean = false) {
            val now = System.nanoTime()
            val last = lastReport.get()
            if (force || (now - last >= REPORT_INTERVAL_NANOS && lastReport.compareAndSet(last, now))) {
                listener(snapshot())
            }
        }
    }

    private companion object {
        const val DEFAULT_FAILURE_STREAK = 25
        const val QUEUE_PER_WORKER = 4
        const val REPORT_INTERVAL_NANOS = 1_000_000_000L
    }
}
