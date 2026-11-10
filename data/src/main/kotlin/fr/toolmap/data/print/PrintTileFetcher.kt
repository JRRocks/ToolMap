package fr.toolmap.data.print

import fr.toolmap.core.problem.Problems
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.ScalingPolicy
import fr.toolmap.core.tiles.TileId
import fr.toolmap.core.tiles.TileRecipe
import fr.toolmap.core.tiles.TileResolver
import fr.toolmap.data.download.BulkPolicy
import fr.toolmap.data.tiles.BitmapTileComposer
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.SourceLookup
import fr.toolmap.data.tiles.TileComposer
import fr.toolmap.data.tiles.TileHost
import fr.toolmap.data.tiles.TileKey
import fr.toolmap.data.tiles.TileStore
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What reading one tile gave. */
sealed interface TileFetch {
    class Found(
        val bytes: ByteArray,
    ) : TileFetch

    /** The source has no tile there (outside its coverage, empty): nothing is missing, there is nothing to draw. */
    data object Empty : TileFetch

    /** The tile could not be read: offline and not stored, network failure, server error. It is missing. */
    data object Unavailable : TileFetch
}

/** Where the print gets its tiles. */
fun interface PrintTileFetcher {
    /** Blocking; called from several threads at once. */
    fun fetch(
        source: TileSource,
        tile: TileId,
    ): TileFetch
}

/**
 * Reads tiles for a print: local storage first (stored tiles never expire here: a map printed offline must not change
 * because a delay ran out), then — online, for sources without a restrictive usage policy — the network through the
 * app's tile client, which adds the source's Referer, headers and cookies and stores the answer.
 *
 * A tile of a multi-zoom map, of a forced zoom or of a zoom the map does not serve is drawn from the real tiles it
 * is made of ([TileResolver], ADR-031), each one read the same way and under the rules of its own server.
 *
 * Community servers (OpenStreetMap, OpenTopoMap) are never reached from here: a print is a bulk read, and their
 * tiles come from the storage or from the limited, slowed-down download offered before the export ([BulkPolicy]).
 */
class HttpPrintTileFetcher(
    private val client: OkHttpClient,
    private val store: TileStore,
    private val offline: OfflineMode,
    sources: SourceLookup = SourceLookup { null },
    private val scaling: () -> ScalingPolicy = { ScalingPolicy() },
    private val composer: TileComposer = BitmapTileComposer(),
    /**
     * The longest one tile may take, waiting for its turn included. The tile client waits 15 s for a connection and
     * 30 s for each read, and tries again on another connection: right for a map on the screen, but a print waits for
     * every tile of a strip, and one silent connection held it for a minute (0.4.224, seen on a phone).
     */
    private val tileTimeoutMillis: Long = TILE_TIMEOUT_MILLIS,
) : PrintTileFetcher {
    private val resolver = TileResolver { id -> sources.find(id) }

    override fun fetch(
        source: TileSource,
        tile: TileId,
    ): TileFetch {
        val recipe = resolver.resolve(source, tile.z, tile.x, tile.y, scaling())
        if (recipe is TileRecipe.Direct) return fetchReal(recipe)
        if (recipe == TileRecipe.None) return TileFetch.Empty
        var unavailable = false
        val image =
            composer.compose(recipe) { leaf ->
                when (val part = fetchReal(leaf)) {
                    is TileFetch.Found -> {
                        part.bytes
                    }

                    TileFetch.Unavailable -> {
                        unavailable = true
                        null
                    }

                    TileFetch.Empty -> {
                        null
                    }
                }
            }
        return when {
            image != null -> TileFetch.Found(image)
            unavailable -> TileFetch.Unavailable
            else -> TileFetch.Empty
        }
    }

    private fun fetchReal(leaf: TileRecipe.Direct): TileFetch {
        val source = leaf.source
        val tile = leaf.tile
        store.get(TileKey(source.id, tile.z, tile.x, tile.y))?.let { return TileFetch.Found(it.bytes) }
        // A local package has no server: a tile it does not hold is missing.
        if (!leaf.level.hasServer) return TileFetch.Unavailable
        if (offline.isOffline() || BulkPolicy.limitsFor(source).maxTiles != null) {
            return TileFetch.Unavailable
        }
        val url: HttpUrl =
            HttpUrl
                .Builder()
                .scheme("https")
                .host(TileHost.HOST)
                .addPathSegment(source.id)
                .addPathSegment(tile.z.toString())
                .addPathSegment(tile.x.toString())
                .addPathSegment(tile.y.toString())
                .build()
        return try {
            val call = client.newCall(Request.Builder().url(url).get().build())
            call.timeout().timeout(tileTimeoutMillis, TimeUnit.MILLISECONDS)
            call.execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val bytes = response.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) TileFetch.Empty else TileFetch.Found(bytes)
                    }

                    // The interceptor answers 404 for "no tile there", the server's own 404 included.
                    response.code == HTTP_NOT_FOUND -> {
                        TileFetch.Empty
                    }

                    else -> {
                        TileFetch.Unavailable
                    }
                }
            }
        } catch (e: IOException) {
            Problems.expected("print.tile.fetch", e, "source" to source.id, "z" to tile.z, "x" to tile.x, "y" to tile.y)
            TileFetch.Unavailable
        }
    }

    private companion object {
        const val HTTP_NOT_FOUND = 404
        const val TILE_TIMEOUT_MILLIS = 20_000L
    }
}
