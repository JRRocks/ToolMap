package fr.toolmap.data.tiles

import fr.toolmap.core.elevation.DemWindow
import fr.toolmap.core.elevation.ElevationMask
import fr.toolmap.core.elevation.MaskRaster
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.UpdateDelay
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.tiles.TileRecipe
import fr.toolmap.core.tiles.TileResolver
import fr.toolmap.core.tiles.TileUrlBuilder
import fr.toolmap.core.tiles.TileUrlException
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application interceptor installed on MapLibre's HTTP client (`HttpRequestUtil.setOkHttpClient`).
 *
 * For a request to [TileHost.HOST] (`/<sourceId>/<z>/<x>/<y>`), the [TileResolver] first says where the tile comes
 * from (ADR-031): the map's own tile, the tile of the map a multi-zoom level shows, or tiles of another zoom to
 * enlarge or shrink (a forced zoom, a zoom missing from the map, below its first zoom). Each real tile is then:
 * 1. answered from the [TileStore] without any network access (unless it is older than the source's
 *    `update-delay`, or its map is in [ForcedRefresh]: then it is fetched again, and still served if the server
 *    fails);
 * 2. in [OfflineMode] a miss is "no tile" (MapLibre treats a 404 as "no tile here");
 * 3. otherwise fetched from the real URL built from the AQX definition ([TileUrlBuilder]), sent with the configured
 *    Referer / User-Agent / headers / cookie, and stored when it is really an image.
 * A tile made of other tiles is drawn by the [TileComposer] and never stored.
 *
 * Every other request (style, glyphs...) goes through untouched. Synthetic answers: 400 malformed URL,
 * 404 unknown source / zoom out of range / offline miss / empty tile, 502 unusable server answer,
 * 504 network failure. Not honoured yet: [LevelDef.insecure], [LevelDef.clearColor], virtual levels.
 */
