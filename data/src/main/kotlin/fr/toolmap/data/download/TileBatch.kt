package fr.toolmap.data.download

import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileEntry
import fr.toolmap.data.tiles.TileKey

/**
 * The tiles a zone download has fetched and not written yet. They are written together (one transaction per zoom in
 * the store) once [BATCH_TILES] have come or [BATCH_NANOS] have passed since the first one (ADR-058). A batch lost to a
 * crash is downloaded again by the next run, which skips what is stored.
 */
internal class TileBatch(
    private val write: (List<TileEntry>) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var pending = ArrayList<TileEntry>()
    private var firstAt = 0L

    fun add(
        key: TileKey,
        tile: StoredTile,
    ) {
        val ready =
            synchronized(this) {
                if (pending.isEmpty()) firstAt = nanoTime()
                pending += TileEntry(key.z, key.x, key.y, tile)
                if (pending.size >= BATCH_TILES) take() else null
            }
        ready?.let(write)
    }

    /** Writes the tiles waiting if the oldest has waited [BATCH_NANOS]. */
    fun flushIfDue() {
        val ready =
            synchronized(this) {
                if (pending.isNotEmpty() && nanoTime() - firstAt >= BATCH_NANOS) take() else null
            }
        ready?.let(write)
    }

    /** Writes the tiles waiting, whatever their number. */
    fun flush() {
        val ready = synchronized(this) { if (pending.isEmpty()) null else take() }
        ready?.let(write)
    }

    private fun take(): List<TileEntry> = pending.also { pending = ArrayList() }

    companion object {
        const val BATCH_TILES = 64
        const val BATCH_NANOS = 1_000_000_000L
    }
}
