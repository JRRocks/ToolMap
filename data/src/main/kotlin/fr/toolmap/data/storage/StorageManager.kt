package fr.toolmap.data.storage

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesFile
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileEntry
import fr.toolmap.data.tiles.TileKey
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** What is stored in one storage (the tiles of one tile set, shared by every map that serves them; ADR-031). */
data class SourceUsage(
    /** Id of the storage: the id of the built-in map or local package it belongs to, or `t-<hash>`. */
    val sourceId: String,
    /** Names of the maps that show these tiles; the id itself when no map of the catalogue does any more. */
    val name: String,
    val tiles: Long,
    val diskBytes: Long,
    val perZoom: Map<Int, Long>,
    /** Ids of the maps of the catalogue that show these tiles (several when they are shared). */
    val usedBy: List<String> = emptyList(),
)

/** A `.mbtiles` file copied to a temporary place and checked, waiting for the user to say where it goes. */
class StagedPackage(
    internal val file: File,
    val info: PackageInfo,
)

/** What an `.mbtiles` file declares about itself. */
data class PackageInfo(
    val name: String?,
    val attribution: String?,
    val format: String?,
    val minZoom: Int,
    val maxZoom: Int,
    val tiles: Long,
    /** The id of the ToolMap source it was exported from, when it was made by this app. */
    val sourceId: String?,
)

/** Where an imported package goes. */
sealed interface ImportTarget {
    /** Merge into the storage of a source that already exists. */
    data class Existing(
        val sourceId: String,
    ) : ImportTarget

    /** Create a local-only map named [name] (no server: it only shows the imported tiles). */
    data class NewLocalMap(
        val name: String,
    ) : ImportTarget
}

class PackageException(
    val reason: Reason,
    message: String,
) : Exception(message) {
    enum class Reason { NOT_MBTILES, UNSUPPORTED_FORMAT, EMPTY, TOO_LARGE, UNREADABLE }
}

/**
 * Everything about the tiles kept on the device: sizes per source, purges, and `.mbtiles` export / import
 * (ADR-003: the files are standard MBTiles, so packages travel between devices and tools).
 */
