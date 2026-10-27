package fr.toolmap.data.download

import fr.toolmap.data.tiles.StoredTile
import fr.toolmap.data.tiles.TileEntry
import fr.toolmap.data.tiles.TileKey
import kotlin.test.Test
import kotlin.test.assertEquals

class TileBatchTest {
    private val written = mutableListOf<List<TileEntry>>()
    private var now = 0L
    private val batch = TileBatch(write = { written += it }, nanoTime = { now })
    private val tile = StoredTile(byteArrayOf(1, 2, 3), "image/png")

    private fun add(x: Int) = batch.add(TileKey("s", 12, x, 5), tile)

    @Test
    fun `64 tiles are written together`() {
        repeat(63) { add(it) }
        assertEquals(0, written.size)
        add(63)
        assertEquals(listOf(64), written.map { it.size })
    }

    @Test
    fun `a few tiles wait for the second to pass`() {
        add(0)
        add(1)
        now += 999_000_000
        batch.flushIfDue()
        assertEquals(0, written.size, "not due yet")
        now += 1_000_000
        batch.flushIfDue()
        assertEquals(listOf(2), written.map { it.size })
        batch.flushIfDue()
        assertEquals(1, written.size, "nothing left to write")
    }

    @Test
    fun `the second counts from the first tile of the batch`() {
        now = 5_000_000_000
        add(0)
        now += 600_000_000
        add(1)
        now += 450_000_000
        batch.flushIfDue()
        assertEquals(listOf(2), written.map { it.size })
    }

    @Test
    fun `flush writes what is left and an empty batch writes nothing`() {
        batch.flush()
        assertEquals(0, written.size)
        add(0)
        batch.flush()
        assertEquals(listOf(1), written.map { it.size })
        assertEquals(listOf(0), written.single().map { it.x })
    }
}
