package fr.toolmap.maps

import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.settings.SettingsStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.state.WorkStateStore
import fr.toolmap.data.storage.StorageManager
import fr.toolmap.data.tiles.ForcedRefresh
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.data.tiles.TileStats
import fr.toolmap.data.workspaces.WorkspaceStore
import fr.toolmap.download.ZoneEstimator
import fr.toolmap.map.MapViewModel
import fr.toolmap.record.GnssSatellites
import fr.toolmap.testing.stopTurningLooper
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The two view models behind « Cartes et calques », on three maps of its own (IGN Cartes, OpenStreetMap, the IGN
 * slopes overlay) and the favourites provided with the app: for the tests and renders of the panel (P29.3). With
 * [builtin], the maps provided with the app instead, IGN Cartes shown under the IGN slopes: the renders of « Cartes
 * disponibles » (P29.6).
 */
class MapsPanelFixture(
    private val builtin: Boolean = false,
) {
    val root: File = createTempDirectory("maps-panel").toFile()

    private fun source(
        id: String,
        name: String,
        zooms: ZoomSet,
        isLayer: Boolean = false,
    ) = TileSource(
        id = id,
        name = name,
        isLayer = isLayer,
        levels = listOf(LevelDef(zooms, listOf("https://t/$id/{\$z}/{\$x}/{\$y}.png"))),
    )

    val repository =
        SourceRepository(
            File(root, "sources"),
            if (builtin) {
                BuiltinSources.load()
            } else {
                listOf(
                    source("ign-maps", "IGN Cartes", ZoomSet.range(1, 18)),
                    source("osm", "OpenStreetMap", ZoomSet.range(0, 19)),
                    source("ign-slopes", "IGN Pentes montagne", ZoomSet.range(8, 17), isLayer = true),
                )
            },
        )
    private val store = MbTilesStore(File(root, "tiles"), repository)
    private val offline = OfflineMode()
    private val settings = SettingsStore(File(root, "settings.json"))

    val maps =
        MapsViewModel(
            context = RuntimeEnvironment.getApplication(),
            repository = repository,
            storage = StorageManager(store, repository),
            estimator = ZoneEstimator(ZoneDownloader(OkHttpClient(), repository, store, offline), repository, store),
            queue = {},
            offlineMode = offline,
            forcedRefresh = ForcedRefresh(),
            thumbnails = MapThumbnails(OkHttpClient(), repository),
            settingsStore = settings,
        )

    val vm =
        MapViewModel(
            repository = repository,
            store = WorkStateStore(File(root, "state.json")),
            tileStats = TileStats(),
            offlineMode = offline,
            workspaceStore = WorkspaceStore(File(root, "workspaces")),
            settingsStore = settings,
            gnss = GnssSatellites(RuntimeEnvironment.getApplication()),
        ).apply {
            for (layer in state.value.stack.topToBottom()) removeLayer(layer.id)
            if (builtin) {
                addLayer("ign-maps")
                addLayer("ign-pentes")
            } else {
                // A stack of three maps, top first: the slopes, OpenStreetMap kept to zooms 1 to 8, IGN Cartes.
                addLayer("ign-maps")
                addLayer("osm")
                addLayer("ign-slopes")
                val osm = state.value.stack.topToBottom().first { it.sourceId == "osm" }
                setZoomRange(osm.id, 1, 8)
            }
        }

    fun close() {
        // The view models write their files on Dispatchers.IO (a favourite deleted): they stop before the folder goes.
        maps.stopTurningLooper()
        vm.stopTurningLooper()
        store.close()
        root.deleteRecursively()
    }
}
