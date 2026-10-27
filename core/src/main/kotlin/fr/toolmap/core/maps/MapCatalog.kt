package fr.toolmap.core.maps

import fr.toolmap.core.source.TileSource
import java.net.URI
import java.text.Normalizer

/** The families of the "Available maps" catalogue, in the order the screen lists them. */
enum class GroupKind {
    /** Maps built on OpenStreetMap data (OpenStreetMap, OpenTopoMap...). */
    OPENSTREETMAP,

    /** The official IGN maps and layers served by the Géoplateforme. */
    IGN,

    /** Maps assembled from other maps by zoom range (ADR-031), built in or made by the user. */
    MULTI_ZOOM,

    /** Maps the user added: an imported `.aqx` file or the "add a map" wizard. */
    IMPORTED,

    /** Packages of tiles with no server (an imported `.mbtiles`). */
    LOCAL,

    OTHER,
}

/** One section of the catalogue. */
data class MapGroup(
    val kind: GroupKind,
    val sources: List<TileSource>,
) {
    /**
     * Credit line under the title ("© IGN · geopf.fr"): only for the groups that come from one provider, where the
     * first map's copyright speaks for all of them.
     */
    val credit: String?
        get() {
            if (kind != GroupKind.IGN && kind != GroupKind.OPENSTREETMAP) return null
            val owner = sources.firstNotNullOfOrNull { it.copyright?.let(MapCatalog::cleanCopyright) }
            val site = sources.firstNotNullOfOrNull { it.dataSource?.let(MapCatalog::hostOf) }
            val parts = listOfNotNull(owner?.let { "© $it" }, site)
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
}

/** Groups the maps of the catalogue the way AlpineQuest's "On-demand maps" screen does. */
object MapCatalog {
    private val NOT_A_LETTER = Regex("[^a-z]+")

    /**
     * The sections to show: elevation data is left out (it is not a map to look at), empty sections are dropped,
     * and inside a section the base maps come before the overlays, each in catalogue order.
     */
    fun groups(
        sources: List<TileSource>,
        isImported: (String) -> Boolean,
    ): List<MapGroup> {
        val visible = sources.filter { it.type != "dem" }
        return GroupKind.entries.mapNotNull { kind ->
            val members = visible.filter { kindOf(it, isImported) == kind }
            if (members.isEmpty()) {
                null
            } else {
                MapGroup(kind, members.filterNot { it.isLayer } + members.filter { it.isLayer })
            }
        }
    }

    /**
     * The same sections without the provided maps the user hid from the list (F3), unless [showHidden]. A map the
     * user added is never hidden: it is deleted instead.
     */
    fun groups(
        sources: List<TileSource>,
        hidden: Set<String>,
        showHidden: Boolean,
        isImported: (String) -> Boolean,
    ): List<MapGroup> =
        groups(
            if (showHidden) sources else sources.filterNot { isHidden(it.id, hidden, isImported) },
            isImported,
        )

    /** Whether [sourceId] is a provided map that the user hid from the list. */
    fun isHidden(
        sourceId: String,
        hidden: Set<String>,
        isImported: (String) -> Boolean,
    ): Boolean = sourceId in hidden && !isImported(sourceId)

    /**
     * The sections keeping only the maps whose name or section title holds every word of [query] (F5), case and
     * accents ignored; sections left empty are dropped. A blank query keeps everything.
     */
    fun search(
        groups: List<MapGroup>,
        query: String,
        title: (GroupKind) -> String,
    ): List<MapGroup> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val groupText = fold(title(group.kind))
            val kept =
                group.sources.filter { source ->
                    fold(source.name + " " + groupText).let { t ->
                        words.all {
                            it in
                                t
                        }
                    }
                }
            if (kept.isEmpty()) null else group.copy(sources = kept)
        }
    }

    /**
     * Whether [text] holds every word of [query], whatever the case and the accents: the search of the online catalogue
     * from « Cartes disponibles » (P29.6), as [search] does for the maps of the list.
     */
    fun matches(
        text: String,
        query: String,
    ): Boolean {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        val folded = fold(text)
        return words.isNotEmpty() && words.all { it in folded }
    }

    /** Lower case without accents: « Énergie » and « energie » match. */
    private fun fold(text: String): String =
        Normalizer
            .normalize(text, Normalizer.Form.NFD)
            .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
            .lowercase()

    fun kindOf(
        source: TileSource,
        isImported: (String) -> Boolean,
    ): GroupKind =
        when {
            source.isLocalOnly -> GroupKind.LOCAL
            source.isMultiZoom -> GroupKind.MULTI_ZOOM
            isImported(source.id) -> GroupKind.IMPORTED
            else -> providerOf(source) ?: GroupKind.OTHER
        }

    private fun providerOf(source: TileSource): GroupKind? {
        val text = listOfNotNull(source.copyright, source.dataSource).joinToString(" ").lowercase()
        return when {
            "geopf.fr" in text || "geoportail" in text || NOT_A_LETTER.split(text).contains("ign") -> GroupKind.IGN
            "openstreetmap" in text || "opentopomap" in text -> GroupKind.OPENSTREETMAP
            else -> null
        }
    }

    /** A copyright without its "(c)" / "©" prefix, or `null` when nothing is left. */
    fun cleanCopyright(text: String): String? =
        text
            .trim()
            .removePrefix("(c)")
            .removePrefix("(C)")
            .removePrefix("©")
            .trim()
            .takeIf { it.isNotEmpty() }

    /** The host of a web address without `www.`, or `null` when it is not an address. */
    fun hostOf(address: String): String? =
        runCatching { URI(address.trim()).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }
}
