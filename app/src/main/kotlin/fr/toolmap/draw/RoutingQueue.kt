package fr.toolmap.draw

import fr.toolmap.core.route.LegKey
import fr.toolmap.core.route.LegResult
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RoutingEngine
import fr.toolmap.core.route.RoutingError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The legs waiting for BRouter (`docs/specs/routing.md` §6.2): one call at a time, in the order of the legs. [submit]
 * gives the whole list of legs still wanted after each change: a leg no longer in it is not computed, and the one being
 * computed is cancelled. Runs on the scope's thread (the main thread of the view model); [onResult] is called there.
 */
class RoutingQueue(
    private val scope: CoroutineScope,
    private val engine: RoutingEngine,
    private val onResult: (LegKey, LegResult) -> Unit,
) {
    class Request(
        val key: LegKey,
        val from: RouteStop,
        val to: RouteStop,
    )

    private var wanted: List<Request> = emptyList()
    private var running: LegKey? = null
    private var job: Job? = null

    /** True while a leg is being computed or waits for it. */
    val busy: Boolean get() = job != null || wanted.isNotEmpty()

    fun submit(requests: List<Request>) {
        wanted = requests.distinctBy { it.key }
        val current = running
        if (current != null && wanted.none { it.key == current }) stop()
        if (job == null) next()
    }

    /** Forgets everything (end of the edit). */
    fun cancel() {
        wanted = emptyList()
        stop()
    }

    private fun stop() {
        job?.cancel()
        job = null
        running = null
    }

    private fun next() {
        val request = wanted.firstOrNull() ?: return
        running = request.key
        val started =
            scope.launch(start = CoroutineStart.LAZY) {
                val result =
                    withTimeoutOrNull(TIMEOUT_MILLIS) { engine.route(request.from, request.to, request.key.profile) }
                        ?: LegResult.Failed(RoutingError.TIMEOUT, "timeout")
                job = null
                running = null
                wanted = wanted.filter { it.key != request.key }
                onResult(request.key, result)
                if (job == null) next()
            }
        job = started
        started.start()
    }

    private companion object {
        /** BRouter stops itself after 30 s (`maxRunningTime`); this covers the binding and the transfer. */
        const val TIMEOUT_MILLIS = 35_000L
    }
}
