package fr.toolmap.maps

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.TileHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The small picture of each map in the panels: one tile at the preview location of the map (or at the last
 * known position), asked through the same pipeline as the map itself. A tile already stored costs nothing; a
 * missing one is fetched once and stored like any other (and nothing leaves the device in offline mode).
 */
@Singleton
class MapThumbnails
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val repository: SourceRepository,
    ) {
        private val cache = ConcurrentHashMap<String, ImageBitmap>()
        private val failedAt = ConcurrentHashMap<String, Long>()

        /** Where thumbnails look when a map has no preview location of its own; set by the map screen. */
        @Volatile
        var around: LonLatZoom? = null

        /** The thumbnail of [sourceId], or `null` when there is none (yet): the caller draws a placeholder. */
        suspend fun load(sourceId: String): ImageBitmap? {
            cache[sourceId]?.let { return it }
            val failed = failedAt[sourceId]
            if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) return null
            val source = repository.find(sourceId) ?: return null
            val bitmap = withContext(Dispatchers.IO) { runCatching { fetch(source) }.getOrNull() }
            if (bitmap == null) {
                failedAt[sourceId] = System.currentTimeMillis()
            } else {
                failedAt.remove(sourceId)
                cache[sourceId] = bitmap
            }
            return bitmap
        }

        private fun fetch(source: TileSource): ImageBitmap? {
            if (!source.isSupported) return null
            val place =
                source.previewLocation ?: around ?: source.defaultLocation?.let { LonLatZoom(it.lon, it.lat, 0) }
            val zoom =
                (place?.zoom ?: DEFAULT_ZOOM).coerceIn(
                    source.minZoom,
                    source.maxZoom,
                ).coerceAtMost(MAX_THUMB_ZOOM)
            val tile = TileMath.tileAt(place?.lon ?: DEFAULT_LON, place?.lat ?: DEFAULT_LAT, zoom)
            val url =
                HttpUrl
                    .Builder()
                    .scheme("https")
                    .host(TileHost.HOST)
                    .addPathSegment(source.id)
                    .addPathSegment(zoom.toString())
                    .addPathSegment(tile.x.toString())
                    .addPathSegment(tile.y.toString())
                    .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.bytes() ?: return null
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        private companion object {
            const val RETRY_AFTER_MS = 60_000L
            const val DEFAULT_ZOOM = 6
            const val MAX_THUMB_ZOOM = 12
            const val DEFAULT_LON = 2.35
            const val DEFAULT_LAT = 48.85
        }
    }
