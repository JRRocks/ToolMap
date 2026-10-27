package fr.toolmap.data.tiles

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The store while its folder is on a removable card that may be out (ADR-042): nothing is read or written then. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MbTilesStoreAvailabilityTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val key = TileKey("osm", 3, 1, 1)
    private var usable = true
    private val dir: File by lazy { temp.newFolder("tiles") }
    private val store: MbTilesStore by lazy { MbTilesStore(dir, available = { usable }) }

    @Test
    fun `tiles are stored and served while the folder is available`() {
        store.put(key, StoredTile(png, "image/png"))
        assertNotNull(store.get(key))
    }

    @Test
    fun `nothing is read, stored or created while the folder is out`() {
        usable = false
        store.put(key, StoredTile(png, "image/png"))
        assertNull(store.get(key))
        assertFalse(store.contains(key))
        assertFalse(File(dir, "osm.mbtiles").exists())
        store.close()
    }

    @Test
    fun `the store resumes with the folder when the card is back, and the tile stored meanwhile is not there`() {
        usable = false
        store.put(key, StoredTile(png, "image/png"))
        usable = true
        assertNull(store.get(key))
        store.put(key, StoredTile(png, "image/png"))
        assertNotNull(store.get(key))
        store.close()
    }
}
