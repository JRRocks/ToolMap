package fr.toolmap.core.source

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.aqx.AqxWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapRefTest {
    @Test
    fun `a reference round-trips through its server value`() {
        for (ref in listOf(MapRef("ign-maps"), MapRef("ign-maps", 15), MapRef("IGN_MAPS-2", 0), MapRef("é ü/?&", 7))) {
            assertEquals(ref, MapRef.parse(ref.toServer()), ref.toServer())
        }
        assertEquals("toolmap-map:ign-maps?z=15", MapRef("ign-maps", 15).toServer())
    }

    @Test
    fun `ordinary and malformed servers are not references`() {
        assertNull(MapRef.parse("https://tile.openstreetmap.org/{\$z}/{\$x}/{\$y}.png"))
        assertNull(MapRef.parse(LevelDef.LOCAL_ONLY_SERVER))
        assertNull(MapRef.parse("toolmap-map:"))
        assertNull(MapRef.parse("toolmap-map:osm?z=abc"))
        assertNull(MapRef.parse("toolmap-map:osm?z=99"))
        assertNull(MapRef.parse("toolmap-map:bad%2"))
    }

    @Test
    fun `a multi-zoom source survives an AQX round trip`() {
        val source =
            TileSource(
                id = "mz-test",
                name = "Test multi-zoom",
                levels =
                    listOf(
                        LevelDef(ZoomSet.range(1, 11), listOf(MapRef("ign-maps").toServer())),
                        LevelDef(ZoomSet.range(12, 18), listOf(MapRef("ign-maps", 15).toServer())),
                    ),
            )
        val back = AqxParser.parse(AqxWriter.write(listOf(source))).file.sources.single()
        assertEquals(source, back)
        assertTrue(back.isMultiZoom)
        assertEquals(MapRef("ign-maps", 15), back.levelFor(13)?.mapRef)
    }
}
