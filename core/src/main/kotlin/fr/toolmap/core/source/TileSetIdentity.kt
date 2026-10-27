package fr.toolmap.core.source

import java.security.MessageDigest

/**
 * Which tiles a level really serves, whatever map file describes it (ADR-031). AlpineQuest keeps the tiles of each
 * `.aqx` source apart, so the same IGN map described by three files is downloaded three times; ToolMap stores tiles
 * by this identity instead, and every map that serves the same tiles shares them.
 *
 * The identity is built from the URL templates of the level, normalized so that only differences that change the
 * image count:
 * - `{$param:k}` values are substituted, then API keys (`apikey`, `key`, `token`…) are dropped: two keys give the
 *   same tiles;
 * - the scheme is ignored (`http` / `https`);
 * - the host is lower-cased and a load-balancing first label (`a.`, `b.`, `mt0.`, `khm1.`) becomes `*`;
 * - query parameters are sorted, with names and values lower-cased (`SERVICE=WMTS` = `service=wmts`);
 * - the zoom levels are not part of it: a file that stops at zoom 14 and another that goes to 16 share zooms 6–14.
 *
 * The path stays case-sensitive. Two levels differing only in a way not listed here are simply not shared (stored
 * twice, as before): the rules err on the side of never mixing two different maps.
 */
object TileSetIdentity {
    private val KEY_NAMES =
        setOf("apikey", "api_key", "api-key", "key", "token", "access_token", "accesstoken", "appid", "app_id")
    private val PARAM_REF = Regex("\\{\\\$param:([^\\}]+)\\}")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val ROTATING_LABEL = Regex("^([a-z]|[a-z]{0,4}[0-9]{1,2}|\\{\\$[a-z]+\\})$")
    private const val HASH_CHARS = 16
    private const val STORAGE_PREFIX = "t-"

    /** Identity of the tiles of [level], or `null` for a level without a server (local package, map reference). */
    fun of(
        source: TileSource,
        level: LevelDef,
    ): String? {
        if (!level.hasServer) return null
        val servers = level.servers.map { normalizeServer(it, source.params) }.distinct().sorted()
        val usesBbox = level.servers.any { it.contains("bbox}") }
        return buildString {
            append(servers.joinToString("|"))
            append("#").append(level.tileSize)
            if (usesBbox) append("#").append(level.bboxCrs)
        }
    }

    /** The identities of every level of [source] that has a server. */
    fun allOf(source: TileSource): Set<String> = source.levels.mapNotNull { of(source, it) }.toSet()

    /** Name of the storage of the tiles of [identity] (`t-` + 16 hexadecimal characters of its SHA-1). */
    fun storageIdOf(identity: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(identity.toByteArray(Charsets.UTF_8))
        return STORAGE_PREFIX + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }.take(HASH_CHARS)
    }

    /** One URL template in normalized form (see the class comment). */
    fun normalizeServer(
        template: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        val expanded = PARAM_REF.replace(template.trim()) { m -> params[m.groupValues[1]] ?: m.value }
        val noScheme = expanded.replaceFirst(SCHEME, "").substringBefore('#')
        val authorityEnd = noScheme.indexOfFirst { it == '/' || it == '?' }.let { if (it < 0) noScheme.length else it }
        val authority = noScheme.substring(0, authorityEnd).substringAfterLast('@').lowercase()
        val rest = noScheme.substring(authorityEnd)
        val path = rest.substringBefore('?')
        val query = rest.substringAfter('?', "")
        return normalizeHost(authority) + path + normalizeQuery(query)
    }

    private fun normalizeHost(authority: String): String {
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "")
        val labels = host.split('.')
        val normalized =
            if (labels.size >= 3 && ROTATING_LABEL.matches(labels[0])) {
                (listOf("*") + labels.drop(1)).joinToString(".")
            } else {
                host
            }
        return if (port.isEmpty()) normalized else "$normalized:$port"
    }

    private fun normalizeQuery(query: String): String {
        val pairs =
            query
                .split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val name = pair.substringBefore('=').lowercase()
                    val value = pair.substringAfter('=', "").lowercase()
                    if (name.isEmpty() || name in KEY_NAMES || (pair.contains('=') && value.isEmpty())) {
                        null
                    } else {
                        if (pair.contains('=')) "$name=$value" else name
                    }
                }.sorted()
        return if (pairs.isEmpty()) "" else "?" + pairs.joinToString("&")
    }
}

