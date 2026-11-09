package fr.toolmap.problem

import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.ProblemDevice
import fr.toolmap.core.problem.ProblemKind
import fr.toolmap.core.problem.ProblemReport
import fr.toolmap.core.problem.Problems
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProblemQueueTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val taken = mutableListOf<Problem>()
    private val device = ProblemDevice("0.4.210 (1)", "abc1234", "15", 35, "Pixel", "arm64-v8a")

    @Before
    fun setUp() {
        Problems.sink = Problems.Sink { taken += it }
    }

    @After
    fun tearDown() {
        Problems.sink = Problems.stderr
    }

    private fun report(
        where: String,
        kind: ProblemKind = ProblemKind.ERROR,
        atMs: Long = 1_000L,
    ) = ProblemReport.of(kind, where, IllegalStateException("x"), "main", device, atMs)

    @Test
    fun `the same bug is one report with its count, the worst kind kept`() {
        val queue = ProblemQueue(folder.root)
        queue.add(report("tiles.decode", atMs = 1_000L))
        queue.add(report("tiles.decode", ProblemKind.CRASH, atMs = 2_000L))
        queue.addCount(report("tiles.decode").fp, 5, 3_000L)
        val all = queue.all(nowMs = 4_000L)
        assertEquals(1, all.size)
        assertEquals(7, all.single().count)
        assertEquals(ProblemKind.CRASH, all.single().kind)
        assertEquals(3_000L, all.single().lastMs)
        queue.remove(all.single().fp)
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `old reports expire and the queue is bounded`() {
        val queue = ProblemQueue(folder.root)
        for (i in 0 until ProblemQueue.MAX_FILES + 5) queue.add(report("test.place$i", atMs = 1_000L))
        assertEquals(ProblemQueue.MAX_FILES, queue.all(nowMs = 2_000L).size)
        assertEquals(0, queue.all(nowMs = 1_000L + ProblemQueue.MAX_AGE_MS + 1).size)
    }

    @Test
    fun `a report file that cannot be read is dropped and reported`() {
        val queue = ProblemQueue(folder.root)
        val fp = report("tiles.decode").fp
        File(folder.root, "problems/queue").mkdirs()
        File(folder.root, "problems/queue/$fp.json").writeText("{ not json")
        assertEquals(0, queue.all().size)
        assertEquals(listOf("problems.queue.read" to false), taken.map { it.where to it.expected })
    }
}
