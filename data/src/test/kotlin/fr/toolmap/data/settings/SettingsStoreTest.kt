package fr.toolmap.data.settings

import fr.toolmap.core.geo.CoordinateSystem
import fr.toolmap.core.settings.AppSettings
import fr.toolmap.core.settings.ThemeMode
import fr.toolmap.core.settings.UnitSystem
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    private val dir: File = Files.createTempDirectory("settings").toFile()
    private val file = File(dir, "nested/settings.json")

    @Test
    fun `a new store has the defaults and no file`() {
        val store = SettingsStore(file)
        assertEquals(AppSettings(), store.settings.value)
        assertFalse(file.exists())
    }

    @Test
    fun `an update is published, saved, and read back by the next store`() {
        val store = SettingsStore(file)
        store.update { it.copy(units = UnitSystem.IMPERIAL, coordinates = CoordinateSystem.MGRS) }
        assertEquals(UnitSystem.IMPERIAL, store.settings.value.units)
        assertTrue(file.exists())
        assertFalse(File(file.parentFile, "settings.json.tmp").exists(), "temporary file left behind")
        val again = SettingsStore(file)
        assertEquals(UnitSystem.IMPERIAL, again.settings.value.units)
        assertEquals(CoordinateSystem.MGRS, again.settings.value.coordinates)
    }

    @Test
    fun `updates apply one after another to the latest value`() {
        val store = SettingsStore(file)
        store.update { it.copy(theme = ThemeMode.DARK) }
        store.update { it.copy(gloves = true) }
        assertEquals(ThemeMode.DARK, store.settings.value.theme)
        assertTrue(store.settings.value.gloves)
    }

    @Test
    fun `a damaged file gives the defaults and the next update repairs it`() {
        file.parentFile.mkdirs()
        file.writeText("{ this is not json")
        val store = SettingsStore(file)
        assertEquals(AppSettings(), store.settings.value)
        store.update { it.copy(keepScreenOn = true) }
        assertTrue(SettingsStore(file).settings.value.keepScreenOn)
    }

    @Test
    fun `reload picks up a file written behind its back`() {
        val store = SettingsStore(file)
        store.update { it.copy(units = UnitSystem.IMPERIAL) }
        file.writeText(fr.toolmap.core.settings.AppSettingsCodec.encode(AppSettings(theme = ThemeMode.LIGHT)))
        store.reload()
        assertEquals(ThemeMode.LIGHT, store.settings.value.theme)
        assertEquals(UnitSystem.METRIC, store.settings.value.units)
    }
}
