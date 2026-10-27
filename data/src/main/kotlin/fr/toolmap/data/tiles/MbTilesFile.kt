package fr.toolmap.data.tiles

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.tiles.TileMath
import java.io.Closeable
import java.io.File

/** How much of a tile's area is stored, see [MbTilesFile.coverage]. */
enum class Coverage { NONE, PARTIAL, FULL }

/** One tile to write with [MbTilesFile.putAll]. */
class TileEntry(
    val z: Int,
    val x: Int,
    val y: Int,
    val tile: StoredTile,
)

/**
 * One MBTiles file (ADR-003): the standard `metadata` and `tiles` tables (rows in TMS order), so QGIS and other
 * tools can open it, plus the annex table `tm_tile_info` that remembers when each tile was downloaded.
 * All coordinates in this API are XYZ (y from the top); the TMS flip happens here only.
 * Safe to use from several threads (SQLite WAL: parallel readers, serialized writers).
 */
class MbTilesFile private constructor(
    val file: File,
    private val db: SQLiteDatabase,
) : Closeable {
    fun get(
        z: Int,
        x: Int,
        y: Int,
    ): StoredTile? =
        db
            .rawQuery(
                "SELECT t.tile_data, i.stored_at FROM tiles t " +
                    "LEFT JOIN tm_tile_info i ON i.zoom_level = t.zoom_level " +
                    "AND i.tile_column = t.tile_column AND i.tile_row = t.tile_row " +
                    "WHERE t.zoom_level = ? AND t.tile_column = ? AND t.tile_row = ?",
                coordinates(z, x, y),
            ).use { cursor ->
                if (!cursor.moveToFirst()) return null
                val bytes = cursor.getBlob(0) ?: return null
                // Tiles written by another tool have no download date: they never count as expired.
                val storedAt = if (cursor.isNull(1)) StoredTile.UNKNOWN_DATE_MILLIS else cursor.getLong(1)
                StoredTile(bytes, contentTypeOf(bytes) ?: formatContentType() ?: FALLBACK_TYPE, storedAt)
            }

    fun contains(
        z: Int,
        x: Int,
        y: Int,
    ): Boolean =
        db
            .rawQuery(
                "SELECT 1 FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                coordinates(z, x, y),
            ).use { it.moveToFirst() }

    /**
     * What is stored for the tile (z, x, y): the tile itself ([Coverage.FULL]), or at least one of its
     * descendants within [depth] zoom levels ([Coverage.PARTIAL]), or nothing.
     */
    fun coverage(
        z: Int,
        x: Int,
        y: Int,
        depth: Int = DEFAULT_COVERAGE_DEPTH,
    ): Coverage {
        if (contains(z, x, y)) return Coverage.FULL
        for (k in 1..depth) {
            val level = z + k
            if (level > MAX_ZOOM) break
            val span = 1 shl k
            val rowLow = TileMath.tmsY((y + 1) * span - 1, level)
            val rowHigh = TileMath.tmsY(y * span, level)
            val found =
                db
                    .rawQuery(
                        "SELECT 1 FROM tiles WHERE zoom_level = ? AND tile_column BETWEEN ? AND ? " +
                            "AND tile_row BETWEEN ? AND ? LIMIT 1",
                        arrayOf(level, x * span, (x + 1) * span - 1, rowLow, rowHigh).map(Int::toString).toTypedArray(),
                    ).use { it.moveToFirst() }
            if (found) return Coverage.PARTIAL
        }
        return Coverage.NONE
    }

    fun put(
        z: Int,
        x: Int,
        y: Int,
        tile: StoredTile,
    ) = putAll(listOf(TileEntry(z, x, y, tile)))

    /** Writes [entries] in one transaction, replacing tiles already stored unless [replace] is false. */
    fun putAll(
        entries: List<TileEntry>,
        replace: Boolean = true,
    ) {
        if (entries.isEmpty()) return
        val conflict = if (replace) SQLiteDatabase.CONFLICT_REPLACE else SQLiteDatabase.CONFLICT_IGNORE
        db.beginTransactionNonExclusive()
        try {
            for (entry in entries) {
                val row = TileMath.tmsY(entry.y, entry.z)
                db.insertWithOnConflict(
                    "tiles",
                    null,
                    ContentValues().apply {
                        put("zoom_level", entry.z)
                        put("tile_column", entry.x)
                        put("tile_row", row)
                        put("tile_data", entry.tile.bytes)
                    },
                    conflict,
                )
                db.insertWithOnConflict(
                    "tm_tile_info",
                    null,
                    ContentValues().apply {
                        put("zoom_level", entry.z)
                        put("tile_column", entry.x)
                        put("tile_row", row)
                        put("stored_at", entry.tile.storedAtMillis)
                    },
                    conflict,
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        entries.firstOrNull()?.let { rememberFormat(it.tile.bytes) }
    }

    fun delete(
        z: Int,
        x: Int,
        y: Int,
    ) {
        val args = coordinates(z, x, y)
        db.beginTransactionNonExclusive()
        try {
            db.delete("tiles", WHERE_TILE, args)
            db.delete("tm_tile_info", WHERE_TILE, args)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Reads every tile of zoom [z] with its download date, [batchSize] at a time (storage migration). */
    fun forEachTile(
        z: Int,
        batchSize: Int,
        block: (List<TileEntry>) -> Unit,
    ) {
        var lastColumn = -1
        var lastRow = -1
        while (true) {
            val batch =
                db
                    .rawQuery(
                        "SELECT t.tile_column, t.tile_row, t.tile_data, i.stored_at FROM tiles t " +
                            "LEFT JOIN tm_tile_info i ON i.zoom_level = t.zoom_level " +
                            "AND i.tile_column = t.tile_column AND i.tile_row = t.tile_row " +
                            "WHERE t.zoom_level = ? " +
                            "AND (t.tile_column > ? OR (t.tile_column = ? AND t.tile_row > ?)) " +
                            "ORDER BY t.tile_column, t.tile_row LIMIT ?",
                        arrayOf(z, lastColumn, lastColumn, lastRow, batchSize).map(Int::toString).toTypedArray(),
                    ).use { c ->
                        buildList {
                            while (c.moveToNext()) {
                                lastColumn = c.getInt(0)
                                lastRow = c.getInt(1)
                                val bytes = c.getBlob(2) ?: continue
                                val storedAt = if (c.isNull(3)) StoredTile.UNKNOWN_DATE_MILLIS else c.getLong(3)
                                val type = contentTypeOf(bytes) ?: formatContentType() ?: FALLBACK_TYPE
                                add(
                                    TileEntry(
                                        z,
                                        lastColumn,
                                        TileMath.tmsY(lastRow, z),
                                        StoredTile(bytes, type, storedAt),
                                    ),
                                )
                            }
                        }
                    }
            if (batch.isEmpty()) return
            block(batch)
            if (batch.size < batchSize) return
        }
    }

    /** Number of tiles stored. */
    fun tileCount(): Long = scalar("SELECT COUNT(*) FROM tiles") ?: 0L

    /** Number of tiles stored at each zoom level. */
    fun tileCountByZoom(): Map<Int, Long> =
        db.rawQuery("SELECT zoom_level, COUNT(*) FROM tiles GROUP BY zoom_level ORDER BY zoom_level", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getInt(0), c.getLong(1)) }
        }

    /** Sum of the tile payload sizes in bytes (a full scan: use for storage screens, not per tile). */
    fun payloadBytes(): Long = scalar("SELECT COALESCE(SUM(LENGTH(tile_data)), 0) FROM tiles") ?: 0L

    /** Size of the files on disk, including the write-ahead log. */
    fun diskBytes(): Long = file.length() + sibling("-wal").length()

    /**
     * Which tiles of zoom [z] inside the inclusive XYZ ranges are stored, as values built with [TileSet.pack].
     * This feeds the "stored coverage" overlay.
     */
    fun storedTiles(
        z: Int,
        xRange: IntRange,
        yRange: IntRange,
    ): Set<Long> {
        if (xRange.isEmpty() || yRange.isEmpty()) return emptySet()
        val rowLow = TileMath.tmsY(yRange.last, z)
        val rowHigh = TileMath.tmsY(yRange.first, z)
        return db
            .rawQuery(
                "SELECT tile_column, tile_row FROM tiles WHERE zoom_level = ? " +
                    "AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z, xRange.first, xRange.last, rowLow, rowHigh).map(Int::toString).toTypedArray(),
            ).use { c ->
                buildSet {
                    while (c.moveToNext()) add(TileSet.pack(c.getInt(0), TileMath.tmsY(c.getInt(1), z)))
                }
            }
    }

    /** How many tiles of zoom [z] inside the inclusive XYZ ranges are stored (the zone estimate). */
    fun countTiles(
        z: Int,
        xRange: IntRange,
        yRange: IntRange,
    ): Long {
        if (xRange.isEmpty() || yRange.isEmpty()) return 0L
        val rowLow = TileMath.tmsY(yRange.last, z)
        val rowHigh = TileMath.tmsY(yRange.first, z)
        return db
            .rawQuery(
                "SELECT COUNT(*) FROM tiles WHERE zoom_level = ? " +
                    "AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?",
                arrayOf(z, xRange.first, xRange.last, rowLow, rowHigh).map(Int::toString).toTypedArray(),
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
    }

    /** Deletes every tile of the zooms [zooms] whose area intersects [area] (degrees). Returns how many. */
    fun deleteInArea(
        area: Bounds,
        zooms: IntRange,
    ): Int {
        var removed = 0
        db.beginTransactionNonExclusive()
        try {
            for (z in zooms) {
                val a = TileMath.tileAt(area.minX, area.maxY, z)
                val b = TileMath.tileAt(area.maxX, area.minY, z)
                val rowLow = TileMath.tmsY(b.y, z).toString()
                val rowHigh = TileMath.tmsY(a.y, z).toString()
                val args = arrayOf(z.toString(), a.x.toString(), b.x.toString(), rowLow, rowHigh)
                val where = "zoom_level = ? AND tile_column BETWEEN ? AND ? AND tile_row BETWEEN ? AND ?"
                removed += db.delete("tiles", where, args)
                db.delete("tm_tile_info", where, args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return removed
    }

    /** Deletes tiles downloaded before [cutoffMillis]; tiles with an unknown date (imported ones) are kept. */
    fun deleteOlderThan(cutoffMillis: Long): Int {
        db.beginTransactionNonExclusive()
        try {
            val stale =
                db.delete(
                    "tiles",
                    "EXISTS (SELECT 1 FROM tm_tile_info i WHERE i.zoom_level = tiles.zoom_level " +
                        "AND i.tile_column = tiles.tile_column AND i.tile_row = tiles.tile_row " +
                        "AND i.stored_at >= 0 AND i.stored_at < ?)",
                    arrayOf(cutoffMillis.toString()),
                )
            db.delete("tm_tile_info", "stored_at >= 0 AND stored_at < ?", arrayOf(cutoffMillis.toString()))
            db.setTransactionSuccessful()
            return stale
        } finally {
            db.endTransaction()
        }
    }

    /** Empties the file (metadata is kept). */
    fun clear() {
        db.beginTransactionNonExclusive()
        try {
            db.delete("tiles", null, null)
            db.delete("tm_tile_info", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun metadata(): Map<String, String> =
        db.rawQuery("SELECT name, value FROM metadata", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1).orEmpty()) }
        }

    fun setMetadata(
        name: String,
        value: String,
    ) {
        db.insertWithOnConflict(
            "metadata",
            null,
            ContentValues().apply {
                put("name", name)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** Refreshes the `minzoom`, `maxzoom` and `bounds` entries from the tiles actually stored. */
    fun refreshMetadata() {
        val zooms = tileCountByZoom().keys
        if (zooms.isEmpty()) return
        setMetadata("minzoom", zooms.min().toString())
        setMetadata("maxzoom", zooms.max().toString())
        val z = zooms.min()
        db.rawQuery(
            "SELECT MIN(tile_column), MAX(tile_column), MIN(tile_row), MAX(tile_row) FROM tiles WHERE zoom_level = ?",
            arrayOf(z.toString()),
        ).use { c ->
            if (!c.moveToFirst() || c.isNull(0)) return
            val west = TileMath.boundsLonLat(c.getInt(0), TileMath.tmsY(c.getInt(3), z), z)
            val east = TileMath.boundsLonLat(c.getInt(1), TileMath.tmsY(c.getInt(2), z), z)
            setMetadata("bounds", "${west.minX},${west.minY},${east.maxX},${east.maxY}")
        }
    }

    /** Folds the write-ahead log into the main file so it can be copied alone (export, backup). */
    fun checkpoint() {
        db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
    }

    /** Reclaims the space of deleted tiles. */
    fun compact() {
        checkpoint()
        db.execSQL("VACUUM")
    }

    override fun close() {
        if (db.isOpen) db.close()
    }

    private fun rememberFormat(bytes: ByteArray) {
        if ("format" in metadata()) return
        when (contentTypeOf(bytes)) {
            "image/png" -> setMetadata("format", "png")
            "image/jpeg" -> setMetadata("format", "jpg")
            "image/webp" -> setMetadata("format", "webp")
        }
    }

    private fun formatContentType(): String? = metadata()["format"]?.let(::contentTypeOfFormat)

    private fun scalar(sql: String): Long? =
        db.rawQuery(sql, null).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    private fun sibling(suffix: String) = File(file.path + suffix)

    private fun coordinates(
        z: Int,
        x: Int,
        y: Int,
    ): Array<String> = arrayOf(z.toString(), x.toString(), TileMath.tmsY(y, z).toString())

    companion object {
        const val DEFAULT_COVERAGE_DEPTH = 3
        private const val MAX_ZOOM = 22
        private const val WHERE_TILE = "zoom_level = ? AND tile_column = ? AND tile_row = ?"
        private const val FALLBACK_TYPE = "application/octet-stream"

        /**
         * Opens [file], creating it with the MBTiles schema when it does not exist. A file that SQLite cannot read
         * is moved aside (`.corrupt`) and replaced by a fresh one, so one bad file never breaks the map.
         */
        fun open(
            file: File,
            initialMetadata: Map<String, String> = emptyMap(),
        ): MbTilesFile {
            file.parentFile?.mkdirs()
            val db =
                try {
                    openDatabase(file)
                } catch (e: SQLiteException) {
                    val aside = File(file.path + ".corrupt")
                    aside.delete()
                    file.renameTo(aside)
                    File(file.path + "-wal").delete()
                    File(file.path + "-shm").delete()
                    openDatabase(file)
                }
            val opened = MbTilesFile(file, db)
            val existing = opened.metadata()
            for ((name, value) in initialMetadata) if (name !in existing) opened.setMetadata(name, value)
            return opened
        }

        private fun openDatabase(file: File): SQLiteDatabase {
            // The default error handler deletes a corrupt file; ours keeps it so open() can move it aside.
            val db =
                SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY) { }
            try {
                db.enableWriteAheadLogging()
                db.execSQL("CREATE TABLE IF NOT EXISTS metadata (name TEXT, value TEXT)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS metadata_name ON metadata (name)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tiles " +
                        "(zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS tm_tile_info (zoom_level INTEGER, tile_column INTEGER, " +
                        "tile_row INTEGER, stored_at INTEGER NOT NULL, " +
                        "PRIMARY KEY (zoom_level, tile_column, tile_row)) WITHOUT ROWID",
                )
                // Forces SQLite to read the file now, so a corrupt file fails here rather than later.
                db.rawQuery("SELECT COUNT(*) FROM sqlite_master", null).use { it.moveToFirst() }
                return db
            } catch (e: SQLiteException) {
                db.close()
                throw e
            }
        }

        /** Content type of an image payload from its magic bytes, or `null` when it is not PNG, JPEG or WebP. */
        fun contentTypeOf(bytes: ByteArray): String? =
            when {
                bytes.size >= PNG_MAGIC.size && PNG_MAGIC.indices.all { bytes[it] == PNG_MAGIC[it] } -> "image/png"

                bytes.size >= JPEG_MAGIC.size && JPEG_MAGIC.indices.all { bytes[it] == JPEG_MAGIC[it] } -> "image/jpeg"

                bytes.size >= WEBP_MIN_SIZE && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"

                else -> null
            }

        private fun contentTypeOfFormat(format: String): String? =
            when (format.lowercase()) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> null
            }

        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private const val WEBP_MIN_SIZE = 12
    }
}

/** Helpers for sets of tile coordinates packed in a `Long` (x in the high 32 bits, y in the low ones). */
object TileSet {
    fun pack(
        x: Int,
        y: Int,
    ): Long = (x.toLong() shl Int.SIZE_BITS) or (y.toLong() and 0xFFFFFFFFL)

    fun x(packed: Long): Int = (packed shr Int.SIZE_BITS).toInt()

    fun y(packed: Long): Int = packed.toInt()
}
