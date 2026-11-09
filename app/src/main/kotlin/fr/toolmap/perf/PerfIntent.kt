package fr.toolmap.perf

import android.content.Intent
import android.util.Log
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.layers.BlendMode
import fr.toolmap.core.layers.RangeLogic
import fr.toolmap.core.state.CameraState
import fr.toolmap.download.ZoneQueue
import fr.toolmap.engine.NativeEngine
import fr.toolmap.map.MapViewModel

/**
 * The fixed starting points of the measure scripts (tools/perf, docs/specs/performance.md §7), read from the extras
 * of the launch intent; only called when `BuildConfig.PERF_INTENT` (benchmark build), for an explicit launch. Values are strings
 * (`am start --es`) so the shell passes them exactly:
 * - `perf.source`: the only map shown (its source id, e.g. `ign-maps`);
 * - `perf.lat`, `perf.lon`, `perf.zoom`: the camera the map opens at;
 * - `perf.zone` = `west,south,east,north`, with `perf.zmin` and `perf.zmax`: starts a zone download of `perf.source`;
 * - `perf.overlay`: maps added on top of the others (source ids, `a,b`: the last on top);
 * - `perf.blend`: the blend mode of the top `perf.overlay` (`multiply`, `soft-light`…, P26);
 * - `perf.altmin`, `perf.altmax` (metres, an empty value = no limit), `perf.logic` (`and` / `or`): the elevation band
 *   of `perf.overlay`, or else of `perf.source` (P20);
 * - `perf.engine`: `kotlin` keeps the native engine off for this start (the same scenario measured with the Kotlin
 *   code, ADR-119).
 */
object PerfIntent {
    private const val SOURCE = "perf.source"
    private const val LAT = "perf.lat"
    private const val LON = "perf.lon"
    private const val ZOOM = "perf.zoom"
    private const val ZONE = "perf.zone"
    private const val ZMIN = "perf.zmin"
    private const val ZMAX = "perf.zmax"
    private const val ZONE_EDGES = 4
    private const val OVERLAY = "perf.overlay"
    private const val ALT_MIN = "perf.altmin"
    private const val ALT_MAX = "perf.altmax"
    private const val LOGIC = "perf.logic"
    private const val BLEND = "perf.blend"
    private const val ENGINE = "perf.engine"

    fun handle(
        intent: Intent?,
        map: MapViewModel,
        zones: ZoneQueue,
    ) {
        val extras = intent?.extras ?: return
        if (extras.getString(ENGINE) == "kotlin") {
            NativeEngine.keepOff()
            Log.i(EngineBench.TAG, "engine ${NativeEngine.describe()} (kept off by perf.engine)")
        }
        val source = extras.getString(SOURCE)
        if (source != null) {
            val existing = map.state.value.stack.layers.firstOrNull { it.sourceId == source }
            if (existing == null) map.addLayer(source)
            map.state.value.stack.layers
                .lastOrNull { it.sourceId == source }
                ?.let { map.removeOtherLayers(it.id) }
        }
        // Several maps (`a,b`) are added in that order, the last on top.
        val overlays = extras.getString(OVERLAY)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        for (id in overlays) {
            if (map.state.value.stack.layers
                    .none { it.sourceId == id }
            ) {
                map.addLayer(id)
            }
        }
        val overlay = overlays.lastOrNull()
        extras.getString(BLEND)?.let { key ->
            val mode = BlendMode.fromKey(key) ?: return@let
            map.state.value.stack.layers
                .lastOrNull { overlay == null || it.sourceId == overlay }
                ?.let { layer ->
                    Log.i(MapPerfLog.TAG, "blend layer=${layer.sourceId} mode=${mode.key}")
                    map.setBlend(layer.id, mode)
                }
        }
        if (extras.containsKey(ALT_MIN) || extras.containsKey(ALT_MAX)) {
            val target = overlay ?: source
            map.state.value.stack.layers
                .lastOrNull { target == null || it.sourceId == target }
                ?.let { layer ->
                    val logic = if (extras.getString(LOGIC) == "or") RangeLogic.OR else RangeLogic.AND
                    val min = extras.getString(ALT_MIN)?.toIntOrNull()
                    val max = extras.getString(ALT_MAX)?.toIntOrNull()
                    Log.i(MapPerfLog.TAG, "elevation band layer=${layer.sourceId} min=$min max=$max logic=$logic")
                    map.setElevationRange(layer.id, min, max, logic)
                }
        }
        val lat = extras.getString(LAT)?.toDoubleOrNull()
        val lon = extras.getString(LON)?.toDoubleOrNull()
        val zoom = extras.getString(ZOOM)?.toDoubleOrNull()
        if (lat != null && lon != null && zoom != null) map.setCamera(CameraState(lat = lat, lon = lon, zoom = zoom))
        val zone = extras.getString(ZONE)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }
        if (source != null && zone != null && zone.size == ZONE_EDGES) {
            val (west, south, east, north) = zone
            val request =
                ZoneRequest(
                    id = "perf-${System.currentTimeMillis()}",
                    name = "perf",
                    sourceIds = listOf(source),
                    minZoom = extras.getString(ZMIN)?.toIntOrNull() ?: return,
                    maxZoom = extras.getString(ZMAX)?.toIntOrNull() ?: return,
                    shape = ZoneShape.Rectangle(west, south, east, north),
                    createdAtMillis = System.currentTimeMillis(),
                )
            Log.i(MapPerfLog.TAG, "zone queued id=${request.id}")
            zones.start(request)
        }
    }
}
