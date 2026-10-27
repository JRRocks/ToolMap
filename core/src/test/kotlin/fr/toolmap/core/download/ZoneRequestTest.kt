package fr.toolmap.core.download

import fr.toolmap.core.geo.Bounds
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.tiles.DownloadArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ZoneRequestTest {
    private fun request(shape: ZoneShape) =
        ZoneRequest("job1", "Mont Blanc", listOf("ign-scan25", "ign-pentes"), 10, 16, shape, 42L)

    @Test
    fun `every shape survives a round trip through JSON`() {
        val shapes =
            listOf(
                DownloadArea.Rectangle(Bounds(6.8, 45.8, 6.9, 45.9)),
                DownloadArea.Polygon(listOf(LonLat(6.8, 45.8), LonLat(6.9, 45.8), LonLat(6.85, 45.9))),
                DownloadArea.Corridor(listOf(LonLat(6.8, 45.8), LonLat(6.9, 45.9)), 1000.0),
            )
        for (area in shapes) {
            val job =
                ZoneJob(
                    request(ZoneShape.of(area)),
                    ZoneStatus.RUNNING,
                    ZoneProgress(100, 40, 10, 1, 2, 5000),
                    "msg",
                    7L,
                )
            val back = ZoneJobCodec.decodeOrNull(ZoneJobCodec.encode(job))
            assertEquals(job, back)
            assertEquals(area, back!!.request.shape.toArea())
        }
    }

    @Test
    fun `a damaged job file reads as null`() {
        assertNull(ZoneJobCodec.decodeOrNull("{not json"))
        assertNull(ZoneJobCodec.decodeOrNull(""))
    }

    @Test
    fun `progress fraction counts everything processed`() {
        val progress = ZoneProgress(total = 200, downloaded = 50, alreadyStored = 30, missing = 10, failed = 10)
        assertEquals(100L, progress.processed)
        assertEquals(0.5f, progress.fraction)
        assertEquals(1f, ZoneProgress().fraction)
    }

    @Test
    fun `invalid requests are refused`() {
        val shape = ZoneShape.Rectangle(0.0, 0.0, 1.0, 1.0)
        assertFailsWith<IllegalArgumentException> { ZoneRequest("a", "a", emptyList(), 1, 2, shape) }
        assertFailsWith<IllegalArgumentException> { ZoneRequest("a", "a", listOf("s"), 5, 4, shape) }
        assertFailsWith<IllegalArgumentException> { ZoneRequest("a", "a", listOf("s"), 0, 40, shape) }
    }

    @Test
    fun `zoom limits include their bounds and an absent limit is unbounded`() {
        val limit = ZoomLimit(8, 14)
        assertEquals(listOf(false, true, true, false), listOf(7, 8, 14, 15).map { it in limit })
        assertEquals(true, 3 in ZoomLimit())
        assertEquals(true, 20 in ZoomLimit(min = 8))
        assertEquals(false, 20 in ZoomLimit(max = 12))
    }

    @Test
    fun `a job file written before zoom limits existed still loads`() {
        val shape = ZoneShape.Rectangle(6.8, 45.8, 6.9, 45.9)
        val withLimits =
            ZoneJob(request(shape).copy(zoomLimits = mapOf("ign-scan25" to ZoomLimit(8, 14))), ZoneStatus.QUEUED)
        assertEquals(withLimits, ZoneJobCodec.decodeOrNull(ZoneJobCodec.encode(withLimits)))

        val plain = ZoneJob(request(shape), ZoneStatus.QUEUED)
        val json = ZoneJobCodec.encode(plain)
        val legacy = json.replace(Regex(",?\\s*\"zoomLimits\"\\s*:\\s*\\{\\s*\\}"), "")
        assertEquals(false, legacy.contains("zoomLimits"), "the field was removed from the JSON")
        assertEquals(plain, ZoneJobCodec.decodeOrNull(legacy))
    }
}
