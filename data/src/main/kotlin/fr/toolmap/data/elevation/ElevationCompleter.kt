package fr.toolmap.data.elevation

import fr.toolmap.core.elevation.PngException
import fr.toolmap.core.elevation.TerrariumTile
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.data.tiles.TileHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Where Terrarium PNG tiles come from. `null` means this tile is not available (offline, missing, error). */
fun interface ElevationTiles {
    suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
    ): ByteArray?
}

/**
 * Terrarium tiles through the app's tile client: the source `terrarium` goes through the same interceptor as the map
 * (stored tiles first, offline mode, User-Agent), so elevations already downloaded for an area cost nothing.
 */
class SourceElevationTiles(
    private val client: OkHttpClient,
    private val sourceId: String = TERRARIUM_SOURCE_ID,
) : ElevationTiles {
    override suspend fun tile(
        z: Int,
        x: Int,
        y: Int,
    ): ByteArray? =
        withContext(Dispatchers.IO) {
            val url: HttpUrl =
                HttpUrl
                    .Builder()
                    .scheme("https")
                    .host(TileHost.HOST)
                    .addPathSegment(sourceId)
                    .addPathSegment(z.toString())
                    .addPathSegment(x.toString())
                    .addPathSegment(y.toString())
                    .build()
            val call = client.newCall(Request.Builder().url(url).get().build())
            currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response -> if (response.isSuccessful) response.body?.bytes() else null }
            } catch (_: IOException) {
                null
            }
        }

    companion object {
        const val TERRARIUM_SOURCE_ID = "terrarium"
    }
}

/** What [ElevationCompleter.fill] did. [missingTiles] > 0 means some points could not be completed (try again online). */
class CompletionResult(
    val segments: List<List<TrackPoint>>,
    val filled: Int,
    val alreadyHadElevation: Int,
    val missingTiles: Int,
)

/**
 * Gives an elevation to the points that have none, from Terrarium tiles (interpolated between pixel centres). Points
 * that already have an elevation (from the GPS or a barometer) are never touched.
 */
class ElevationCompleter(
    private val tiles: ElevationTiles,
    private val zoom: Int = DEFAULT_ZOOM,
) {
    suspend fun fill(segments: List<List<TrackPoint>>): CompletionResult {
        val needed = LinkedHashMap<TileCoord, MutableList<Pair<Int, Int>>>()
        var had = 0
        segments.forEachIndexed { s, points ->
            points.forEachIndexed { i, p ->
                if (p.ele != null) {
                    had++
                } else {
                    val tile = TileMath.tileAt(p.lon, p.lat, zoom)
                    needed.getOrPut(TileCoord(tile.x, tile.y)) { ArrayList() } += s to i
                }
            }
        }
        val out = segments.map { it.toMutableList() }
        var filled = 0
        var missing = 0
        for ((coord, positions) in needed) {
            currentCoroutineContext().ensureActive()
            val tile =
                tiles.tile(zoom, coord.x, coord.y)?.let {
                    try {
                        TerrariumTile.fromPng(it)
                    } catch (_: PngException) {
                        null
                    }
                }
            if (tile == null) {
                missing++
                continue
            }
            for ((s, i) in positions) {
                val p = out[s][i]
                val (fx, fy) = TerrariumTile.fractionIn(p.lon, p.lat, zoom, coord.x, coord.y)
                out[s][i] = p.copy(ele = tile.elevationAt(fx, fy))
                filled++
            }
        }
        return CompletionResult(out, filled, had, missing)
    }

    private data class TileCoord(
        val x: Int,
        val y: Int,
    )

    companion object {
        /** About 19 m per pixel at the equator, 13 m at 45°: finer than the data's own accuracy. */
        const val DEFAULT_ZOOM = 13
    }
}