class StorageManager(
    private val store: MbTilesStore,
    private val repository: SourceRepository,
) {
    fun usage(): List<SourceUsage> {
        val catalogue = repository.sources.value
        return store.storedSourceIds().map { id ->
            val file = store.fileFor(id)
            // The maps that really read these tiles; multi-zoom maps are named through the maps they show.
            val users = store.usersOf(id, catalogue.filterNot { it.isMultiZoom })
            val name = users.joinToString(", ") { it.name }.ifEmpty { repository.find(id)?.name ?: id }
            SourceUsage(id, name, file.tileCount(), file.diskBytes(), file.tileCountByZoom(), users.map { it.id })
        }
    }

    /** Bytes and tiles stored for each map of the catalogue (a storage shared by several maps counts for each). */
    fun usageBySource(usages: List<SourceUsage> = usage()): Map<String, Pair<Long, Long>> {
        val byStorage = usages.associateBy { it.sourceId }
        return repository.sources.value.associate { source ->
            val parts = store.storageIdsOf(source.id).mapNotNull(byStorage::get)
            source.id to (parts.sumOf { it.tiles } to parts.sumOf { it.diskBytes })
        }.filterValues { it.first > 0 }
    }

    /**
     * The storages holding tiles that no map would show any more once [sourceId] is replaced by [replacement]
     * (its address changed in the wizard): what can be purged without touching a map that shares them.
     */
    fun unusedAfterReplacing(
        sourceId: String,
        replacement: TileSource,
    ): Set<String> {
        val old = store.storageIdsOf(sourceId)
        val stillUsed =
            repository.sources.value
                .filter { it.id != sourceId }
                .flatMapTo(HashSet()) { store.storageIdsOf(it.id) } +
                store.keys.storageIdsOf(replacement) { repository.find(it) }
        return old.filterTo(LinkedHashSet()) { it !in stillUsed && (store.peek(it)?.tileCount() ?: 0L) > 0L }
    }

    /**
     * Moves the tiles of files written before ADR-031 (one file per imported map, named after it) to the storage of
     * the tiles they are, so that they are shared with every map serving them, and the shared storages a built-in map
     * took over ([mergeTakenOverStorages]). Files of maps no longer in the catalogue and of local packages are left
     * alone. Returns the number of files merged. Idempotent.
     */
    fun migrateLegacyFiles(): Int {
        var merged = mergeTakenOverStorages()
        for (id in store.storedSourceIds()) {
            if (store.keys.isSharedStorage(id)) continue
            val source = repository.find(id) ?: continue
            if (source.isLocalOnly) continue
            val legacy = store.peek(id) ?: continue
            val zooms = legacy.tileCountByZoom().keys
            val targets = zooms.associateWith { store.storageIdOf(TileKey(id, it, 0, 0)) }
            if (targets.values.all { it == id }) continue
            for ((zoom, target) in targets) {
                if (target == null || target == id) continue
                val destination = store.fileFor(target, source)
                legacy.forEachTile(zoom, BATCH) { batch -> destination.putAll(batch, replace = false) }
                destination.refreshMetadata()
            }
            if (targets.values.none { it == id }) {
                store.deleteSource(id)
            }
            merged++
        }
        return merged
    }

    /**
     * Moves the tiles of a shared storage (`t-<hash>`) into the storage of the built-in map that now serves them: a
     * map imported by hand, then shipped in the catalogue with the same tiles (ADR-069), keeps what was downloaded.
     */
    private fun mergeTakenOverStorages(): Int {
        var merged = 0
        val stored = store.storedSourceIds().toSet()
        for ((hashed, builtinId) in store.keys.takenOverByBuiltins) {
            if (hashed !in stored) continue
            val legacy = store.peek(hashed) ?: continue
            val destination = store.fileFor(builtinId, repository.find(builtinId))
            for (zoom in legacy.tileCountByZoom().keys) {
                legacy.forEachTile(zoom, BATCH) { batch -> destination.putAll(batch, replace = false) }
            }
            destination.refreshMetadata()
            store.deleteSource(hashed)
            merged++
        }
        return merged
    }

    /** Empties a source's storage and gives the space back. */
    fun purge(sourceId: String) {
        val file = store.fileFor(sourceId)
        file.clear()
        file.compact()
    }

    /** Removes the tiles that intersect [area] at the zooms [zooms]; returns how many were removed. */
    fun purgeArea(
        sourceId: String,
        area: Bounds,
        zooms: IntRange,
    ): Int {
        val file = store.fileFor(sourceId)
        val removed = file.deleteInArea(area, zooms)
        file.compact()
        return removed
    }

    /** Removes tiles downloaded before [cutoffMillis]. */
    fun purgeOlderThan(
        sourceId: String,
        cutoffMillis: Long,
    ): Int {
        val file = store.fileFor(sourceId)
        val removed = file.deleteOlderThan(cutoffMillis)
        file.compact()
        return removed
    }

    /** Removes a local-only map entirely: its catalogue entry and its tiles. Other sources are refused. */
    fun deleteLocalMap(sourceId: String): Boolean {
        val source = repository.find(sourceId)
        if (source == null || !source.isLocalOnly || !repository.isImported(sourceId)) return false
        repository.delete(sourceId)
        store.deleteSource(sourceId)
        return true
    }

    /** Writes the MBTiles file of [sourceId] to [out]; returns its size in bytes. */
    fun exportTo(
        sourceId: String,
        out: OutputStream,
    ): Long {
        val file = store.fileFor(sourceId)
        file.refreshMetadata()
        file.checkpoint()
        file.file.inputStream().use { it.copyTo(out) }
        out.flush()
        return file.file.length()
    }

    /** Copies [input] to a temporary file and checks that it is a raster MBTiles file. */
    fun stage(
        input: InputStream,
        maxBytes: Long = MAX_PACKAGE_BYTES,
    ): StagedPackage {
        val temp = File(store.directory, ".import-${UUID.randomUUID()}.tmp")
        store.directory.mkdirs()
        try {
            copyLimited(input, temp, maxBytes)
            return StagedPackage(temp, inspect(temp))
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    fun discard(staged: StagedPackage) {
        staged.file.delete()
    }

    /** Puts the staged package in place; returns the id of the source that now holds the tiles. */
    fun commit(
        staged: StagedPackage,
        target: ImportTarget,
    ): String {
        val sourceId =
            when (target) {
                is ImportTarget.Existing -> target.sourceId
                is ImportTarget.NewLocalMap -> createLocalSource(target.name, staged.info)
            }
        mergeInto(sourceId, staged.file)
        staged.file.delete()
        return sourceId
    }

    private fun createLocalSource(
        name: String,
        info: PackageInfo,
    ): String {
        val id = freeId(slug(name))
        val zooms = ZoomSet.range(info.minZoom, info.maxZoom)
        repository.save(
            TileSource(
                id = id,
                name = name,
                copyright = info.attribution,
                levels = listOf(LevelDef(zooms, listOf(LevelDef.LOCAL_ONLY_SERVER))),
            ),
        )
        return id
    }

    private fun freeId(base: String): String {
        var candidate = base
        var n = 2
        while (repository.find(candidate) != null ||
            File(store.directory, MbTilesStore.fileNameFor(candidate)).exists()
        ) {
            candidate = "$base-$n"
            n++
        }
        return candidate
    }

    private fun mergeInto(
        sourceId: String,
        staged: File,
    ) {
        // The tiles go to the storage of the map at their zoom (ADR-031): when it has a single one, a brand new
        // storage can simply take the file, as long as `tiles` is a real table (not a view).
        val single = store.storageIdsOf(sourceId).singleOrNull()
        if (single != null) {
            val destination = File(store.directory, MbTilesStore.fileNameFor(single))
            store.release(single)
            if (!destination.exists() && isPlainTable(staged)) {
                check(staged.renameTo(destination) || staged.copyTo(destination, overwrite = true).exists()) {
                    "Cannot move package"
                }
                MbTilesFile.open(destination, mapOf("toolmap-source-id" to single)).close()
                return
            }
        }
        val source = SQLiteDatabase.openDatabase(staged.path, null, SQLiteDatabase.OPEN_READONLY) { }
        try {
            source.rawQuery("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles", null).use { c ->
                val batch = ArrayList<TileEntry>(BATCH)
                while (c.moveToNext()) {
                    val z = c.getInt(0)
                    val data = c.getBlob(3) ?: continue
                    batch +=
                        TileEntry(
                            z,
                            c.getInt(1),
                            TileMath.tmsY(c.getInt(2), z),
                            StoredTile(
                                data,
                                MbTilesFile.contentTypeOf(data) ?: "application/octet-stream",
                                StoredTile.UNKNOWN_DATE_MILLIS,
                            ),
                        )
                    if (batch.size >= BATCH) {
                        store.putAll(sourceId, batch)
                        batch.clear()
                    }
                }
                store.putAll(sourceId, batch)
            }
        } finally {
            source.close()
        }
        store.storageIdsOf(sourceId).forEach { store.peek(it)?.refreshMetadata() }
    }

    private fun isPlainTable(file: File): Boolean =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) { }.use { db ->
            db.rawQuery("SELECT type FROM sqlite_master WHERE name = 'tiles'", null).use {
                it.moveToFirst() &&
                    it.getString(0) == "table"
            }
        }

    private fun inspect(file: File): PackageInfo {
        val db =
            try {
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY) { }
            } catch (e: SQLiteException) {
                throw PackageException(PackageException.Reason.NOT_MBTILES, "not an SQLite file")
            }
        db.use {
            try {
                val metadata =
                    it.rawQuery("SELECT name, value FROM metadata", null).use { c ->
                        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1).orEmpty()) }
                    }
                val stats =
                    it.rawQuery("SELECT COUNT(*), MIN(zoom_level), MAX(zoom_level) FROM tiles", null).use { c ->
                        c.moveToFirst()
                        Triple(c.getLong(0), c.getInt(1), c.getInt(2))
                    }
                if (stats.first == 0L) throw PackageException(PackageException.Reason.EMPTY, "no tile in the file")
                val format = metadata["format"]?.lowercase()
                if (format != null && format !in RASTER_FORMATS) {
                    throw PackageException(
                        PackageException.Reason.UNSUPPORTED_FORMAT,
                        "format $format is not a raster map",
                    )
                }
                val sample =
                    it.rawQuery("SELECT tile_data FROM tiles LIMIT 1", null).use { c ->
                        if (c.moveToFirst()) c.getBlob(0) else null
                    }
                if (sample == null || MbTilesFile.contentTypeOf(sample) == null) {
                    throw PackageException(
                        PackageException.Reason.UNSUPPORTED_FORMAT,
                        "tiles are not PNG, JPEG or WebP images",
                    )
                }
                return PackageInfo(
                    name = metadata["name"]?.takeIf { n -> n.isNotBlank() },
                    attribution = metadata["attribution"]?.takeIf { n -> n.isNotBlank() },
                    format = format,
                    minZoom = stats.second,
                    maxZoom = stats.third,
                    tiles = stats.first,
                    sourceId = metadata["toolmap-source-id"]?.takeIf { n -> n.isNotBlank() },
                )
            } catch (e: SQLiteException) {
                throw PackageException(PackageException.Reason.NOT_MBTILES, "missing metadata or tiles table")
            }
        }
    }

    private fun copyLimited(
        input: InputStream,
        target: File,
        maxBytes: Long,
    ) {
        var total = 0L
        try {
            target.outputStream().use { out ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total >
                        maxBytes
                    ) {
                        throw PackageException(PackageException.Reason.TOO_LARGE, "more than $maxBytes bytes")
                    }
                    out.write(buffer, 0, n)
                }
            }
        } catch (e: IOException) {
            throw PackageException(PackageException.Reason.UNREADABLE, e.message ?: "read error")
        }
    }

    companion object {
        /** Packages above 4 GB are refused (FAT32 limit, and no phone import takes that long). */
        const val MAX_PACKAGE_BYTES = 4L * 1024 * 1024 * 1024
        private val RASTER_FORMATS = setOf("png", "jpg", "jpeg", "webp")
        private const val BATCH = 400
        private const val BUFFER = 64 * 1024

        /** A source id from a display name: lower case, letters and digits separated by dashes. */
        fun slug(name: String): String {
            val s =
                name.lowercase().map {
                    if (it.isLetterOrDigit() &&
                        it.code < ASCII_LIMIT
                    ) {
                        it
                    } else {
                        '-'
                    }
                }.joinToString("")
            return s.replace(Regex("-+"), "-").trim('-').take(MAX_SLUG).ifEmpty { "map" }
        }

        private const val ASCII_LIMIT = 128
        private const val MAX_SLUG = 40
    }
}
