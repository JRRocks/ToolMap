package fr.toolmap.data.tracks

import android.database.sqlite.SQLiteDatabase
import fr.toolmap.core.geo.LonLat
import fr.toolmap.core.sketch.Cubic
import fr.toolmap.core.sketch.RawStroke
import fr.toolmap.core.sketch.SketchFill
import fr.toolmap.core.sketch.SketchPath
import fr.toolmap.core.sketch.SketchShape
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
            createVersion(file, 1) { it.execSQL("INSERT INTO folders (name, visible, createdAt) VALUES ('Lac', 1, 0)") }
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

    @Test
    fun `a sketch of version 2 keeps its strokes, and lines and zones come back with their fill`() =
        runBlocking {
            val context = RuntimeEnvironment.getApplication()
            val file = context.getDatabasePath("migration-v2-test.db")
            file.parentFile?.mkdirs()
            file.delete()
            createVersion(file, 2) {
                it.execSQL(
                    "INSERT INTO sketches (id, name, visible, createdAt, updatedAt) VALUES (1, 'Repérage', 1, 0, 0)",
                )
                it.execSQL(
                    "INSERT INTO sketch_strokes (sketchId, ord, colorArgb, widthMeters, smoothingMeters, path) " +
                        "VALUES (1, 0, -1, 3.0, 1.0, 'M10 20 c1 1 2 2 3 3')",
                )
            }
            val migrated =
                androidx.room.Room
                    .databaseBuilder(context, TrackDatabase::class.java, file.path)
                    .allowMainThreadQueries()
                    .build()
            try {
                val sketches = SketchRepository(migrated) { now }
                val old = assertNotNull(sketches.load(1)).sketch.strokes.single()
                assertNull(old.shape)
                val style = SketchStyle(-1, widthMeters = 3.0, smoothingMeters = 1.0)
                val corners = listOf(origin, origin + XY(100.0, 0.0), origin + XY(50.0, 80.0))
                val zone = RawStroke(style, corners, SketchShape(closed = true, fill = SketchFill.DOTS)).smoothed()
                val line = RawStroke(style, corners, SketchShape(closed = false)).smoothed()
                sketches.save(1, "Repérage", listOf(old, zone, line))
                val back = assertNotNull(sketches.load(1)).sketch.strokes
                assertEquals(listOf(null, zone.shape, line.shape), back.map { it.shape })
                assertEquals(3, back[1].toRaw().points.size)
            } finally {
                migrated.close()
                file.delete()
            }
        }

    /** A database file exactly as [version] wrote it, from the exported schema (`schemas/…/<version>.json`). */
    private fun createVersion(
        file: File,
        version: Int,
        fill: (SQLiteDatabase) -> Unit,
    ) {
        val schema =
            JSONObject(
                File("schemas/fr.toolmap.data.tracks.TrackDatabase/$version.json").readText(),
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
        fill(sqlite)
        sqlite.version = version
        sqlite.close()
    }
}
