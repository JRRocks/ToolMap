package fr.toolmap.core.problem

import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProblemsTest {
    private val taken = mutableListOf<Problem>()

    @BeforeTest
    fun setUp() {
        Problems.sink = Problems.Sink { taken += it }
    }

    @AfterTest
    fun tearDown() {
        Problems.sink = Problems.stderr
    }

    @Test
    fun `an unexpected failure is reported and returned`() {
        val result = catchReported("test.place") { error("boom") }
        assertTrue(result.isFailure)
        assertEquals(listOf("test.place" to false), taken.map { it.where to it.expected })
    }

    @Test
    fun `an expected type is expected, anything else is a bug`() {
        catchExpected("test.io", IOException::class) { throw IOException("offline") }
        catchExpected("test.io", IOException::class) { throw IllegalStateException("bug") }
        assertEquals(listOf(true, false), taken.map { it.expected })
        assertEquals(4, catchExpected("test.ok", IOException::class) { 4 }.getOrThrow())
    }

    @Test
    fun `a cancellation is never swallowed`() {
        assertFailsWith<CancellationException> { catchReported("test.cancel") { throw CancellationException("stop") } }
        assertFailsWith<CancellationException> {
            catchExpected("test.cancel", IllegalStateException::class) { throw CancellationException("stop") }
        }
        assertTrue(taken.isEmpty())
    }

    @Test
    fun `a sink that fails falls back to standard error instead of looping`() {
        var calls = 0
        Problems.sink =
            Problems.Sink {
                calls++
                Problems.report("test.inner", IllegalStateException("from the sink"))
                throw IllegalStateException("sink down")
            }
        Problems.report("test.outer", IllegalStateException("first"))
        assertEquals(1, calls)
    }

    @Test
    fun `unexpected keeps the place of the call in its stack`() {
        Problems.unexpected("test.state", "impossible", "n" to 3)
        val problem = taken.single()
        assertTrue(problem.error is UnexpectedState)
        assertEquals(listOf("n" to 3), problem.context)
        assertTrue(problem.error.stackTrace.any { it.className.contains("ProblemsTest") })
    }
}
