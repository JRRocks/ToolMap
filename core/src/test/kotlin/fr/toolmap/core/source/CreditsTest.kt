package fr.toolmap.core.source

import kotlin.test.Test
import kotlin.test.assertEquals

class CreditsTest {
    @Test
    fun `the credits of the built-in maps fit on one short line`() {
        val shown =
            listOf(
                "(c) OpenStreetMap contributors",
                "(c) OpenStreetMap contributors, SRTM | Style : (c) OpenTopoMap (CC-BY-SA)",
                "IGN",
            )
        assertEquals("© OSM · SRTM · OpenTopoMap · IGN", Credits.short(shown))
    }

    @Test
    fun `a name is kept once whatever its case and licence`() {
        assertEquals(
            "© OSM · Mapzen · AWS Open Data Terrain Tiles",
            Credits.short(
                listOf("OpenStreetMap contributors (CC-BY-SA)", "openstreetmap", "Mapzen, AWS Open Data Terrain Tiles"),
            ),
        )
    }

    @Test
    fun `no credits give an empty line`() {
        assertEquals("", Credits.short(emptyList()))
        assertEquals("", Credits.short(listOf(" (c) ", "")))
    }
}
