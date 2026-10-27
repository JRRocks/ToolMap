package fr.toolmap.data.tiles

import fr.toolmap.core.source.TileSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.HttpUrl

/**
 * The fake host every map source is declared on. MapLibre requests
 * `https://tiles.toolmap.local/<sourceId>/<z>/<x>/<y>`; [TileInterceptor] answers it from the local
 * store or rewrites it to the real URL of the source. The host is never resolved by DNS.
 */
object TileHost {
    const val HOST = "tiles.toolmap.local"

    /** MapLibre tile URL template (`{z}/{x}/{y}` placeholders) of the source [sourceId]. */
    fun templateFor(sourceId: String): String {
        val base =
            HttpUrl
                .Builder()
                .scheme("https")
                .host(HOST)
                .addPathSegment(sourceId)
                .build()
                .toString()
                .trimEnd('/')
        return "$base/{z}/{x}/{y}"
    }

    fun templateFor(source: TileSource): String = templateFor(source.id)
}

/** Finds a source by id when a tile is requested. */
fun interface SourceLookup {
    fun find(sourceId: String): TileSource?
}

/** A fixed list of sources (built-in ones for now; the imported ones come with P1.7). */
class StaticSourceLookup(
    val sources: List<TileSource>,
) : SourceLookup {
    private val byId = sources.associateBy { it.id }

    override fun find(sourceId: String): TileSource? = byId[sourceId]
}

/** "Local storage only" switch: while on, nothing leaves the device and misses answer 404 at once. */
class OfflineMode(
    initial: Boolean = false,
) {
    private val flow = MutableStateFlow(initial)
    val enabled: StateFlow<Boolean> = flow.asStateFlow()

    fun isOffline(): Boolean = flow.value

    fun set(value: Boolean) {
        flow.value = value
    }
}
