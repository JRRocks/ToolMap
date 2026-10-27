package fr.toolmap.data.state

import fr.toolmap.core.layers.LayerStack
import fr.toolmap.core.state.CameraState
import fr.toolmap.core.state.WorkState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkStateStoreTest {
    private val dir: File = kotlin.io.path.createTempDirectory("toolmap-state").toFile()

    @Test
    fun `no file gives the default state`() {
        assertEquals(WorkState(), WorkStateStore(File(dir, "state.json")).load())
    }

    @Test
    fun `save then load returns the same state and leaves no temporary file`() {
        val store = WorkStateStore(File(dir, "sub/state.json"))
        val state =
            WorkState(LayerStack().add("ign-scan25").add("ign-pentes", 0.5f), CameraState(45.0, 5.7, 12.25, 10.0))
        store.save(state)
        assertEquals(state, store.load())
        store.save(state.copy(offline = true))
        assertTrue(store.load().offline)
        assertEquals(listOf("state.json"), File(dir, "sub").list().orEmpty().toList())
    }

    @Test
    fun `a corrupted file gives the default state instead of crashing`() {
        val file = File(dir, "state.json")
        file.writeText("{\"stack\": [broken")
        assertEquals(WorkState(), WorkStateStore(file).load())
    }
}
