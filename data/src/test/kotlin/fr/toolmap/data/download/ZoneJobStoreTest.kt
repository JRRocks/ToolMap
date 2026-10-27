package fr.toolmap.data.download

import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoneStatus
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoneJobStoreTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("jobs").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun job(
        id: String,
        created: Long,
        status: ZoneStatus = ZoneStatus.QUEUED,
    ) = ZoneJob(
        ZoneRequest(id, "Zone $id", listOf("osm"), 5, 9, ZoneShape.Rectangle(0.0, 0.0, 1.0, 1.0), created),
        status,
        ZoneProgress(total = 10),
    )

    @Test
    fun `jobs are listed newest first and survive a restart`() {
        val store = ZoneJobStore(dir)
        store.save(job("a", 1))
        store.save(job("b", 3))
        store.save(job("c", 2))
        assertEquals(listOf("b", "c", "a"), store.jobs.value.map { it.request.id })

        val reopened = ZoneJobStore(dir)
        assertEquals(listOf("b", "c", "a"), reopened.jobs.value.map { it.request.id })
        assertNotNull(reopened.get("c"))
    }

    @Test
    fun `saving a job again replaces it`() {
        val store = ZoneJobStore(dir)
        store.save(job("a", 1))
        store.save(job("a", 1, ZoneStatus.DONE))
        assertEquals(1, store.jobs.value.size)
        assertEquals(ZoneStatus.DONE, store.get("a")!!.status)
    }

    @Test
    fun `deleting a job removes its file`() {
        val store = ZoneJobStore(dir)
        store.save(job("a", 1))
        store.delete("a")
        assertNull(store.get("a"))
        assertEquals(0, ZoneJobStore(dir).jobs.value.size)
    }

    @Test
    fun `an update writes a listed job but never brings back a removed one`() {
        val store = ZoneJobStore(dir)
        store.save(job("a", 1))
        assertTrue(store.update(job("a", 1).copy(status = ZoneStatus.PAUSED)))
        assertEquals(ZoneStatus.PAUSED, store.get("a")?.status)
        store.delete("a")
        // The worker of a removed job writes its pause on cancellation: the job stays removed (P29.7).
        assertFalse(store.update(job("a", 1).copy(status = ZoneStatus.PAUSED)))
        assertNull(store.get("a"))
        assertNull(ZoneJobStore(dir).get("a"))
    }

    @Test
    fun `damaged files are ignored and ids cannot escape the directory`() {
        File(dir, "broken.json").writeText("{nope")
        val store = ZoneJobStore(dir)
        assertEquals(0, store.jobs.value.size)
        store.save(job("../../evil", 1))
        assertEquals(1, dir.listFiles { f -> f.name.endsWith(".json") && f.name != "broken.json" }!!.size)
        assertEquals(1, store.jobs.value.size)
    }
}
