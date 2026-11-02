package fr.toolmap.core.layers

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.LevelDef
import fr.toolmap.core.source.MapRef
import fr.toolmap.core.source.TileSource
import fr.toolmap.core.source.ZoomSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoritesFileTest {
    private val builtins = BuiltinSources.load()
    private val builtinIds = builtins.map { it.id }.toSet()

    private fun map(
        id: String,
        name: String,
        server: String,
        params: Map<String, String> = emptyMap(),
    ) = TileSource(
        id = id,
        name = name,
        params = params,
        levels = listOf(LevelDef(zooms = ZoomSet.range(1, 18), servers = listOf(server))),
    )

    private val snow =
        map(
            "SNOW_KEYED",
            "SnowBase Map",
            "https://tiles.example.org/snow/{\$z}/{\$x}/{\$y}.png?key={\$param:key}",
            mapOf("key" to "secret-123"),
        )
    private val slopes = map("PENTES_YETI", "Pentes Yéti", "https://tiles.example.org/pentes/{\$z}/{\$x}/{\$y}.png")
    private val added = listOf(snow, slopes)
    private val phone = builtins + added

    private fun find(id: String) = phone.firstOrNull { it.id == id }

    private val ski =
        Workspace(
            "ws-ski",
            "Sortie Ski",
            listOf(WorkspaceLayer("ign-scan25"), WorkspaceLayer("SNOW_KEYED", displayMinElevation = 1550)),
        )
    private val winter =
        Workspace("ws-winter", "Chartreuse hiver", listOf(WorkspaceLayer("PENTES_YETI", opacity = 0.3f)))

    private fun share(
        favorites: List<Workspace>,
        keepKeys: Boolean,
    ) = FavoritesExport.build(favorites, ::find, { it in builtinIds }, keepKeys, appVersion = "0.4.148")

    @Test
    fun `a file holds several favorites and the added maps they show, without the built-in ones`() {
        val file = share(listOf(Workspaces.randoEte, ski, winter), keepKeys = true)
        val back = FavoritesFileCodec.decode(FavoritesFileCodec.encode(file))
        assertEquals(file, back)
        assertEquals(listOf("Rando été", "Sortie Ski", "Chartreuse hiver"), back.favorites.map { it.name })
        assertEquals(ski.layers, back.favorites[1].layers)
        val maps = AqxParser.parse(back.aqx!!).file.sources
        assertEquals(listOf("SNOW_KEYED", "PENTES_YETI"), maps.map { it.id })
        assertEquals("secret-123", maps.first().params["key"])
    }

    @Test
    fun `removing the keys writes every parameter of the added maps empty`() {
        val file = share(listOf(ski), keepKeys = false)
        val maps = AqxParser.parse(file.aqx!!).file.sources
        assertEquals(listOf("SNOW_KEYED"), maps.map { it.id })
        assertEquals("", maps.single().params["key"])
        assertFalse("secret-123" in FavoritesFileCodec.encode(file))
    }

    @Test
    fun `favorites of built-in maps only carry no map`() {
        assertNull(share(listOf(Workspaces.randoEte), keepKeys = true).aqx)
        assertEquals(
            listOf(snow),
            FavoritesExport.keyed(
                FavoritesExport.addedMaps(listOf(ski, winter), ::find) {
                    it in
                        builtinIds
                },
            ),
        )
    }

    @Test
    fun `an added multi-zoom map brings the maps it shows`() {
        val multi =
            TileSource(
                id = "MY_MULTI",
                name = "Mon multi-zoom",
                levels =
                    listOf(
                        LevelDef(ZoomSet.range(1, 12), listOf(MapRef("osm").toServer())),
                        LevelDef(ZoomSet.range(13, 18), listOf(MapRef("PENTES_YETI").toServer())),
                    ),
            )
        val maps =
            FavoritesExport.addedMaps(listOf(Workspace("w", "W", listOf(WorkspaceLayer("MY_MULTI")))), {
                if (it == "MY_MULTI") multi else find(it)
            }) { it in builtinIds }
        assertEquals(listOf("MY_MULTI", "PENTES_YETI"), maps.map { it.id })
    }

    @Test
    fun `a file name keeps the favorite name without the characters a file system refuses`() {
        assertEquals("Sortie Ski.toolmap", FavoritesExport.fileName("Sortie Ski"))
        assertEquals("Ski hiver 2.toolmap", FavoritesExport.fileName("Ski / hiver: 2?"))
        assertEquals("A B.toolmap", FavoritesExport.fileName("A/B"))
        assertEquals("Favoris.toolmap", FavoritesExport.fileName(" ... "))
    }

    @Test
    fun `only a ToolMap favorites file of a known format is read`() {
        val text = FavoritesFileCodec.encode(share(listOf(ski), keepKeys = false))
        assertTrue(FavoritesFileCodec.looksLike(text))
        assertFalse(FavoritesFileCodec.looksLike("<?xml version=\"1.0\"?><aqx/>"))
        val failure = { t: String -> assertFailsWith<FavoritesFileException> { FavoritesFileCodec.decode(t) }.failure }
        assertEquals(FavoritesFileFailure.NOT_FAVORITES, failure("<aqx/>"))
        assertEquals(FavoritesFileFailure.NOT_FAVORITES, failure(text.replace("\"ToolMap\"", "\"Other\"")))
        assertEquals(FavoritesFileFailure.UNSUPPORTED_VERSION, failure(text.replace("\"format\":1", "\"format\":2")))
        assertEquals(
            FavoritesFileFailure.EMPTY,
            failure(FavoritesFileCodec.encode(FavoritesFile(favorites = emptyList()))),
        )
        // A newer writer may add fields: they are ignored.
        assertEquals(
            "Sortie Ski",
            FavoritesFileCodec.decode(text.replace("{\"format\"", "{\"extra\":3,\"format\"")).favorites.single().name,
        )
    }

    @Test
    fun `a name already taken gets a number, also between favorites of the same file`() {
        assertEquals("Sortie Ski", FavoritesImport.uniqueName("Sortie Ski", listOf("Rando")))
        assertEquals("Sortie Ski (2)", FavoritesImport.uniqueName("Sortie Ski", listOf("Sortie Ski")))
        assertEquals("Sortie Ski (3)", FavoritesImport.uniqueName("Sortie Ski", listOf("Sortie Ski", "Sortie Ski (2)")))
        val file = FavoritesFile(favorites = listOf(ski, ski.copy(id = "other")))
        val plan =
            FavoritesImport.plan(
                file,
                emptyList(),
                phone,
                taken = listOf("Rando été"),
                hidden = emptySet(),
            )
        assertEquals(listOf("Sortie Ski", "Sortie Ski (2)"), plan.items.map { it.favorite.name })
        assertEquals(listOf(false, true), plan.items.map { it.renamed })
    }

    @Test
    fun `on a phone without the added maps they are added, and a map without its key asks for one`() {
        val file = share(listOf(Workspaces.randoEte, ski, winter), keepKeys = false)
        val sources = AqxParser.parse(file.aqx!!).file.sources
        val plan =
            FavoritesImport.plan(
                file,
                sources,
                builtins,
                taken = listOf("Rando été", "Sortie Ski"),
                hidden = emptySet(),
            )
        val (rando, skiItem, winterItem) = plan.items
        assertEquals("Rando été (2)", rando.favorite.name)
        assertTrue(rando.added.isEmpty())
        assertEquals(listOf("SNOW_KEYED"), skiItem.added.map { it.id })
        assertEquals(listOf("SNOW_KEYED"), skiItem.needKey.map { it.id })
        assertEquals(listOf("PENTES_YETI"), winterItem.added.map { it.id })
        assertTrue(winterItem.needKey.isEmpty())
        assertEquals(ski.layers, skiItem.favorite.layers)
        assertEquals(listOf("SNOW_KEYED", "PENTES_YETI"), plan.mapsFor(listOf(2, 1)).map { it.id })
        assertEquals(listOf("Sortie Ski (2)"), plan.favoritesFor(listOf(1)).map { it.name })
    }

    @Test
    fun `a map the phone already has is reused, under its own id when the tiles are the same`() {
        // The receiver added the same snow map under another name and with their own key.
        val mine = snow.copy(id = "SNOW_MINE", name = "Neige", params = mapOf("key" to "their-key"))
        val file = share(listOf(ski), keepKeys = false)
        val sources = AqxParser.parse(file.aqx!!).file.sources
        val plan = FavoritesImport.plan(file, sources, builtins + mine, taken = emptyList(), hidden = emptySet())
        val item = plan.items.single()
        assertTrue(item.added.isEmpty())
        assertEquals(listOf("ign-scan25", "SNOW_MINE"), item.favorite.layers.map { it.sourceId })
        // Same id and tiles: kept as it is.
        val same = FavoritesImport.plan(file, sources, builtins + snow, taken = emptyList(), hidden = emptySet())
        assertTrue(same.items.single().added.isEmpty())
        assertEquals("SNOW_KEYED", same.items.single().favorite.layers[1].sourceId)
    }

    @Test
    fun `a map whose id names another map here is added under a new id`() {
        val other = map("SNOW_KEYED", "Autre carte", "https://other.example.net/{\$z}/{\$x}/{\$y}.png")
        val file = share(listOf(ski), keepKeys = true)
        val sources = AqxParser.parse(file.aqx!!).file.sources
        val item =
            FavoritesImport.plan(
                file,
                sources,
                builtins + other,
                taken = emptyList(),
                hidden = emptySet(),
            ).items.single()
        assertEquals(listOf("SNOW_KEYED-2"), item.added.map { it.id })
        assertEquals("SnowBase Map (2)", item.added.single().name)
        assertEquals("SNOW_KEYED-2", item.favorite.layers[1].sourceId)
        assertTrue(item.needKey.isEmpty())
    }

    @Test
    fun `a map the user hid comes back with the favorite`() {
        val file = FavoritesFile(favorites = listOf(Workspaces.randoHiver))
        val item =
            FavoritesImport.plan(
                file,
                emptyList(),
                builtins,
                taken = emptyList(),
                hidden = setOf("ign-pentes", "osm"),
            ).items.single()
        assertEquals(listOf("ign-pentes"), item.unhidden)
    }
}
