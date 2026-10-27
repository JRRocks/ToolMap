package fr.toolmap.data.sources

import fr.toolmap.core.maps.OnlineCatalog
import fr.toolmap.core.source.BuiltinSources
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRepositoryTest {
    private val dir: File = kotlin.io.path.createTempDirectory("toolmap-sources").toFile()
    private val fixture: String =
        File(requireNotNull(System.getProperty("fixtures.dir")), "aqx/IGN_Topo_zoom_test.aqx").readText(Charsets.UTF_8)

    private fun repo() = SourceRepository(dir)

    @Test
    fun `a fresh repository serves the built-in catalogue`() {
        val repo = repo()
        assertEquals(BuiltinSources.load().map { it.id }, repo.sources.value.map { it.id })
        assertNotNull(repo.find("osm"))
        assertTrue(repo.isBuiltin("ign-scan25"))
        assertNull(repo.find("IGN_MAPS"))
    }

    @Test
    fun `the fixture previews with its two sources and imports them`() {
        val repo = repo()
        val preview = repo.preview(fixture, "IGN_Topo_zoom_test.aqx")
        assertEquals(listOf("IGN_MAPS", "IGN_SCAN25TOUR"), preview.entries.map { it.source.id })
        assertTrue(preview.entries.none { it.idInUse })
        // Nothing stored yet.
        assertNull(repo.find("IGN_MAPS"))

        val outcome = repo.import(preview)
        assertEquals(2, outcome.added.size)
        val scan = repo.find("IGN_SCAN25TOUR")
        assertNotNull(scan)
        assertEquals(6, scan.minZoom)
        assertEquals(14, scan.maxZoom)
        assertEquals("https://data.geopf.fr/", scan.levels.single().referer)
        assertTrue(scan.isSupported)
        assertTrue(repo.isImported("IGN_MAPS"))
        assertFalse(repo.isBuiltin("IGN_MAPS"))
    }

    @Test
    fun `imported sources survive a restart`() {
        val first = repo()
        first.import(first.preview(fixture))
        val second = repo()
        assertEquals(
            listOf("IGN_MAPS", "IGN_SCAN25TOUR"),
            second.sources.value.drop(BuiltinSources.load().size).map {
                it.id
            },
        )
        assertEquals(first.find("IGN_MAPS"), second.find("IGN_MAPS"))
    }

    @Test
    fun `importing only some sources`() {
        val repo = repo()
        val outcome = repo.import(repo.preview(fixture), selectedIds = setOf("IGN_MAPS"))
        assertEquals(listOf("IGN_MAPS"), outcome.added.map { it.id })
        assertNull(repo.find("IGN_SCAN25TOUR"))
    }

    @Test
    fun `duplicate policies`() {
        val repo = repo()
        repo.import(repo.preview(fixture))

        val again = repo.preview(fixture)
        assertTrue(again.entries.all { it.idInUse })

        assertEquals(listOf("IGN_MAPS", "IGN_SCAN25TOUR"), repo.import(again, DuplicatePolicy.SKIP).skippedIds)

        val replaced = repo.import(again, DuplicatePolicy.REPLACE)
        assertEquals(2, replaced.replaced.size)
        assertEquals(2, repo.sources.value.count { it.id.startsWith("IGN_") })

        val both = repo.import(again, DuplicatePolicy.KEEP_BOTH)
        assertEquals(listOf("IGN_MAPS-2", "IGN_SCAN25TOUR-2"), both.added.map { it.id })
        assertTrue(both.added.all { "(2)" in it.name })
        val third = repo.import(again, DuplicatePolicy.KEEP_BOTH)
        assertEquals(listOf("IGN_MAPS-3", "IGN_SCAN25TOUR-3"), third.added.map { it.id })
    }

    @Test
    fun `a built-in id is never replaced`() {
        val repo = repo()
        val text = repo.export(listOf("osm")).replace("OpenStreetMap", "Hijacked")
        val preview = repo.preview(text)
        assertTrue(preview.entries.single().idInUseByBuiltin)
        val outcome = repo.import(preview, DuplicatePolicy.REPLACE)
        assertEquals("OpenStreetMap", repo.find("osm")!!.name)
        assertEquals("osm-2", outcome.added.single().id)
        assertFailsWith<IllegalArgumentException> { repo.save(repo.find("osm")!!) }
        assertFalse(repo.delete("osm"))
    }

    @Test
    fun `delete removes the source and its file`() {
        val repo = repo()
        repo.import(repo.preview(fixture))
        assertTrue(repo.delete("IGN_MAPS"))
        assertNull(repo.find("IGN_MAPS"))
        assertNull(repo().find("IGN_MAPS"))
        assertFalse(repo.delete("IGN_MAPS"))
    }

    @Test
    fun `bad files are rejected with a clear error and nothing is stored`() {
        val repo = repo()
        assertFailsWith<SourceImportException> { repo.preview("not xml at all") }
        assertFailsWith<SourceImportException> { repo.preview("<html></html>") }
        assertFailsWith<SourceImportException> { repo.preview("<aqx version=\"11\"></aqx>") }
        assertFailsWith<SourceImportException> { repo.preview("<!DOCTYPE aqx [<!ENTITY x \"y\">]><aqx/>") }
        assertEquals(BuiltinSources.load().size, repo.sources.value.size)
    }

    @Test
    fun `a damaged file in the directory is skipped`() {
        dir.mkdirs()
        File(dir, "broken-1.aqx").writeText("<<<")
        val repo = repo()
        assertEquals(BuiltinSources.load().size, repo.sources.value.size)
    }

    @Test
    fun `save creates then updates a source and export blanks secrets`() {
        val repo = repo()
        val base = repo.find("ign-scan25")!!
        val custom = base.copy(id = "mine", name = "Ma carte", params = mapOf("ign-apikey" to "SECRET"))
        repo.save(custom)
        repo.save(custom.copy(name = "Ma carte v2"))
        assertEquals("Ma carte v2", repo().find("mine")!!.name)
        val shared = repo.export(listOf("mine"), redactedParams = setOf("ign-apikey"))
        assertTrue("SECRET" !in shared)
    }

    @Test
    fun `ids with odd characters get safe file names`() {
        val repo = repo()
        val odd = repo.find("osm")!!.copy(id = "a/b c:d?*", name = "Odd")
        repo.save(odd)
        assertTrue(dir.listFiles().orEmpty().all { it.name.matches(Regex("[A-Za-z0-9._-]+")) })
        assertEquals("Odd", repo().find("a/b c:d?*")!!.name)
    }

    @Test
    fun `a map of the online catalogue is added once, with its key, and survives a restart`() {
        val repo = repo()
        val os = OnlineCatalog.load().flatMap { it.entries }.first { it.keyParams.isNotEmpty() }
        val keyed = OnlineCatalog.withKeys(os.source, mapOf("os-key" to "abc"))
        assertNull(repo.alreadyHave(keyed))
        assertTrue(repo.addFromCatalog(keyed))
        assertFalse(repo.addFromCatalog(keyed), "adding twice stored a second copy")
        assertEquals(keyed.id, repo.alreadyHave(os.source)?.id)
        assertTrue(repo.isImported(keyed.id))
        assertEquals("abc", repo().find(keyed.id)!!.params["os-key"])
    }

    @Test
    fun `a map with the tiles of a built in map is reported as already there`() {
        val repo = repo()
        val osm = repo.find("osm")!!
        assertEquals("osm", repo.alreadyHave(osm.copy(id = "cat-osm-copy", name = "Copy"))?.id)
    }
}
