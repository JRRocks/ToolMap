package fr.toolmap.core.maps

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.BuiltinSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnlineCatalogTest {
    private val groups = OnlineCatalog.load()
    private val all = groups.flatMap { it.entries }

    @Test
    fun `every provider has maps, in the order of the screen`() {
        assertEquals(CatalogProvider.entries, groups.map { it.provider })
    }

    @Test
    fun `every map can be displayed and has its own id`() {
        assertTrue(all.all { it.source.isSupported }, "a map of the catalogue cannot be displayed")
        assertEquals(all.size, all.map { it.source.id }.toSet().size)
        assertTrue(all.all { it.source.id.startsWith(it.provider.prefix) })
    }

    @Test
    fun `no map of the catalogue is already one of the built in maps`() {
        val builtin = BuiltinSources.load().map { it.id }.toSet()
        assertTrue(all.none { it.source.id in builtin })
    }

    @Test
    fun `no tile address carries a key or goes through Bing or Google`() {
        val servers = all.flatMap { e -> e.source.levels.flatMap { it.servers } }
        assertTrue(servers.none { "bing" in it.lowercase() || "google" in it.lowercase() })
        assertTrue(servers.none { "apikey=" in it.lowercase() })
    }

    @Test
    fun `only Ordnance Survey maps ask for a key`() {
        assertTrue(all.filter { it.keyParams.isNotEmpty() }.all { it.provider == CatalogProvider.ORDNANCE_SURVEY })
        assertEquals(listOf("os-key"), all.first { it.provider == CatalogProvider.ORDNANCE_SURVEY }.keyParams)
        assertTrue(all.filter { it.provider != CatalogProvider.ORDNANCE_SURVEY }.all { it.keyParams.isEmpty() })
    }

    @Test
    fun `the key the user gives is filled in and trimmed`() {
        val os = all.first { it.keyParams.isNotEmpty() }
        val keyed = OnlineCatalog.withKeys(os.source, mapOf("os-key" to " abc123 "))
        assertEquals("abc123", keyed.params["os-key"])
        assertTrue(OnlineCatalog.keyParams(keyed).isEmpty())
        assertEquals(listOf("os-key"), OnlineCatalog.keyParams(os.source))
    }

    @Test
    fun `a source of no known provider is left out`() {
        val strange = AqxParser.parse(STRANGE).file.sources
        assertFalse(OnlineCatalog.groups(strange).any { it.entries.isNotEmpty() })
    }

    private companion object {
        const val STRANGE =
            """<aqx version="11"><source id="mine"><name>Mine</name><zoom-levels z="0-5">""" +
                """<server>https://example.org/{${'$'}z}/{${'$'}x}/{${'$'}y}.png</server></zoom-levels></source></aqx>"""
    }
}
