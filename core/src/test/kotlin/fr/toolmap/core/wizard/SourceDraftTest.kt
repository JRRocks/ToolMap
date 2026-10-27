package fr.toolmap.core.wizard

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.aqx.AqxWriter
import fr.toolmap.core.geo.LonLatZoom
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.core.tiles.TileMath
import fr.toolmap.core.tiles.TileUrlBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceDraftTest {
    private val good =
        SourceDraft(
            name = "Ma carte",
            urls = listOf("https://tiles.example/{\$z}/{\$x}/{\$y}.png"),
            zoomSpec = "0-17",
        )

    private fun issues(draft: SourceDraft) = draft.validate().toSet()

    @Test
    fun `a minimal draft is valid`() {
        assertTrue(good.isValid)
        assertEquals(emptyList(), good.validate())
    }

    @Test
    fun `each mistake has its own code`() {
        assertEquals(setOf(DraftIssue.NAME_EMPTY), issues(good.copy(name = " ")))
        assertTrue(DraftIssue.URL_EMPTY in issues(good.copy(urls = listOf(""))))
        assertTrue(DraftIssue.URL_NOT_HTTP in issues(good.copy(urls = listOf("ftp://x/{\$z}/{\$x}/{\$y}"))))
        assertTrue(DraftIssue.NO_TILE_PLACEHOLDER in issues(good.copy(urls = listOf("https://x/tiles.png"))))
        assertTrue(DraftIssue.NO_TILE_PLACEHOLDER in issues(good.copy(urls = listOf("https://x/{\$z}/{\$x}.png"))))
        assertTrue(
            DraftIssue.UNKNOWN_PLACEHOLDER in
                issues(good.copy(urls = listOf("https://{\$serverpart}/{\$z}/{\$x}/{\$y}"))),
        )
        assertTrue(
            DraftIssue.MISSING_PARAM in issues(good.copy(urls = listOf("https://x/{\$z}/{\$x}/{\$y}?k={\$param:k}"))),
        )
        assertTrue(DraftIssue.ZOOM_INVALID in issues(good.copy(zoomSpec = "a-b")))
        assertTrue(DraftIssue.TILE_SIZE_INVALID in issues(good.copy(tileSize = 300)))
        assertTrue(DraftIssue.UPDATE_DELAY_INVALID in issues(good.copy(updateDelay = "soon")))
        assertTrue(DraftIssue.HEADER_INVALID in issues(good.copy(headersText = "no colon here")))
        assertTrue(DraftIssue.THREADS_INVALID in issues(good.copy(maxThreads = 0)))
        assertTrue(DraftIssue.OPACITY_INVALID in issues(good.copy(opacity = 101)))
    }

    @Test
    fun `quadkey and tms and bbox templates count as tile placeholders`() {
        for (url in listOf(
            "https://x/{\$q}.jpg",
            "https://x/{\$z}/{\$x}/{\$!y}.png",
            "https://x/wms?BBOX={\$bbox}",
            "https://x/{\$ibbox}",
        )) {
            assertTrue(DraftIssue.NO_TILE_PLACEHOLDER !in issues(good.copy(urls = listOf(url))), url)
        }
    }

    @Test
    fun `warnings do not block saving and come last`() {
        val draft = good.copy(urls = listOf("http://plain.example/{\$z}/{\$x}/{\$y}.png"), insecure = true)
        assertTrue(draft.isValid)
        // `insecure` is only carried over from an imported file: it is not applied, so nothing to warn about.
        assertEquals(setOf(DraftIssue.CLEARTEXT), issues(draft))
        val mixed = draft.copy(name = "")
        assertEquals(DraftIssue.NAME_EMPTY, mixed.validate().first())
    }

    @Test
    fun `a key left in the url is flagged and extractKeys moves it to a parameter`() {
        val draft = good.copy(urls = listOf("https://x/{\$z}/{\$x}/{\$y}.png?apikey=secret123"))
        assertTrue(DraftIssue.KEY_IN_URL in issues(draft))
        val moved = draft.extractKeys()
        assertEquals(listOf("https://x/{\$z}/{\$x}/{\$y}.png?apikey={\$param:apikey}"), moved.urls)
        assertEquals(mapOf("apikey" to "secret123"), moved.params)
        assertTrue(moved.validate().isEmpty())
        assertEquals(mapOf("apikey" to "secret123"), moved.toSource().params)
    }

    @Test
    fun `unused parameters are not written to the source`() {
        val source = good.copy(params = mapOf("old" to "x")).toSource()
        assertTrue(source.params.isEmpty())
    }

    @Test
    fun `http settings reach the level`() {
        val source =
            good
                .copy(
                    referer = " https://data.geopf.fr/ ",
                    userAgent = "ToolMap-test",
                    headersText = "X-One: 1\n\nAccept-Language:  fr ",
                    cookie = "a=b",
                    maxThreads = 2,
                    updateDelay = "2w",
                    tileSize = 512,
                    type = " Topo ",
                    isLayer = true,
                    opacity = 60,
                    copyright = " © Moi ",
                ).toSource()
        val level = source.levels.single()
        assertEquals("https://data.geopf.fr/", level.referer)
        assertEquals("ToolMap-test", level.userAgent)
        assertEquals(listOf("X-One" to "1", "Accept-Language" to "fr"), level.headers)
        assertEquals("a=b", level.cookie)
        assertEquals(2, level.maxThreads)
        assertEquals("2W", level.updateDelay)
        assertEquals(512, level.tileSize)
        assertEquals("topo", source.type)
        assertTrue(source.isLayer)
        assertEquals(60, source.opacity)
        assertEquals("© Moi", source.copyright)
        assertEquals(ZoomSet.range(0, 17), level.zooms)
    }

    @Test
    fun `an invalid draft cannot become a source`() {
        assertFailsWith<IllegalStateException> { good.copy(name = "").toSource() }
    }

    @Test
    fun `draft survives the aqx round trip and back into a draft`() {
        val draft =
            good.copy(
                urls = listOf("https://{a,b}.example/{\$z}/{\$x}/{\$y}.png?k={\$param:k}"),
                params = mapOf("k" to "v"),
                referer = "https://ref.example/",
                headersText = "X-A: 1",
                zoomSpec = "2-5,9",
                previewLocation = LonLatZoom(2.35, 48.85, 9),
                updateDelay = "3M",
                bboxCrs = "EPSG:3857",
            )
        val source = draft.copy(id = "ma-carte").toSource()
        val parsed = AqxParser.parse(AqxWriter.write(listOf(source))).file.sources.single()
        assertEquals(source, parsed)
        val back = assertNotNull(SourceDraft.fromSource(parsed))
        assertEquals(draft.copy(id = "ma-carte"), back)
    }

    @Test
    fun `a source with several levels cannot be edited in the wizard`() {
        val two =
            good.toSource().let {
                it.copy(
                    levels =
                        it.levels + it.levels.first().copy(zooms = ZoomSet.range(18, 19)),
                )
            }
        assertNull(SourceDraft.fromSource(two))
        assertNull(SourceDraft.fromSource(good.toSource().copy(unsupportedReason = "projection")))
    }

    @Test
    fun `ids come from the name`() {
        assertEquals("ma-carte-ete", SourceIds.fromName("  Ma carte — Été !  "))
        assertEquals("map", SourceIds.fromName("???"))
        assertEquals(40, SourceIds.fromName("a".repeat(100)).length)
        assertEquals("x", SourceIds.unique("x") { false })
        assertEquals("x-3", SourceIds.unique("x") { it == "x" || it == "x-2" })
        assertEquals("ma-carte", good.toSource().id, "id is derived from the name when left blank")
    }

    @Test
    fun `preview tile uses the preview location or Paris`() {
        val zooms = ZoomSet.range(0, 17)
        val paris = PreviewTile.choose(zooms, null)
        assertEquals(12, paris.z)
        assertEquals(TileMath.tileAt(2.35, 48.85, 12).x, paris.x)
        assertEquals(TileMath.tileAt(2.35, 48.85, 12).y, paris.y)
        val custom = PreviewTile.choose(zooms, LonLatZoom(-1.55, 47.2, 10))
        assertEquals(10, custom.z)
        // The location's zoom is outside what the source serves: the closest served zoom is used.
        assertEquals(14, PreviewTile.choose(ZoomSet.range(6, 14), LonLatZoom(2.35, 48.85, 17)).z)
        assertEquals(12, PreviewTile.choose(ZoomSet.range(8, 16), null).z)
        assertEquals(14, PreviewTile.choose(ZoomSet.range(14, 16), null).z)
        assertEquals(5, PreviewTile.choose(ZoomSet.of(1, 5, 9), LonLatZoom(0.0, 0.0, 4)).z)
        assertFalse(TileUrlBuilder(good.toSource()).build(paris.z, paris.x, paris.y).isNullOrEmpty())
    }
}
