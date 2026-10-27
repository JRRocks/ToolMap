package fr.toolmap.data.tiles

/** Identifies one tile of one source (XYZ scheme, like the tile URLs). */
data class TileKey(
    val sourceId: String,
    val z: Int,
    val x: Int,
    val y: Int,
)

/** Raw tile bytes as received from the server, with the content type to answer with. */
class StoredTile(
    val bytes: ByteArray,
    val contentType: String,
    val storedAtMillis: Long = System.currentTimeMillis(),
) {
    /** True when the tile was downloaded more than [validityMillis] before [nowMillis] (`null` = never expires). */
    fun isStale(
        nowMillis: Long,
        validityMillis: Long?,
    ): Boolean =
        validityMillis != null && storedAtMillis != UNKNOWN_DATE_MILLIS && nowMillis - storedAtMillis > validityMillis

    companion object {
        /** Download date of tiles that carry none (files made by other tools): they never expire. */
        const val UNKNOWN_DATE_MILLIS = -1L
    }
}

/**
 * Local tile storage consulted by [TileInterceptor] before any network access.
 * Calls are made from OkHttp worker threads: implementations must be thread-safe and may block briefly.
 * [MbTilesStore] is the real implementation; the in-memory one serves tests and the spike.
 */
interface TileStore {
    fun get(key: TileKey): StoredTile?

    fun put(
        key: TileKey,
        tile: StoredTile,
    )
}

/** Bounded in-memory LRU store: when [maxBytes] is exceeded the least recently used tiles are dropped. */
class InMemoryTileStore(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : TileStore {
    private val tiles = LinkedHashMap<TileKey, StoredTile>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var totalBytes = 0L

    @Synchronized
    override fun get(key: TileKey): StoredTile? = tiles[key]

    @Synchronized
    override fun put(
        key: TileKey,
        tile: StoredTile,
    ) {
        if (tile.bytes.size > maxBytes) return
        tiles.put(key, tile)?.let { totalBytes -= it.bytes.size }
        totalBytes += tile.bytes.size
        val iterator = tiles.entries.iterator()
        while (totalBytes > maxBytes && iterator.hasNext()) {
            val eldest = iterator.next()
            // Never evict the tile that was just stored.
            if (eldest.key == key) continue
            totalBytes -= eldest.value.bytes.size
            iterator.remove()
        }
    }

    @Synchronized
    fun sizeBytes(): Long = totalBytes

    @Synchronized
    fun count(): Int = tiles.size

    private companion object {
        const val DEFAULT_MAX_BYTES = 64L * 1024 * 1024
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
    }
}
