package fr.toolmap.data.tracks

import android.database.sqlite.SQLiteDatabase
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.sketch.Cubic
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchStroke
import fr.toolmap.core.sketch.SketchStyle
import fr.toolmap.core.sketch.XY
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SketchRepositoryTest {
    private lateinit var db: TrackDatabase
    private lateinit var repo: SketchRepository
    private var now = 1_000L

    private val origin = XY.of(LonLat(5.95, 45.52))

    private fun stroke(
        color: Int,
        dx: Double,
    ) = SketchStroke(
        SketchStyle(color, widthMeters = 3.6, smoothingMeters = 1.5),
        SketchPath(origin, listOf(Cubic(origin + XY(dx, 1.0), origin + XY(dx, 2.0), origin + XY(dx + 10.0, 3.0)))),
    )

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
        repo = SketchRepository(db) { now }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a sketch is saved, read back in order, renamed, hidden and deleted`() =
        runBlocking {
            val id = repo.save(null, "Croquis du 3 nov.", listOf(stroke(1, 0.0), stroke(2, 5.0)))
            val saved = assertNotNull(repo.load(id))
            assertEquals("Croquis du 3 nov.", saved.sketch.name)
            assertEquals(listOf(1, 2), saved.sketch.strokes.map { it.style.color })
            assertTrue(saved.visible)
            val first = saved.sketch.strokes[0]
            assertEquals(3.6, first.style.widthMeters)
            assertTrue(first.path.end.distanceTo(origin + XY(10.0, 3.0)) <= 0.075)

            now = 2_000L
            repo.save(id, "ignored", listOf(stroke(3, 0.0)))
            val again = assertNotNull(repo.load(id))
            assertEquals(listOf(3), again.sketch.strokes.map { it.style.color })
            assertEquals(1_000L, again.createdAt)
            assertEquals(2_000L, again.updatedAt)

            repo.rename(id, "  Repérage du col ")
            repo.setVisible(id, false)
            val list = repo.sketches.first()
            assertEquals("Repérage du col", list.single().sketch.name)
            assertEquals(false, list.single().visible)

            repo.delete(id)
            assertNull(repo.load(id))
            assertTrue(repo.sketches.first().isEmpty())
            assertEquals(0, db.query("SELECT * FROM sketch_strokes", null).use { it.count })
        }

    @Test
    fun `the most recently changed sketch comes first`() =
        runBlocking {
            val a = repo.save(null, "A", listOf(stroke(1, 0.0)))
            now = 5_000L
            val b = repo.save(null, "B", listOf(stroke(1, 0.0)))
            assertEquals(listOf(b, a), repo.sketches.first().map { it.id })
            now = 9_000L
            repo.save(a, "A", listOf(stroke(2, 0.0)))
            assertEquals(listOf(a, b), repo.sketches.first().map { it.id })
        }

    @Test
    fun `a library of version 1 opens with its tracks and an empty sketch list`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val file = context.getDatabasePath("migration-test.db")
            file.parentFile?.mkdirs()
            file.delete()
            createVersion1(file)
            val migrated =
                androidx.room.Room
                    .databaseBuilder(context, TrackDatabase::class.java, file.path)
                    .allowMainThreadQueries()
                    .build()
            try {
                assertEquals(listOf("Lac"), migrated.dao().observeFolders().first().map { it.name })
                val sketches = SketchRepository(migrated) { now }
                assertTrue(sketches.sketches.first().isEmpty())
                val id = sketches.save(null, "Croquis", listOf(stroke(1, 0.0)))
                assertEquals(1, sketches.load(id)?.sketch?.strokes?.size)
            } finally {
                migrated.close()
                file.delete()
            }
        }

    /** A database file exactly as version 1 wrote it, from the exported schema (`schemas/…/1.json`). */
    private fun createVersion1(file: File) {
        val schema =
            JSONObject(
                File("schemas/fr.toolmap.data.tracks.TrackDatabase/1.json").readText(),
            ).getJSONObject("database")
        val sqlite = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (k in 0 until indices.length()) {
                sqlite.execSQL(indices.getJSONObject(k).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sqlite.execSQL(setup.getString(i))
        sqlite.execSQL("INSERT INTO folders (name, visible, createdAt) VALUES ('Lac', 1, 0)")
        sqlite.version = 1
        sqlite.close()
    }
}
