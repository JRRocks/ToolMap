package fr.toolmap.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.toolmap.R
import fr.toolmap.core.AppInfo
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileHost
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.map.rememberMapViewWithLifecycle
import fr.toolmap.ui.theme.Button
import fr.toolmap.ui.theme.OutlinedButton
import fr.toolmap.ui.theme.Switch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import kotlin.math.floor

private const val EMPTY_STYLE =
    """{"version":8,"sources":{},"layers":[{"id":"bg","type":"background","paint":{"background-color":"#d9d9d9"}}]}"""
private const val TILEJSON_VERSION = "2.2.0"
private const val OVERLAY_SUFFIX = "-overlay"
private const val START_CAMERA_ZOOM = 11.0

/**
 * P1.1 diagnostic screen: a MapLibre map whose raster sources all live on the fake tile host, so every
 * tile goes through [fr.toolmap.data.tiles.TileInterceptor]. It shows the interceptor counters and the
 * camera zoom next to the tile zoom actually requested (to measure the 256/512 px offset, ADR-011) and
 * can copy a plain-text report.
 */
@Composable
fun SpikeScreen(
    sources: List<TileSource>,
    stats: TileStats,
    offline: OfflineMode,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)

    val bases = remember(sources) { sources.filter { it.isSupported && !it.isLayer && it.type != "dem" } }
    val overlays = remember(sources) { sources.filter { it.isSupported && it.isLayer } }
    var baseId by remember { mutableStateOf(bases.firstOrNull { it.id == "ign-scan25" }?.id ?: bases.first().id) }
    var overlayId by remember { mutableStateOf<String?>(null) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    var cameraZoom by remember { mutableDoubleStateOf(START_CAMERA_ZOOM) }
    var moving by remember { mutableStateOf(false) }
    // floor(camera zoom) -> tile zoom requested while the camera was idle.
    val zoomPairs = remember { mutableStateMapOf<Int, Int>() }

    val snapshot by stats.state.collectAsStateWithLifecycle()
    val isOffline by offline.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(snapshot.lastZoom, snapshot.total, moving) {
        val tileZoom = snapshot.lastZoom
        if (!moving && tileZoom != null) zoomPairs[floor(cameraZoom).toInt()] = tileZoom
    }

    // Rebuild the layers whenever the selection changes (or the style finishes loading).
    LaunchedEffect(map, baseId, overlayId) {
        val m = map ?: return@LaunchedEffect
        val base = sources.first { it.id == baseId }
        val overlay = overlayId?.let { id -> sources.first { it.id == id } }
        m.getStyle { style -> applySelection(style, base, overlay) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val mapView = rememberMapViewWithLifecycle()
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    mapView.also { view ->
                        view.getMapAsync { m ->
                            m.setStyle(Style.Builder().fromJson(EMPTY_STYLE)) {
                                val start = sources.first { it.id == baseId }.previewLocation
                                val target = start?.let { LatLng(it.lat, it.lon) } ?: LatLng(44.80, 5.90)
                                m.moveCamera(CameraUpdateFactory.newLatLngZoom(target, START_CAMERA_ZOOM))
                                m.addOnCameraMoveStartedListener { moving = true }
                                m.addOnCameraMoveListener { cameraZoom = m.cameraPosition.zoom }
                                m.addOnCameraIdleListener {
                                    cameraZoom = m.cameraPosition.zoom
                                    moving = false
                                }
                                map = m
                            }
                        }
                    }
                },
            )
        }

        val reportText = report(sources, baseId, overlayId, isOffline, cameraZoom, zoomPairs, snapshot.report())
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).navigationBarsPadding().padding(8.dp)) {
            Column(
                modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.spike_title), style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    bases.forEach { source ->
                        FilterChip(
                            selected = source.id == baseId,
                            onClick = { baseId = source.id },
                            label = { Text(source.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    overlays.forEach { source ->
                        FilterChip(
                            selected = overlayId == source.id,
                            onClick = { overlayId = if (overlayId == source.id) null else source.id },
                            label = { Text(stringResource(R.string.spike_overlay, source.name, source.opacity)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = isOffline, onCheckedChange = offline::set)
                    Text(stringResource(R.string.spike_offline), modifier = Modifier.padding(start = 8.dp))
                }

                Text(
                    stringResource(
                        R.string.spike_zoom_line,
                        cameraZoom,
                        snapshot.lastZoom?.toString() ?: "-",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(reportText, style = MaterialTheme.typography.bodySmall)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyToClipboard(context, reportText) }) {
                        Text(stringResource(R.string.spike_copy_report))
                    }
                    OutlinedButton(onClick = {
                        stats.reset()
                        zoomPairs.clear()
                    }) { Text(stringResource(R.string.spike_reset)) }
                    OutlinedButton(onClick = onBack) { Text(stringResource(R.string.spike_close)) }
                }
            }
        }
    }
}

private fun report(
    sources: List<TileSource>,
    baseId: String,
    overlayId: String?,
    offline: Boolean,
    cameraZoom: Double,
    zoomPairs: Map<Int, Int>,
    statsReport: String,
): String =
    buildString {
        appendLine("toolmap=${AppInfo.NAME} spike=P1.1")
        appendLine("base=$baseId overlay=${overlayId ?: "-"} offline=$offline")
        val base = sources.first { it.id == baseId }
        appendLine("base_zooms=${base.minZoom}-${base.maxZoom}")
        appendLine("camera_zoom=${"%.2f".format(java.util.Locale.ROOT, cameraZoom)}")
        appendLine("zoom_pairs=${zoomPairs.toSortedMap().entries.joinToString(",") { "cam${it.key}->z${it.value}" }}")
        append(statsReport)
    }

private fun copyToClipboard(
    context: Context,
    text: String,
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("ToolMap spike report", text))
    Toast.makeText(context, R.string.spike_copied, Toast.LENGTH_SHORT).show()
}

/** Replaces the raster layers of [style] by the selected base map and optional overlay. */
private fun applySelection(
    style: Style,
    base: TileSource,
    overlay: TileSource?,
) {
    style.layers.map { it.id }.filter { it != "bg" }.forEach { style.removeLayer(it) }
    style.sources.map { it.id }.forEach { style.removeSource(it) }
    addRaster(style, base, opacity = null)
    if (overlay != null) addRaster(style, overlay, opacity = overlay.opacity / 100f)
}

private fun addRaster(
    style: Style,
    source: TileSource,
    opacity: Float?,
) {
    val level = source.levels.first()
    val tileSet = TileSet(TILEJSON_VERSION, TileHost.templateFor(source))
    // Spike: AQX zooms are handed over as-is; the interceptor also answers 404 outside the levels.
    tileSet.setMinZoom(source.minZoom.toFloat())
    tileSet.setMaxZoom(source.maxZoom.toFloat())
    tileSet.attribution = source.copyright
    style.addSource(RasterSource(source.id, tileSet, level.tileSize))
    val layer = RasterLayer(source.id + OVERLAY_SUFFIX, source.id)
    if (opacity != null) layer.setProperties(PropertyFactory.rasterOpacity(opacity))
    style.addLayer(layer)
}