/**
 * Where the tiles of a map are stored, zoom by zoom (ADR-031):
 * - an ordinary level: the storage of its [TileSetIdentity], named after the built-in map that serves the same tiles
 *   when there is one (so the files of earlier versions keep their name), `t-<hash>` otherwise;
 * - a local package: the map's own id (its tiles cannot be downloaded again);
 * - a [MapRef] level: the storage of the referenced map; nothing when the zoom is forced to another level (those
 *   tiles are built from the referenced map's tiles, never stored).
 */
class StorageKeys(
    builtins: List<TileSource>,
) {
    private val aliases: Map<String, String> =
        buildMap {
            for (source in builtins) {
                for (level in source.levels) {
                    val identity = TileSetIdentity.of(source, level) ?: continue
                    if (identity !in this) put(identity, source.id)
                }
            }
        }

    /**
     * Shared storages (`t-<hash>`) whose tiles a built-in map now serves, each mapped to that map's storage: a tile
     * set first stored for an imported map, then shipped in the catalogue (ADR-069), changes storage name.
     */
    val takenOverByBuiltins: Map<String, String> =
        aliases.entries.associate { (identity, id) -> TileSetIdentity.storageIdOf(identity) to id }

    /** Storage of the tile of zoom [zoom] of [source], or `null` when such a tile is never stored. */
    fun storageIdFor(
        source: TileSource,
        zoom: Int,
        lookup: (String) -> TileSource?,
    ): String? = resolve(source, zoom, lookup, 0)

    /** Storage of [level] of [source] at its own tile zoom (`null` for a reference). */
    fun storageIdOf(
        source: TileSource,
        level: LevelDef,
    ): String? =
        when {
            level.isLocalOnly -> source.id
            level.mapRef != null -> null
            else -> TileSetIdentity.of(source, level)?.let { aliases[it] ?: TileSetIdentity.storageIdOf(it) }
        }

    /** Every storage the tiles shown by [source] can come from (several for a multi-zoom map). */
    fun storageIdsOf(
        source: TileSource,
        lookup: (String) -> TileSource?,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        collect(source, lookup, out, 0)
        return out
    }

    private fun collect(
        source: TileSource,
        lookup: (String) -> TileSource?,
        out: MutableSet<String>,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        for (level in source.levels) {
            val ref = level.mapRef
            if (ref == null) {
                storageIdOf(source, level)?.let(out::add)
                continue
            }
            val member = lookup(ref.sourceId) ?: continue
            if (member.id == source.id) continue
            if (ref.tileZoom != null) {
                resolve(member, ref.tileZoom, lookup, depth + 1)?.let(out::add)
            } else {
                collect(member, lookup, out, depth + 1)
            }
        }
    }

    private fun resolve(
        source: TileSource,
        zoom: Int,
        lookup: (String) -> TileSource?,
        depth: Int,
    ): String? {
        if (depth > MAX_DEPTH) return null
        val level = source.levelFor(zoom) ?: return null
        val ref = level.mapRef ?: return storageIdOf(source, level)
        if (ref.tileZoom != null && ref.tileZoom != zoom) return null
        val member = lookup(ref.sourceId) ?: return null
        return resolve(member, zoom, lookup, depth + 1)
    }

    /** True when [storageId] names the storage of a built-in map or a shared tile set (not a map's own file). */
    fun isSharedStorage(storageId: String): Boolean =
        storageId in aliases.values || storageId.startsWith(STORAGE_PREFIX)

    companion object {
        /** Longest chain of maps referencing maps that is followed (a cycle stops here). */
        const val MAX_DEPTH = 4
        private const val STORAGE_PREFIX = "t-"

        /** The keys of the shipped catalogue. */
        val BUILTIN: StorageKeys by lazy { StorageKeys(BuiltinSources.load()) }
    }
}