class TileInterceptor(
    private val sources: SourceLookup,
    private val store: TileStore,
    private val stats: TileStats,
    private val offline: OfflineMode,
    private val defaultUserAgent: String = DEFAULT_USER_AGENT,
    private val clock: () -> Long = System::currentTimeMillis,
    private val forcedRefresh: ForcedRefresh = ForcedRefresh(),
    private val scaling: () -> ScalingPolicy = { ScalingPolicy() },
    private val composer: TileComposer = BitmapTileComposer(),
    private val masker: ElevationMasker = BitmapElevationMasker(),
    private val demSourceId: String = DEM_SOURCE_ID,
    private val log: ((String) -> Unit)? = null,
) : Interceptor {
    private val rotation = ConcurrentHashMap<String, AtomicInteger>()
    private val gates = ConcurrentHashMap<String, RecentFirstGate>()
    private val resolver = TileResolver(sources::find)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != TileHost.HOST) return chain.proceed(request)
        // MapLibre cancels the tiles it no longer needs: a cancelled call waiting in the dispatcher costs nothing.
        if (chain.call().isCanceled()) throw IOException("Canceled")

        val key =
            parse(request.url) ?: run {
                stats.record(TileOutcome.REJECTED, error = "malformed tile url ${request.url}")
                return reply(request, 400, "malformed tile url")
            }
        val source =
            sources.find(key.sourceId) ?: run {
                stats.record(TileOutcome.REJECTED, key.z, error = "unknown source ${key.sourceId}")
                return reply(request, 404, "unknown source")
            }
        val mask = ElevationMask.parse(request.url.query)
        if (mask != null && !mask.isWholeAt(key.z)) return masked(chain, request, key, source, mask)
        return plain(chain, request, key, source)
    }

    /** The tile of [source] as its map draws it, before any elevation band. */
    private fun plain(
        chain: Interceptor.Chain,
        request: Request,
        key: TileKey,
        source: TileSource,
    ): Response =
        when (val recipe = resolver.resolve(source, key.z, key.x, key.y, scaling())) {
            TileRecipe.None -> {
                stats.record(TileOutcome.OUT_OF_RANGE, key.z)
                reply(request, 404, "zoom outside source levels")
            }

            is TileRecipe.Direct -> {
                serve(chain, request, recipe, request.tag(BulkFetch::class.java))
            }

            else -> {
                compose(chain, request, key, recipe)
            }
        }

    /**
     * A tile of a layer drawn in an elevation band (P20). The elevation tile under it (`terrarium`, at most zoom
     * [DemWindow.MAX_DEM_ZOOM], read like any tile: store, offline mode, server) says whether the band covers none of
     * it (no tile: nothing to read), all of it (the plain tile, untouched) or part of it (the plain tile cut out along
     * the contour, never stored). Without the elevations (offline and not stored), the layer shows nothing here.
     */
    private fun masked(
        chain: Interceptor.Chain,
        request: Request,
        key: TileKey,
        source: TileSource,
        mask: ElevationMask,
    ): Response {
        val start = System.nanoTime()
        val demSource = sources.find(demSourceId) ?: return plain(chain, request, key, source)
        val window = DemWindow.of(key.z, key.x, key.y)
        var failure: Response? = null
        val dem =
            masker.dem("${window.demZ}/${window.demX}/${window.demY}") {
                val leaf = resolver.resolve(demSource, window.demZ, window.demX, window.demY, ScalingPolicy.OFF)
                if (leaf !is TileRecipe.Direct) {
                    null
                } else {
                    serve(chain, request, leaf).use { response ->
                        if (response.isSuccessful) {
                            response.body?.bytes()?.takeIf { it.isNotEmpty() }
                        } else {
                            if (response.code !=
                                HTTP_NOT_FOUND
                            ) {
                                failure = reply(request, response.code, response.message)
                            }
                            null
                        }
                    }
                }
            } ?: return failure ?: reply(request, HTTP_NOT_FOUND, "no elevation here")
        val demReady = System.nanoTime()
        val (low, high) = MaskRaster.range(dem.elevations, dem.size, window)
        val coverage = mask.coverage(low, high)
        if (coverage == ElevationMask.Coverage.NONE) {
            stats.record(TileOutcome.MASKED, key.z)
            log?.invoke("mask z=${key.z} coverage=none dem_ms=${millis(demReady - start)}")
            return reply(request, HTTP_NOT_FOUND, "outside the elevation band")
        }
        val tile = plain(chain, request, key, source)
        if (coverage == ElevationMask.Coverage.ALL || !tile.isSuccessful) {
            log?.invoke(
                "mask z=${key.z} coverage=${coverage.name.lowercase()} total_ms=${millis(System.nanoTime() - start)}",
            )
            return tile
        }
        val bytes = tile.use { it.body?.bytes() }?.takeIf { it.isNotEmpty() }
        val tileReady = System.nanoTime()
        val image = bytes?.let { masker.mask(it, dem, window, mask) }
        val demMs = millis(demReady - start)
        val tileMs = millis(tileReady - demReady)
        log?.invoke(
            "mask z=${key.z} coverage=part dem_ms=$demMs tile_ms=$tileMs total_ms=${millis(System.nanoTime() - start)}",
        )
        if (image == null) return reply(request, HTTP_NOT_FOUND, "undecodable tile")
        stats.record(TileOutcome.MASKED, key.z)
        return ok(request, image, COMPOSED_CONTENT_TYPE)
    }

    private fun millis(nanos: Long): String = "%.1f".format(java.util.Locale.ROOT, nanos / 1_000_000.0)

    /** A tile built from other tiles: each one is read like a tile of its own map, then they are drawn together. */
    private fun compose(
        chain: Interceptor.Chain,
        request: Request,
        key: TileKey,
        recipe: TileRecipe,
    ): Response {
        var failure: Response? = null
        val image =
            composer.compose(recipe) { leaf ->
                serve(chain, request, leaf).use { response ->
                    if (response.isSuccessful) {
                        response.body?.bytes()?.takeIf { it.isNotEmpty() }
                    } else {
                        if (response.code != HTTP_NOT_FOUND &&
                            failure == null
                        ) {
                            failure = reply(request, response.code, response.message)
                        }
                        null
                    }
                }
            }
        if (image != null) {
            stats.record(TileOutcome.COMPOSED, key.z)
            return ok(request, image, COMPOSED_CONTENT_TYPE)
        }
        // Nothing to draw: a server or network failure is reported as such (MapLibre retries it), otherwise no tile.
        return failure ?: reply(request, HTTP_NOT_FOUND, "no tile to compose")
    }

    /** One real tile: [leaf] of its own map, from the store or the server. */
    private fun serve(
        chain: Interceptor.Chain,
        request: Request,
        leaf: TileRecipe.Direct,
        bulk: BulkFetch? = null,
    ): Response {
        val source = leaf.source
        val level = leaf.level
        val key = TileKey(source.id, leaf.tile.z, leaf.tile.x, leaf.tile.y)

        // A zone download has just checked that the tile is not stored (ADR-058): no second look, and no writing here.
        val stored = if (bulk != null) null else store.get(key)
        // AQX <update-delay>: an expired tile is fetched again, but kept as a fallback if the server fails.
        // A map the user asked to refresh this session counts as expired, once per tile.
        val forceNow = bulk == null && forcedRefresh.shouldRefresh(key)
        val stale = stored != null && (forceNow || stored.isStale(clock(), UpdateDelay.toMillis(level.updateDelay)))
        if (stored != null && (!stale || offline.isOffline())) {
            stats.record(TileOutcome.STORE_HIT, key.z)
            return ok(request, stored.bytes, stored.contentType)
        }
        if (offline.isOffline() || level.isLocalOnly) {
            // Local-only sources (imported .mbtiles) have no server to ask: a miss is simply "no tile here".
            stats.record(TileOutcome.OFFLINE_MISS, key.z)
            return reply(request, 404, "offline: tile not stored")
        }

        val index = rotation.getOrPut(source.id) { AtomicInteger() }.getAndIncrement()
        val realUrl =
            try {
                TileUrlBuilder(source).build(level, key.z, key.x, key.y, index)
            } catch (e: TileUrlException) {
                stats.record(TileOutcome.REJECTED, key.z, error = e.message)
                return reply(request, 502, "bad url template")
            }
        val outgoing =
            buildRequest(realUrl, level) ?: run {
                stats.record(TileOutcome.REJECTED, key.z, url = realUrl, error = "invalid request for $realUrl")
                return reply(request, 502, "invalid tile request")
            }
        val response = download(chain, request, outgoing, key, source.id, level, bulk)
        if (stored != null && !response.isSuccessful) {
            response.close()
            stats.record(TileOutcome.STORE_HIT, key.z)
            return ok(request, stored.bytes, stored.contentType)
        }
        return response
    }

    private fun download(
        chain: Interceptor.Chain,
        original: Request,
        outgoing: Request,
        key: TileKey,
        sourceId: String,
        level: LevelDef,
        bulk: BulkFetch?,
    ): Response {
        val url = outgoing.url.toString()
        // AQX <max-threads>: at most N simultaneous downloads per source (tile servers' usage policies). The newest
        // request goes first, and a cancelled one leaves the queue without a request (ADR-058).
        val gate = gates.getOrPut("$sourceId#${level.maxThreads}") { RecentFirstGate(level.maxThreads) }
        when (gate.acquire(TimeUnit.SECONDS.toNanos(GATE_TIMEOUT_SECONDS)) { chain.call().isCanceled() }) {
            RecentFirstGate.Outcome.ACQUIRED -> {
                Unit
            }

            RecentFirstGate.Outcome.CANCELLED -> {
                throw IOException("Canceled")
            }

            RecentFirstGate.Outcome.TIMED_OUT -> {
                stats.record(TileOutcome.NETWORK_ERROR, key.z, url, error = "too many simultaneous downloads")
                return reply(original, 504, "busy")
            }
        }
        try {
            val response =
                try {
                    chain.proceed(outgoing)
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e
                    return networkFailure(original, key, url, e)
                }
            if (!response.isSuccessful) {
                stats.record(TileOutcome.HTTP_ERROR, key.z, url, httpCode = response.code)
                return response
            }
            val bytes =
                try {
                    response.body?.bytes() ?: ByteArray(0)
                } catch (e: IOException) {
                    if (chain.call().isCanceled()) throw e
                    return networkFailure(original, key, url, e)
                } finally {
                    response.close()
                }
            val contentType = response.header("Content-Type")
            return when {
                bytes.isEmpty() -> {
                    stats.record(TileOutcome.HTTP_ERROR, key.z, url, httpCode = response.code, error = "empty tile")
                    reply(original, 404, "empty tile")
                }

                !isTileContentType(contentType) -> {
                    stats.record(TileOutcome.REJECTED, key.z, url, response.code, "not an image: $contentType")
                    reply(original, 502, "server answer is not an image")
                }

                else -> {
                    val tile = StoredTile(bytes, contentType ?: DEFAULT_CONTENT_TYPE)
                    if (bulk != null) bulk.sink(key, tile) else store.put(key, tile)
                    stats.record(TileOutcome.NETWORK_OK, key.z, url, httpCode = response.code)
                    ok(original, bytes, contentType)
                }
            }
        } finally {
            gate.release()
        }
    }

    private fun networkFailure(
        original: Request,
        key: TileKey,
        url: String,
        e: IOException,
    ): Response {
        stats.record(TileOutcome.NETWORK_ERROR, key.z, url, error = "${e.javaClass.simpleName}: ${e.message}")
        return reply(original, 504, "network error")
    }

    private fun buildRequest(
        url: String,
        level: LevelDef,
    ): Request? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        return try {
            Request
                .Builder()
                .url(httpUrl)
                .get()
                .header("User-Agent", level.userAgent ?: defaultUserAgent)
                .header("Accept", "image/*,*/*;q=0.8")
                .apply {
                    level.referer?.let { header("Referer", it) }
                    level.headers.forEach { (name, value) -> header(name, value) }
                    level.cookie?.let { header("Cookie", it) }
                }.build()
        } catch (e: IllegalArgumentException) {
            null // illegal character in a configured header name or value
        }
    }

    private fun parse(url: HttpUrl): TileKey? {
        val segments = url.pathSegments
        if (segments.size != SEGMENTS) return null
        val z = segments[1].toIntOrNull() ?: return null
        val x = segments[2].toIntOrNull() ?: return null
        val y = segments[3].toIntOrNull() ?: return null
        if (segments[0].isEmpty() || z !in 0..MAX_ZOOM || x < 0 || y < 0) return null
        return TileKey(segments[0], z, x, y)
    }

    private fun isTileContentType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return type.startsWith("image/") || type == "application/octet-stream"
    }

    private fun ok(
        request: Request,
        bytes: ByteArray,
        contentType: String?,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", contentType ?: DEFAULT_CONTENT_TYPE)
            .body(bytes.toResponseBody((contentType ?: DEFAULT_CONTENT_TYPE).toMediaTypeOrNull()))
            .build()

    private fun reply(
        request: Request,
        code: Int,
        message: String,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(ByteArray(0).toResponseBody(null))
            .build()

    companion object {
        const val DEFAULT_USER_AGENT = "ToolMap/0.1 (+https://github.com/JRRocks/ToolMap)"
        private const val DEFAULT_CONTENT_TYPE = "application/octet-stream"
        private const val SEGMENTS = 4
        private const val MAX_ZOOM = 30
        private const val GATE_TIMEOUT_SECONDS = 30L
        private const val HTTP_NOT_FOUND = 404
        private const val COMPOSED_CONTENT_TYPE = "image/png"

        /** The elevation map the bands of the layers are read from (`builtin-sources.aqx`). */
        const val DEM_SOURCE_ID = "terrarium"
    }
}
