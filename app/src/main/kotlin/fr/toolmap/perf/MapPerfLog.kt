package fr.toolmap.perf

import android.os.Process
import android.os.SystemClock
import android.util.Log
import fr.toolmap.data.tiles.TileOutcome
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.data.tiles.TileStatsSnapshot
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

/**
 * The timing lines the measures of P15 read (docs/specs/performance.md §6 and §7, tools/perf): for each step (the map
 * opened, a gesture, a camera move asked by the app), the time until the map is fully drawn and the tiles it took, by
 * origin. Written to logcat under [TAG]; only installed when `BuildConfig.PERF_LOG` (debug and benchmark builds).
 *
 * One line per step, when MapLibre reports the map fully rendered (whole map, frame or idle) and no finger moves it:
 * `rendered step=<n> trigger=<open|gesture|camera|return> ms=<since the step started> idle_ms=<since the camera stopped>
 * store=<n> network=<n> composed=<n> failed=<n>` (the tile counts are the requests answered since the step started).
 */
class MapPerfLog private constructor(
    private val stats: TileStats,
    private val firstInProcess: Boolean,
) {
    private var step = 0
    private var trigger = "open"
    private var startNanos = SystemClock.elapsedRealtimeNanos()
    private var baseline = stats.snapshot()
    private var armed = true
    private var cameraMoving = false
    private var lastFrameFully = false

    /** When the camera of the current step stopped (0: not yet, or the opening, which has no camera move). */
    private var idleNanos = 0L

    private fun start(trigger: String) {
        step++
        this.trigger = trigger
        startNanos = SystemClock.elapsedRealtimeNanos()
        baseline = stats.snapshot()
        armed = true
        idleNanos = 0L
    }

    /**
     * The layers changed (another map, an elevation band moved, P20): times the wait until the new tiles are drawn.
     * The opening step keeps running: its layers arrive this way too.
     */
    fun restyled() {
        if (step == 0 && armed) return
        start("style")
    }

    /** The map is shown again after a full screen covered it (PF1): times the wait until it is drawn. */
    fun returned() {
        cameraMoving = false
        start("return")
    }

    private fun onFullyRendered() {
        if (!armed || cameraMoving) return
        val now = stats.snapshot()
        // The empty base style is fully drawn before the layers are added: the opening waits for its tiles.
        if (step == 0 && now.total == baseline.total) return
        armed = false
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val ms = (nowNanos - startNanos) / NANOS_PER_MILLI
        // The wait for the tiles alone, without the gesture or the animation: from the moment the camera stopped.
        val idleMs = if (idleNanos > 0) (nowNanos - idleNanos) / NANOS_PER_MILLI else ms
        val sinceProcess = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
        val extra = if (step == 0 && firstInProcess) " since_process_ms=$sinceProcess" else ""
        Log.i(
            TAG,
            "rendered step=$step trigger=$trigger ms=$ms idle_ms=$idleMs " +
                "store=${now.delta(
                    TileOutcome.STORE_HIT,
                )} network=${now.delta(
                    TileOutcome.NETWORK_OK,
                )} composed=${now.delta(TileOutcome.COMPOSED)} masked=${now.delta(TileOutcome.MASKED)} " +
                "failed=${FAILED.sumOf { now.delta(it) }}$extra",
        )
    }

    private fun TileStatsSnapshot.delta(outcome: TileOutcome): Int = count(outcome) - baseline.count(outcome)

    companion object {
        /** The logcat tag the scripts of tools/perf filter on. */
        const val TAG = "ToolMapPerf"
        private const val NANOS_PER_MILLI = 1_000_000L

        /** Map views watched since the process started (a new one each time the map screen comes back, PF1). */
        private var maps = 0
        private val FAILED =
            listOf(TileOutcome.HTTP_ERROR, TileOutcome.NETWORK_ERROR, TileOutcome.OFFLINE_MISS, TileOutcome.REJECTED)

        /** Starts timing [mapView] from now (step 0, « open »); the result times the return from a covering screen. */
        fun watch(
            mapView: MapView,
            stats: TileStats,
        ): MapPerfLog {
            val log = MapPerfLog(stats, firstInProcess = maps++ == 0)
            Log.i(TAG, "open map=$maps")
            // Three ways MapLibre says everything on screen is loaded; the first one that comes ends the step.
            mapView.addOnDidFinishRenderingMapListener { fully -> if (fully) log.onFullyRendered() }
            mapView.addOnDidFinishRenderingFrameListener { fully, _, _ ->
                log.lastFrameFully = fully
                if (fully) log.onFullyRendered()
            }
            mapView.addOnDidBecomeIdleListener { log.onFullyRendered() }
            mapView.getMapAsync { map ->
                map.addOnCameraMoveStartedListener { reason ->
                    when {
                        reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE -> {
                            // A finger on the map: nothing is final until the camera stops.
                            log.cameraMoving = true
                            log.start("gesture")
                        }

                        // The app's own moves (zoom buttons...) start a step unless one is running, and end when the
                        // camera stops; the opening position does neither, the opening step waits for its tiles.
                        log.step > 0 || !log.armed -> {
                            if (!log.armed) log.start("camera")
                            log.cameraMoving = true
                        }
                    }
                }
                map.addOnCameraIdleListener {
                    log.cameraMoving = false
                    if (log.armed && log.step > 0) log.idleNanos = SystemClock.elapsedRealtimeNanos()
                    // Fully drawn before the camera was declared idle: no later frame may come, report now.
                    if (log.lastFrameFully) log.onFullyRendered()
                }
            }
            return log
        }
    }
}
