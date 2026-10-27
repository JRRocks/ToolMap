package fr.toolmap.download

import androidx.work.Configuration
import androidx.work.WorkManager
import fr.toolmap.core.download.ZoneJob
import fr.toolmap.core.download.ZoneProgress
import fr.toolmap.core.download.ZoneRequest
import fr.toolmap.core.download.ZoneShape
import fr.toolmap.core.download.ZoneStatus
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.data.download.ZoneDownloader
import fr.toolmap.data.download.ZoneJobStore
import fr.toolmap.data.sources.SourceRepository
import fr.toolmap.data.tiles.MbTilesStore
import fr.toolmap.data.tiles.OfflineMode
import fr.toolmap.maps.MapThumbnails
import fr.toolmap.testing.stopTurningLooper
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.io.path.createTempDirectory

/**
 * The downloads of the preview of P29.7 (`docs/previews/design-07-telechargements.html`): a zone going at 51 %, « Col
 * du Granier » paused, « Chapareillan » failed for the missing card, « Fort Barraux » done on 25 Oct., on the maps
 * provided with the app; for the tests and the renders of « Téléchargements ».
 */
class DownloadFixture {
    val root: File = createTempDirectory("downloads").toFile()
    val repository = SourceRepository(File(root, "sources"), BuiltinSources.load())
    private val store = MbTilesStore(File(root, "tiles"), repository)
    private val offline = OfflineMode()
    val jobs = ZoneJobStore(File(root, "jobs"))

    val vm =
        DownloadViewModel(
            estimator = ZoneEstimator(ZoneDownloader(OkHttpClient(), repository, store, offline), repository, store),
            scheduler = DownloadScheduler(jobs) { workManager() },
            repository = repository,
            offlineMode = offline,
            jobStore = jobs,
            thumbnails = MapThumbnails(OkHttpClient(), repository),
        )

    private val zone = ZoneShape.Rectangle(5.90, 45.40, 6.00, 45.50)

    /**
     * The app gives WorkManager its configuration (Hilt workers); here it is set up once, with an executor that never
     * runs a worker: the tests only look at what the jobs become.
     */
    private fun workManager(): WorkManager {
        val app = RuntimeEnvironment.getApplication()
        return try {
            WorkManager.getInstance(app)
        } catch (e: IllegalStateException) {
            WorkManager.initialize(app, Configuration.Builder().setExecutor { }.build())
            WorkManager.getInstance(app)
        }
    }

    private fun at(text: String) =
        LocalDateTime.parse(text).atZone(ZoneId.of("Europe/Paris")).toInstant().toEpochMilli()

    private fun job(
        id: String,
        name: String,
        maps: List<String>,
        status: ZoneStatus,
        progress: ZoneProgress,
        created: String,
        minZoom: Int = 0,
        message: String? = null,
    ) = ZoneJob(
        ZoneRequest(id, name, maps, minZoom, 16, zone, createdAtMillis = at(created)),
        status,
        progress,
        message,
        updatedAtMillis = at(created),
    )

    /** The four jobs of the preview, the one going first. */
    fun fill() {
        jobs.save(
            job(
                "going",
                "Zone du 27 oct. 2026 à 14:05",
                listOf("ign-maps", "opentopomap"),
                ZoneStatus.RUNNING,
                ZoneProgress(total = 446, downloaded = 228, bytes = 5_200_000),
                "2026-10-27T14:05",
            ),
        )
        jobs.save(
            job(
                "granier",
                "Col du Granier",
                listOf("ign-scan25"),
                ZoneStatus.PAUSED,
                ZoneProgress(total = 1_450, downloaded = 310, bytes = 6_200_000),
                "2026-10-26T09:00",
                minZoom = 12,
            ),
        )
        jobs.save(
            job(
                "chapareillan",
                "Chapareillan",
                listOf("osm"),
                ZoneStatus.FAILED,
                ZoneProgress(total = 380),
                "2026-10-26T08:00",
                message =
                    "Carte SD absente : rien n'a été téléchargé. Insérez la carte, puis relancez le téléchargement.",
            ),
        )
        jobs.save(
            job(
                "barraux",
                "Fort Barraux",
                listOf("ign-maps"),
                ZoneStatus.DONE,
                ZoneProgress(total = 2_380, downloaded = 2_380, bytes = 41_300_000),
                "2026-10-25T18:00",
            ),
        )
    }

    fun close() {
        vm.stopTurningLooper()
        store.close()
        root.deleteRecursively()
    }
}
