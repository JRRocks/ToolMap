package fr.toolmap.data.tracks

import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.Track
import fr.toolmap.core.track.TrackDocument
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Folders made and filled by the user (P16.5): create, move an item, move a folder, and what exports keep. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TrackFoldersTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = TrackRepository(db, now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    private fun track(name: String) =
        Track(
            name = name,
            segments = listOf(List(3) { i -> TrackPoint(45.0 + i * 0.001, 6.0 + i * 0.001) }),
        )

    @Test
    fun `a folder is created at the top or inside another one, with a trimmed name`() =
        run {
            val top = assertNotNull(repo.createFolder(null, "  Randos  "))
            val child = assertNotNull(repo.createFolder(top, "Jour 1"))
            val lib = repo.library.first()
            assertEquals("Randos", lib.folders.first { it.id == top }.name)
            assertNull(lib.folders.first { it.id == top }.parentId)
            assertEquals(top, lib.folders.first { it.id == child }.parentId)
            assertEquals("Randos / Jour 1", lib.pathOf(child))
        }

    @Test
    fun `a blank name or a missing parent creates nothing`() =
        run {
            assertNull(repo.createFolder(null, "   "))
            assertNull(repo.createFolder(999L, "Orphan"))
            assertTrue(repo.library.first().folders.isEmpty())
        }

    @Test
    fun `items move into a folder and back to the top of the library`() =
        run {
            val folder = assertNotNull(repo.createFolder(null, "Randos"))
            val waypoint = repo.createWaypoint(Waypoint(name = "Col", lat = 45.0, lon = 6.0))
            val trackId = repo.createTrack("Boucle", track("Boucle").segments)
            val ref = ItemRef(ItemKind.WAYPOINT, waypoint)
            assertTrue(repo.moveItem(ref, folder))
            assertTrue(repo.moveItem(ItemRef(ItemKind.TRACK, trackId), folder))
            var lib = repo.library.first()
            assertEquals(folder, lib.waypoints.single().folderId)
            assertEquals(folder, lib.tracks.single().folderId)
            assertTrue(repo.moveItem(ref, null))
            lib = repo.library.first()
            assertNull(lib.waypoints.single().folderId)
            assertEquals(folder, lib.tracks.single().folderId)
        }

    @Test
    fun `moving an item to a missing folder or a missing item changes nothing`() =
        run {
            val waypoint = repo.createWaypoint(Waypoint(name = "Col", lat = 45.0, lon = 6.0))
            assertFalse(repo.moveItem(ItemRef(ItemKind.WAYPOINT, waypoint), 999L))
            assertFalse(repo.moveItem(ItemRef(ItemKind.WAYPOINT, 999L), null))
            assertNull(repo.library.first().waypoints.single().folderId)
        }

    @Test
    fun `a folder moves with what it holds but never inside itself`() =
        run {
            val a = assertNotNull(repo.createFolder(null, "A"))
            val b = assertNotNull(repo.createFolder(a, "B"))
            val c = assertNotNull(repo.createFolder(b, "C"))
            val other = assertNotNull(repo.createFolder(null, "Autre"))
            repo.createWaypoint(Waypoint(name = "Col", lat = 45.0, lon = 6.0), folderId = c)
            assertFalse(repo.moveFolder(a, a), "inside itself")
            assertFalse(repo.moveFolder(a, c), "inside a sub-folder")
            assertTrue(repo.moveFolder(b, other))
            val lib = repo.library.first()
            assertEquals(other, lib.folders.first { it.id == b }.parentId)
            assertEquals("Autre / B / C", lib.pathOf(c))
            assertEquals(c, lib.waypoints.single().folderId)
            assertTrue(repo.moveFolder(b, null))
            assertNull(repo.library.first().folders.first { it.id == b }.parentId)
        }

    @Test
    fun `an item taken out of an import keeps the prefixes of its raw xml`() =
        run {
            val hr = RawXml("<gpxtpx:hr>120</gpxtpx:hr>")
            val point = TrackPoint(45.0, 6.0, extras = listOf(hr))
            val doc =
                TrackDocument(
                    name = "Sortie",
                    tracks = listOf(Track(name = "Avec fc", segments = listOf(listOf(point, point.copy(lat = 45.1))))),
                    namespaces = mapOf("gpxtpx" to "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"),
                )
            val imported = repo.import(doc, null)
            val trackId = repo.library.first().tracks.single().id
            val target = assertNotNull(repo.createFolder(null, "Mes traces"))
            assertTrue(repo.moveItem(ItemRef(ItemKind.TRACK, trackId), target))
            assertEquals(
                "http://www.garmin.com/xmlschemas/TrackPointExtension/v1",
                fr.toolmap.core.track.TrackJson
                    .decodeNamespaces(repo.library.first().folders.first { it.id == target }.namespacesJson)["gpxtpx"],
            )
            // And at the top of the library, the export still declares the prefix on the fragment.
            assertTrue(repo.moveItem(ItemRef(ItemKind.TRACK, trackId), null))
            val exported = repo.document(TrackSelection(trackIds = listOf(trackId)))
            val xml =
                exported.tracks
                    .single()
                    .segments
                    .flatten()
                    .first()
                    .extras
                    .single()
                    .xml
            assertTrue("xmlns:gpxtpx=" in xml, xml)
            assertNotNull(imported.folderId)
        }
}
