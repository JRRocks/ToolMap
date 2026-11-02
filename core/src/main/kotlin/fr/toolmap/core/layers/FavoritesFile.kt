package fr.toolmap.core.layers

import fr.toolmap.core.aqx.AqxWriter
import fr.toolmap.core.source.SourceDuplicates
import fr.toolmap.core.source.TileSetIdentity
import fr.toolmap.core.source.TileSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * A `.toolmap` file (P25, ADR-105): favorites to give to someone, with the maps the sender added that they show (one
 * `.aqx` document), never tiles. The receiver adds them to their own favorites; nothing is applied.
 */
@Serializable
data class FavoritesFile(
    val format: Int = FORMAT,
    val app: String = APP,
    val kind: String = KIND,
    val appVersion: String = "",
    val favorites: List<Workspace> = emptyList(),
    /** The added maps the favorites show, as one `.aqx` document; `null` when they show only built-in maps. */
    val aqx: String? = null,
) {
    companion object {
        const val FORMAT = 1
        const val APP = "ToolMap"
        const val KIND = "favorites"
        const val EXTENSION = ".toolmap"
    }
}

/** Why a `.toolmap` file cannot be read. */
enum class FavoritesFileFailure {
    /** Not a favorites file of ToolMap (not JSON, another application or kind, a broken favorite). */
    NOT_FAVORITES,

    /** Made by a newer version of the format than this one reads. */
    UNSUPPORTED_VERSION,

    /** No favorite in it. */
    EMPTY,
}

