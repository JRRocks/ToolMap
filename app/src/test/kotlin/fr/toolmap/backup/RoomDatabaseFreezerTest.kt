package fr.toolmap.backup

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import fr.toolmap.data.tracks.TrackDatabase
import fr.toolmap.data.tracks.TrackRepository
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
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RoomDatabaseFreezerTest {
    private lateinit var db: TrackDatabase

    @Before
    fun setUp() {
        db = TrackDatabase.inMemory(RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun count(sql: SupportSQLiteDatabase) =
        sql.query("SELECT COUNT(*) FROM waypoints").use {
            it.moveToFirst()
            it.getInt(0)
        }

    @Test
    fun `the copy runs once and the database is usable and unchanged afterwards`() {
        val repo = TrackRepository(db)
        runBlocking { repo.createWaypoint(fr.toolmap.core.track.Waypoint(45.0, 6.0, name = "Col")) }
        var runs = 0
        var during = -1
        RoomDatabaseFreezer(db).invoke {
            runs++
            during = count(db.openHelper.readableDatabase)
        }
        assertEquals(1, runs)
        assertEquals(1, during)
        // The transaction was ended: a write goes through again.
        runBlocking { repo.createWaypoint(fr.toolmap.core.track.Waypoint(46.0, 7.0, name = "Pas")) }
        assertEquals(2, count(db.openHelper.readableDatabase))
        assertFalse(db.openHelper.writableDatabase.inTransaction())
    }

    @Test
    fun `a failing copy still releases the database`() {
        val error =
            runCatching {
                RoomDatabaseFreezer(db).invoke { throw IllegalStateException("copy failed") }
            }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(db.openHelper.writableDatabase.inTransaction())
    }
}
