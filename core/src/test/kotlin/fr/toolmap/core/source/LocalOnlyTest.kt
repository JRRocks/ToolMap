package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.aqx.AqxWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalOnlyTest {
    private val local =
        TileSource(
            id = "pack",
            name = "Paquet importé",
            levels = listOf(LevelDef(ZoomSet.range(10, 14), listOf(LevelDef.LOCAL_ONLY_SERVER))),
        )

    @Test
    fun `a level whose only server is the offline marker is local only`() {
        assertTrue(LevelDef(ZoomSet.range(1, 5), listOf(LevelDef.LOCAL_ONLY_SERVER)).isLocalOnly)
        assertFalse(LevelDef(ZoomSet.range(1, 5), listOf("https://t/{\$z}/{\$x}/{\$y}.png")).isLocalOnly)
        assertFalse(LevelDef(ZoomSet.range(1, 5), listOf(LevelDef.LOCAL_ONLY_SERVER, "https://t/{\$z}")).isLocalOnly)
        assertFalse(LevelDef(ZoomSet.range(1, 5), emptyList()).isLocalOnly)
    }

    @Test
    fun `a source is local only when all its levels are`() {
        assertTrue(local.isLocalOnly)
        val mixed = local.copy(levels = local.levels + LevelDef(ZoomSet.range(15, 16), listOf("https://t/{\$z}")))
        assertFalse(mixed.isLocalOnly)
        assertFalse(local.copy(levels = emptyList()).isLocalOnly)
    }

    @Test
    fun `no built-in source is local only`() {
        assertTrue(BuiltinSources.load().none { it.isLocalOnly })
    }

    @Test
    fun `a local only source survives an AQX round trip and stays supported`() {
        val result = AqxParser.parse(AqxWriter.write(listOf(local)))
        assertEquals(listOf(local), result.file.sources)
        assertTrue(result.file.sources.single().isSupported)
        assertTrue(result.file.sources.single().isLocalOnly)
    }
}