class FavoritesFileException(
    val failure: FavoritesFileFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object FavoritesFileCodec {
    /** Bigger than any real file (a few kB), small enough to be read in memory and sent to a group (P25.4). */
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_FAVORITES = 500
    private const val MAX_LAYERS = 200
    private const val MAX_NAME = 200
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(file: FavoritesFile): String = json.encodeToString(FavoritesFile.serializer(), file)

    /** True when [text] is worth reading as a favorites file rather than as a map definition or a track. */
    fun looksLike(text: String): Boolean {
        val head = text.trimStart().take(HEAD_CHARS)
        return head.startsWith("{") && "\"kind\"" in text && "\"${FavoritesFile.KIND}\"" in text
    }

    /** @throws FavoritesFileException when [text] is not a file this version can add. */
    fun decode(text: String): FavoritesFile {
        val file =
            try {
                json.decodeFromString(FavoritesFile.serializer(), text)
            } catch (e: SerializationException) {
                throw FavoritesFileException(FavoritesFileFailure.NOT_FAVORITES, "Unreadable favorites file", e)
            } catch (e: IllegalArgumentException) {
                throw FavoritesFileException(FavoritesFileFailure.NOT_FAVORITES, "Unreadable favorites file", e)
            }
        if (file.app != FavoritesFile.APP || file.kind != FavoritesFile.KIND || file.format < 1) {
            throw FavoritesFileException(FavoritesFileFailure.NOT_FAVORITES, "Not a ToolMap favorites file")
        }
        if (file.format > FavoritesFile.FORMAT) {
            throw FavoritesFileException(FavoritesFileFailure.UNSUPPORTED_VERSION, "Favorites format ${file.format}")
        }
        if (file.favorites.isEmpty()) throw FavoritesFileException(FavoritesFileFailure.EMPTY, "No favorite")
        val broken =
            file.favorites.size > MAX_FAVORITES ||
                file.favorites.any { it.name.isBlank() || it.name.length > MAX_NAME || it.layers.size > MAX_LAYERS }
        if (broken) throw FavoritesFileException(FavoritesFileFailure.NOT_FAVORITES, "Favorite out of bounds")
        return file
    }

    private const val HEAD_CHARS = 16
}

/** What sharing some favorites puts in the file, from the catalogue as the phone has it (P25.2). */
object FavoritesExport {
    /**
     * The maps the user added (not built in) that [favorites] show, in the order they are first met, with the maps a
     * multi-zoom one shows through its levels: the receiver needs them all to see the same thing.
     */
    fun addedMaps(
        favorites: List<Workspace>,
        find: (String) -> TileSource?,
        isBuiltin: (String) -> Boolean,
    ): List<TileSource> {
        val found = LinkedHashMap<String, TileSource>()

        fun visit(id: String) {
            if (id in found || isBuiltin(id)) return
            val source = find(id) ?: return
            found[id] = source
            source.levels.mapNotNull { it.mapRef?.sourceId }.forEach(::visit)
        }
        favorites.flatMap { it.layers }.forEach { visit(it.sourceId) }
        return found.values.toList()
    }

    /** The added maps of [maps] that carry a key of their own (a parameter with a value): the choice of the key. */
    fun keyed(maps: List<TileSource>): List<TileSource> =
        maps.filter { map -> map.params.values.any { it.isNotBlank() } }

    /**
     * The file for [favorites]. [keepKeys] false writes every parameter of the added maps empty, so that the receiver
     * types their own key (the sender's never leaves the phone).
     */
    fun build(
        favorites: List<Workspace>,
        find: (String) -> TileSource?,
        isBuiltin: (String) -> Boolean,
        keepKeys: Boolean,
        appVersion: String,
    ): FavoritesFile {
        val maps = addedMaps(favorites, find, isBuiltin)
        val redacted = if (keepKeys) emptySet() else maps.flatMapTo(HashSet()) { it.params.keys }
        return FavoritesFile(
            appVersion = appVersion,
            favorites = favorites,
            aqx = if (maps.isEmpty()) null else AqxWriter.write(maps, redactedParams = redacted),
        )
    }

    /** A file name without the characters a file system or a messaging app refuses. */
    fun fileName(base: String): String {
        val clean =
            base
                .replace(UNSAFE, " ")
                .replace(SPACES, " ")
                .trim()
                .trim('.')
                .take(MAX_FILE_NAME)
                .trim()
        return clean.ifEmpty { DEFAULT_NAME } + FavoritesFile.EXTENSION
    }

    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
    private val SPACES = Regex("\\s+")
    private const val MAX_FILE_NAME = 80
    private const val DEFAULT_NAME = "Favoris"
}

/** One favorite of a file, as it will be added (P25.3). */
data class FavoriteImport(
    /** The favorite as it will be saved: its name made unique, its layers pointing at the maps of this phone. */
    val favorite: Workspace,
    val originalName: String,
    /** Maps of the file this favorite needs that the phone does not have: added with it. */
    val added: List<TileSource>,
    /** Of [added], the maps whose key the receiver has to type (a parameter left empty). */
    val needKey: List<TileSource>,
    /** Maps of this phone the favorite shows that the user had hidden from the list: they come back. */
    val unhidden: List<String>,
) {
    val renamed: Boolean get() = favorite.name != originalName
}

/** What adding a `.toolmap` file does, worked out before anything is stored. */
data class FavoritesImportPlan(
    val items: List<FavoriteImport>,
) {
    /** The maps to store for the favorites of [chosen] (their indexes in [items]), each once. */
    fun mapsFor(chosen: Collection<Int>): List<TileSource> =
        chosen.sorted().flatMap { items[it].added }.distinctBy { it.id }

    /** The favorites of [chosen], under the names the sheet showed (unique among those of the file too). */
    fun favoritesFor(chosen: Collection<Int>): List<Workspace> = chosen.sorted().map { items[it].favorite }
}

object FavoritesImport {
    /**
     * Works out how the favorites of [file] land on this phone (decisions of P25): a map of the file the phone already
     * has (same id and tiles, or the same tiles under another id, ADR-031) is reused; a missing one is added, under a
     * new id when its id names another map here; a name already taken gets « (2) »; a map the user hid comes back.
     *
     * @param sources the maps of the file (parsed from [FavoritesFile.aqx]).
     * @param catalogue the maps of this phone.
     * @param taken the names of the favorites of this phone.
     * @param hidden the maps of this phone hidden from the list.
     */
    fun plan(
        file: FavoritesFile,
        sources: List<TileSource>,
        catalogue: List<TileSource>,
        taken: Collection<String>,
        hidden: Set<String>,
    ): FavoritesImportPlan {
        val byId = catalogue.associateBy { it.id }
        val usedIds = catalogue.mapTo(HashSet()) { it.id }
        // Where each map of the file lands: an id of the phone (reused) or the id it is added under.
        val target = HashMap<String, String>()
        val toAdd = LinkedHashMap<String, TileSource>()
        for (source in sources) {
            val here = byId[source.id]
            val reuse =
                when {
                    here != null && sameTiles(here, source) -> here.id
                    else -> SourceDuplicates.of(source, catalogue).duplicateOf?.id
                }
            if (reuse != null) {
                target[source.id] = reuse
                continue
            }
            val copy = if (source.id in usedIds) freeNumber(source.id, usedIds) else null
            val id = copy?.let { "${source.id}-$it" } ?: source.id
            usedIds += id
            target[source.id] = id
            toAdd[source.id] = if (copy == null) source else source.copy(id = id, name = "${source.name} ($copy)")
        }
        // A multi-zoom map shows other maps through its levels: they follow the same ids.
        val added = toAdd.values.map { remapRefs(it, target) }.associateBy { it.id }
        val names = taken.toMutableSet()
        val items =
            file.favorites.map { favorite ->
                val layers = favorite.layers.map { it.copy(sourceId = target[it.sourceId] ?: it.sourceId) }
                val needed = LinkedHashMap<String, TileSource>()

                fun need(id: String) {
                    val map = added[id] ?: return
                    if (needed.put(map.id, map) == null) map.levels.mapNotNull { it.mapRef?.sourceId }.forEach(::need)
                }
                layers.forEach { need(it.sourceId) }
                val name = uniqueName(favorite.name, names)
                names += name
                FavoriteImport(
                    favorite = Workspace(favorite.id, name, layers),
                    originalName = favorite.name,
                    added = needed.values.toList(),
                    needKey = needed.values.filter { map -> map.params.values.any { it.isBlank() } },
                    unhidden = layers.map { it.sourceId }.filter { it in hidden }.distinct(),
                )
            }
        return FavoritesImportPlan(items)
    }

    /** [name], or « [name] (2) », « (3) »… when a favorite of [taken] already has it. */
    fun uniqueName(
        name: String,
        taken: Collection<String>,
    ): String {
        if (name !in taken) return name
        var n = 2
        while ("$name ($n)" in taken) n++
        return "$name ($n)"
    }

    /** Same tiles (ADR-031: keys left aside), or for a multi-zoom map the same maps at the same zooms. */
    private fun sameTiles(
        a: TileSource,
        b: TileSource,
    ): Boolean {
        val mine = TileSetIdentity.allOf(a)
        if (mine.isNotEmpty()) return mine == TileSetIdentity.allOf(b)
        return a.levels.map { it.zooms to it.mapRef } == b.levels.map { it.zooms to it.mapRef }
    }

    /** The first n from 2 such that `id-n` names no map. */
    private fun freeNumber(
        id: String,
        used: Set<String>,
    ): Int {
        var n = 2
        while ("$id-$n" in used) n++
        return n
    }

    private fun remapRefs(
        map: TileSource,
        target: Map<String, String>,
    ): TileSource {
        if (!map.isMultiZoom) return map
        val levels =
            map.levels.map { level ->
                val ref = level.mapRef ?: return@map level
                val id = target[ref.sourceId] ?: return@map level
                if (id == ref.sourceId) level else level.copy(servers = listOf(ref.copy(sourceId = id).toServer()))
            }
        return map.copy(levels = levels)
    }
}
