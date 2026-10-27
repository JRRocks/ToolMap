package fr.toolmap.core.maps

import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.source.TileSource

/** The providers of the online catalogue, in the order the screen lists them (P16.4). */
enum class CatalogProvider(
    /** Prefix of the ids of the provider's maps in `online-catalog.aqx`. */
    val prefix: String,
) {
    OPENSTREETMAP("cat-osm-"),
    IGN("cat-ign-"),
    SWISSTOPO("cat-swisstopo-"),
    BKG("cat-bkg-"),
    USGS("cat-usgs-"),
    ORDNANCE_SURVEY("cat-os-"),
}

/** A map of the online catalogue: [keyParams] are the API keys the user must give when adding it. */
data class CatalogEntry(
    val provider: CatalogProvider,
    val source: TileSource,
    val keyParams: List<String>,
)

/** One section of the online catalogue. */
data class CatalogGroup(
    val provider: CatalogProvider,
    val entries: List<CatalogEntry>,
)

/**
 * The maps that can be added in one gesture from « Ajouter une carte › Catalogue en ligne » (AlpineQuest's catalogue
 * of remote maps). They ship with the app in `online-catalog.aqx` (AQX syntax, so that adding one is storing a source
 * like an import), and only list sources whose terms of use allow direct access to the tiles: no Bing, no Google. A
 * parameter left empty in the file is an API key, asked when the map is added.
 */
object OnlineCatalog {
    const val RESOURCE = "/online-catalog.aqx"

    /** @throws IllegalStateException if the resource is missing or is not a valid AQX file (a packaging bug). */
    fun load(): List<CatalogGroup> {
        val stream = OnlineCatalog::class.java.getResourceAsStream(RESOURCE) ?: error("Missing resource $RESOURCE")
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return groups(AqxParser.parse(text).file.sources)
    }

    /** The sources by provider, in file order; a source of no known provider is left out. */
    fun groups(sources: List<TileSource>): List<CatalogGroup> =
        CatalogProvider.entries.mapNotNull { provider ->
            val entries =
                sources
                    .filter { it.id.startsWith(provider.prefix) }
                    .map { CatalogEntry(provider, it, keyParams(it)) }
            if (entries.isEmpty()) null else CatalogGroup(provider, entries)
        }

    /** The parameters of [source] that no value fills: API keys to ask for. */
    fun keyParams(source: TileSource): List<String> = source.params.filterValues { it.isBlank() }.keys.toList()

    /** [source] with the keys the user gave filled in. */
    fun withKeys(
        source: TileSource,
        keys: Map<String, String>,
    ): TileSource = source.copy(params = source.params + keys.mapValues { it.value.trim() })
}
