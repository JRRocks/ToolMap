package fr.toolmap.draw

import fr.toolmap.core.route.LegKey
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingEngine
import fr.toolmap.core.route.RoutingError
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.track.TrackPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The BRouter queue (`docs/specs/routing.md` §6.2) with a fake engine whose answers the test gives. */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingQueueTest {
    /** Each call waits until the test answers it; cancelled calls are noted. */
    private class FakeEngine : RoutingEngine {
        val calls = ArrayList<Pair<RouteStop, RouteStop>>()
        val cancelled = ArrayList<RouteStop>()
        private val answers = HashMap<RouteStop, CompletableDeferred<LegResult>>()

        override suspend fun route(
            from: RouteStop,
            to: RouteStop,
            profile: String,
        ): LegResult {
            calls += from to to
            val answer = CompletableDeferred<LegResult>()
            answers[from] = answer
            try {
                return answer.await()
            } catch (e: CancellationException) {
                cancelled += from
                throw e
            }
        }

        fun answer(
            from: RouteStop,
            result: LegResult,
        ) {
            answers.getValue(from).complete(result)
        }
    }

    private val a = RouteStop(45.0, 6.0)
    private val b = RouteStop(45.01, 6.0)
    private val c = RouteStop(45.02, 6.0)
    private val d = RouteStop(45.03, 6.0)

    private fun request(
        from: RouteStop,
        to: RouteStop,
    ) = RoutingQueue.Request(LegKey.of(from, to, RoutingProfiles.HIKING), from, to)

    private fun ok(
        from: RouteStop,
        to: RouteStop,
    ) = LegResult.Ok(listOf(from.point, TrackPoint((from.lat + to.lat) / 2, 6.001), to.point), 60_000)

    private class Harness(
        scope: TestScope,
    ) {
        val engine = FakeEngine()
        val results = ArrayList<Pair<LegKey, LegResult>>()
        val queue = RoutingQueue(scope.backgroundScope, engine) { key, result -> results += key to result }
    }

    @Test
    fun `one leg at a time, in the order of the legs`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            val bc = request(b, c)
            h.queue.submit(listOf(ab, bc))
            runCurrent()
            assertEquals(listOf(a to b), h.engine.calls)
            assertTrue(h.queue.busy)

            h.engine.answer(a, ok(a, b))
            runCurrent()
            assertEquals(listOf(a to b, b to c), h.engine.calls)
            assertEquals(listOf(ab.key), h.results.map { it.first })

            h.engine.answer(b, ok(b, c))
            runCurrent()
            assertEquals(listOf(ab.key, bc.key), h.results.map { it.first })
            assertEquals(ok(b, c), h.results[1].second)
            assertFalse(h.queue.busy)
        }

    @Test
    fun `the leg being computed is cancelled when it is no longer wanted`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            val cd = request(c, d)
            h.queue.submit(listOf(ab))
            runCurrent()
            // The user moved B: A → B is gone, another leg is wanted.
            h.queue.submit(listOf(cd))
            runCurrent()
            assertEquals(listOf(a), h.engine.cancelled)
            assertEquals(listOf(a to b, c to d), h.engine.calls)

            h.engine.answer(c, ok(c, d))
            runCurrent()
            assertEquals(listOf(cd.key), h.results.map { it.first })
            assertFalse(h.queue.busy)
        }

    @Test
    fun `a waiting leg no longer wanted is never computed`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            h.queue.submit(listOf(ab, request(b, c)))
            runCurrent()
            h.queue.submit(listOf(ab))
            h.engine.answer(a, ok(a, b))
            runCurrent()
            assertEquals(listOf(a to b), h.engine.calls)
            assertEquals(listOf(ab.key), h.results.map { it.first })
            assertFalse(h.queue.busy)
        }

    @Test
    fun `the leg being computed goes on when it is still wanted, and a leg asked twice is computed once`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            val bc = request(b, c)
            h.queue.submit(listOf(ab, ab))
            runCurrent()
            h.queue.submit(listOf(ab, bc))
            runCurrent()
            assertEquals(listOf(a to b), h.engine.calls)
            assertTrue(h.engine.cancelled.isEmpty())

            h.engine.answer(a, ok(a, b))
            runCurrent()
            h.engine.answer(b, ok(b, c))
            runCurrent()
            assertEquals(listOf(a to b, b to c), h.engine.calls)
            assertEquals(listOf(ab.key, bc.key), h.results.map { it.first })
        }

    @Test
    fun `a leg that takes more than 35 s fails with a timeout, and the next one starts`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            h.queue.submit(listOf(ab, request(b, c)))
            runCurrent()
            advanceTimeBy(34_999)
            runCurrent()
            assertTrue(h.results.isEmpty())

            advanceTimeBy(2)
            runCurrent()
            assertEquals(ab.key, h.results.single().first)
            assertEquals(RoutingError.TIMEOUT, (h.results.single().second as LegResult.Failed).error)
            assertEquals(listOf(a to b, b to c), h.engine.calls)
        }

    @Test
    fun `BRouter's errors reach the view model as they are`() =
        runTest {
            val h = Harness(this)
            val ab = request(a, b)
            h.queue.submit(listOf(ab))
            runCurrent()
            val failed = LegResult.Failed(RoutingError.NO_DATA, "datafile E5_N45.rd5 not found")
            h.engine.answer(a, failed)
            runCurrent()
            assertEquals<List<Pair<LegKey, LegResult>>>(listOf(ab.key to failed), h.results)
        }

    @Test
    fun `cancel forgets everything`() =
        runTest {
            val h = Harness(this)
            h.queue.submit(listOf(request(a, b), request(b, c)))
            runCurrent()
            h.queue.cancel()
            runCurrent()
            assertFalse(h.queue.busy)
            assertEquals(listOf(a), h.engine.cancelled)
            assertEquals(listOf(a to b), h.engine.calls)
            assertTrue(h.results.isEmpty())
        }
}
