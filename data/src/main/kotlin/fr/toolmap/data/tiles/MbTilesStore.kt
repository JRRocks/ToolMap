package fr.toolmap.data.tiles

import android.database.sqlite.SQLiteException
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.StorageKeys
import fr.toolmap.core.source.TileSource
import java.io.Closeable
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The tile store of the app: one [MbTilesFile] per tile set in [directory] (ADR-003, ADR-031), opened on first use.
 *
 * A [TileKey] names the map the tile was asked for; the file it lives in is the storage of the tiles that map really
 * serves at that zoom ([StorageKeys]): every map serving the same tiles (the same IGN map described by several
 * `.aqx` files, a multi-zoom map showing it) reads and writes the same file, so nothing is downloaded twice. The
 * files of the built-in maps keep their names (`osm.mbtiles`, `ign-maps.mbtiles`), other tile sets are named
 * `t-<hash>.mbtiles`, a local package keeps the id of its map. A key whose map is unknown uses its own id.
 *
 * It is a cache first: a storage failure (full disk, closed database) never breaks tile serving, the tile is
 * simply not stored or not found.
 */
class MbTilesStore(
    val directory: File,
    private val sources: SourceLookup = SourceLookup { null },
    private val onError: (Throwable) -> Unit = {},
    val keys: StorageKeys = StorageKeys.BUILTIN,
    /**
     * Whether [directory] can be used now. A card that is not mounted is not: nothing is read or written then, so that
     * no tile lands in a folder the card would hide (ADR-042).
     */
    private val available: () -> Boolean = { true },
) : TileStore,
    Closeable {
    private val files = ConcurrentHashMap<String, MbTilesFile>()

    // Storage of the ordinary levels already seen (pure function of the source and the zoom; refs are not cached).
    private val storageCache = ConcurrentHashMap<Pair<TileSource, Int>, String>()

    override fun get(key: TileKey): StoredTile? =
        guarded(null) {
            val (storage, source) = storageOf(key) ?: return@guarded null
            fileFor(storage, source).get(key.z, key.x, key.y)
        }

    override fun put(
        key: TileKey,
        tile: StoredTile,
    ) = guarded(Unit) {
        val (storage, source) = storageOf(key) ?: return@guarded
        fileFor(storage, source).put(key.z, key.x, key.y, tile)
    }

    /** True when the tile of [key] is stored (a download skips it). */
    fun contains(key: TileKey): Boolean =
        guarded(false) {
            val (storage, source) = storageOf(key) ?: return@guarded false
            fileFor(storage, source).contains(key.z, key.x, key.y)
        }

    /** Stores a batch of tiles of the map [sourceId] (zone downloads, imports), one transaction per storage. */
    fun putAll(
        sourceId: String,
        entries: List<TileEntry>,
    ) = guarded(Unit) {
        for ((zoom, batch) in entries.groupBy { it.z }) {
            val (storage, source) = storageOf(TileKey(sourceId, zoom, 0, 0)) ?: continue
            fileFor(storage, source).putAll(batch)
        }
    }

    /**
     * The storage the tile of [key] lives in, or `null` when such a tile is never stored (a forced zoom of a
     * multi-zoom map, composed from other tiles).
     */
    fun storageIdOf(key: TileKey): String? = storageOf(key)?.first

    /** Every storage the tiles shown by the map [sourceId] can come from (several for a multi-zoom map). */
    fun storageIdsOf(sourceId: String): Set<String> {
        val source = sources.find(sourceId) ?: return setOf(sourceId)
        return keys.storageIdsOf(source, sources::find)
    }

    /** The maps of [catalogue] whose tiles are (at least partly) kept in [storageId]. */
    fun usersOf(
        storageId: String,
        catalogue: List<TileSource>,
    ): List<TileSource> = catalogue.filter { storageId in keys.storageIdsOf(it, sources::find) || it.id == storageId }

    private fun storageOf(key: TileKey): Pair<String, TileSource?>? {
        val source = sources.find(key.sourceId) ?: return key.sourceId to null
        val level = source.levelFor(key.z)
        val storage =
            when {
                level != null && level.mapRef == null -> {
                    storageCache.getOrPut(source to key.z) { keys.storageIdOf(source, level) ?: key.sourceId }
                }

                // A zoom the map does not declare (a package imported with more zooms): kept with its nearest level.
                level == null && !source.isMultiZoom -> {
                    storageCache.getOrPut(source to key.z) {
                        nearestLevel(source, key.z)?.let { keys.storageIdOf(source, it) } ?: key.sourceId
                    }
                }

                else -> {
                    keys.storageIdFor(source, key.z, sources::find)
                }
            }
        return storage?.let { it to source }
    }

    private fun nearestLevel(
        source: TileSource,
        zoom: Int,
    ): LevelDef? =
        source.levels
            .filter { it.mapRef == null }
            .minByOrNull { level -> level.zooms.toList().minOfOrNull { abs(it - zoom) } ?: Int.MAX_VALUE }

    /** The file of the storage [storageId], created on first use with the descriptive metadata of [origin]. */
    fun fileFor(
        storageId: String,
        origin: TileSource? = null,
    ): MbTilesFile =
        files.computeIfAbsent(storageId) { id ->
            check(available()) { "The tile folder is not available" }
            val described = sources.find(id)?.takeIf { !it.isMultiZoom } ?: origin
            MbTilesFile.open(File(directory, fileNameFor(id)), metadataOf(id, described))
        }

    /** The file of [storageId] if it exists, without creating it (read-only uses such as the coverage overlay). */
    fun peek(storageId: String): MbTilesFile? {
        files[storageId]?.let { return it }
        return if (File(directory, fileNameFor(storageId)).isFile) fileFor(storageId) else null
    }

    /** Ids of the storages that have a file in [directory] (recovered from the metadata). */
    fun storedSourceIds(): List<String> =
        directory
            .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            .orEmpty()
            .map(::sourceIdOf)
            .sorted()

    /** Removes everything stored in [storageId]: the open handle, the file and its journal. */
    fun deleteSource(storageId: String) {
        files.remove(storageId)?.close()
        val base = File(directory, fileNameFor(storageId))
        base.delete()
        File(base.path + "-wal").delete()
        File(base.path + "-shm").delete()
    }

    /** Closes the handle of [storageId] (before copying or replacing its file); it reopens on next use. */
    fun release(storageId: String) {
        files.remove(storageId)?.close()
    }

    override fun close() {
        files.values.forEach { it.close() }
        files.clear()
    }

    // The real id is in the metadata (file names are sanitized); the plain file name is the fallback.
    private fun sourceIdOf(file: File): String =
        runCatching { MbTilesFile.open(file).use { it.metadata()["toolmap-source-id"] } }.getOrNull()
            ?: file.name.removeSuffix(EXTENSION)

    private fun metadataOf(
        storageId: String,
        source: TileSource?,
    ): Map<String, String> =
        buildMap {
            if (source != null) {
                put("name", source.name)
                put("type", if (source.isLayer) "overlay" else "baselayer")
                put("version", "1")
                source.copyright?.let { put("attribution", it) }
            }
            put("toolmap-source-id", storageId)
        }

    private fun <T> guarded(
        fallback: T,
        block: () -> T,
    ): T {
        // Without its folder the store is simply empty: it is not an error to report for every tile.
        if (!available()) return fallback
        return try {
            block()
        } catch (e: SQLiteException) {
            onError(e)
            fallback
        } catch (e: IllegalStateException) {
            // "attempt to re-open an already-closed object": the file was released while a tile was requested.
            onError(e)
            fallback
        }
    }

    companion object {
        const val EXTENSION = ".mbtiles"
        private val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,64}")

        /** File name for a source id: the id itself when it is filename-safe, otherwise a sanitized form + hash. */
        fun fileNameFor(sourceId: String): String =
            if (SAFE_NAME.matches(sourceId)) {
                sourceId + EXTENSION
            } else {
                sanitize(sourceId) + "-" + Integer.toHexString(sourceId.hashCode()) + EXTENSION
            }

        private fun sanitize(text: String): String = text.replace(Regex("[^A-Za-z0-9_-]"), "_").take(MAX_NAME)

        private const val MAX_NAME = 48
    }
}
