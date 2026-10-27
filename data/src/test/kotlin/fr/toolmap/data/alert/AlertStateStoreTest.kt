package fr.toolmap.data.alert

import fr.toolmap.core.alert.AlertState
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.alert.FollowRef
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AlertStateStoreTest {
    private val dir: File = Files.createTempDirectory("alerts").toFile()
    private val file = File(dir, "nested/alerts.json")

    @Test
    fun `a new store is idle and writes nothing`() {
        val store = AlertStateStore(file)
        assertTrue(store.state.value.isIdle)
        assertFalse(file.exists())
    }

    @Test
    fun `an update is published, saved, and read back by the next store`() {
        val store = AlertStateStore(file)
        store.update { it.copy(follow = FollowRef(FollowKind.TRACK, 4L)).withWatch(8L, 120) }
        assertEquals(4L, store.state.value.follow?.id)
        assertFalse(File(file.parentFile, "alerts.json.tmp").exists(), "temporary file left behind")
        val again = AlertStateStore(file)
        assertEquals(FollowRef(FollowKind.TRACK, 4L), again.state.value.follow)
        assertEquals(120, again.state.value.watching(8L)?.radiusMeters)
    }

    @Test
    fun `an unchanged update does not touch the file`() {
        val store = AlertStateStore(file)
        store.update { it }
        assertFalse(file.exists())
    }

    @Test
    fun `a damaged file reads as idle and the next update repairs it`() {
        file.parentFile.mkdirs()
        file.writeText("garbage")
        val store = AlertStateStore(file)
        assertEquals(AlertState(), store.state.value)
        store.update { it.withWatch(1L, null) }
        assertEquals(1, AlertStateStore(file).state.value.watches.size)
    }

    @Test
    fun `reload picks up a file replaced behind the store's back`() {
        val store = AlertStateStore(file)
        store.update { it.withWatch(1L, null) }
        file.writeText("""{"watches":[{"waypointId":2},{"waypointId":3}]}""")
        store.reload()
        assertEquals(listOf(2L, 3L), store.state.value.watches.map { it.waypointId })
    }
}
