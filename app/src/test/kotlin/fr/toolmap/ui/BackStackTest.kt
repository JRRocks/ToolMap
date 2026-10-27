package fr.toolmap.ui

import org.junit.Test
import kotlin.test.assertEquals

class BackStackTest {
    private val map = "map"

    @Test
    fun `back shows the screen a screen was opened from`() {
        var stack = listOf(map)
        stack = BackStack.open(stack, "settings", map)
        stack = BackStack.open(stack, "backup", map)
        assertEquals(listOf(map, "settings", "backup"), stack)
        stack = BackStack.back(stack)
        assertEquals("settings", stack.last())
        stack = BackStack.back(stack)
        assertEquals(listOf(map), stack)
    }

    @Test
    fun `back on the map leaves the stack as it is, for the map to handle`() {
        assertEquals(listOf(map), BackStack.back(listOf(map)))
    }

    @Test
    fun `a screen opened from two places goes back to the one it came from`() {
        // « Télécharger autour de cette trace » from the detail of a track, and Downloads from the map.
        val fromDetail = BackStack.open(listOf(map, "tracks", "detail"), "downloads", map)
        assertEquals("detail", BackStack.back(fromDetail).last())
        val fromMap = BackStack.open(listOf(map), "downloads", map)
        assertEquals(map, BackStack.back(fromMap).last())
    }

    @Test
    fun `a screen already open is not stacked twice`() {
        val stack = BackStack.open(listOf(map, "tracks", "detail"), "tracks", map)
        assertEquals(listOf(map, "tracks"), stack)
        assertEquals(listOf(map, "tracks"), BackStack.open(stack, "tracks", map))
    }

    @Test
    fun `opening the map closes every screen`() {
        assertEquals(listOf(map), BackStack.open(listOf(map, "tracks", "detail"), map, map))
    }

    @Test
    fun `replace swaps the screen shown and keeps the one under it`() {
        val stack = BackStack.replace(listOf(map, "available", "wizard"), "storage", map)
        assertEquals(listOf(map, "available", "storage"), stack)
        assertEquals(listOf(map, "storage"), BackStack.replace(listOf(map), "storage", map))
    }
}
