package fr.toolmap.data.tracks

import fr.toolmap.core.track.RawXml
import fr.toolmap.core.track.TrackJson

/**
 * Raw XML fragments rely on the namespace prefixes of the file they were imported from. Exporting items of several
 * imports into one file merges those prefixes onto the root; where two imports use one prefix for different
 * namespaces, the fragments of the later one declare their own.
 */
internal class NamespaceMerger(
    private val library: TrackLibrary,
) {
    private val map = LinkedHashMap<String, String>()
    private val byRoot = HashMap<Long?, Pair<Map<String, String>, Boolean>>()

    val merged: Map<String, String> get() = map

    /** Runs [rewrite] with the function that fixes the fragments of an item of [folderId]. */
    fun <T> adopt(
        folderId: Long?,
        rewrite: ((List<RawXml>) -> List<RawXml>) -> T,
    ): T {
        val root = library.chain(folderId).lastOrNull()?.id
        val (own, conflict) =
            byRoot.getOrPut(root) {
                if (root == null) {
                    // Top of the library (drawn, recorded, or moved out of its import): every import's prefixes are
                    // offered and each fragment declares the ones it uses itself.
                    val all = LinkedHashMap<String, String>()
                    for (f in library.folders.filter { it.parentId == null }) {
                        for ((prefix, uri) in TrackJson.decodeNamespaces(f.namespacesJson)) all.putIfAbsent(prefix, uri)
                    }
                    return@getOrPut all to true
                }
                val own = TrackJson.decodeNamespaces(library.folders.firstOrNull { it.id == root }?.namespacesJson)
                var clash = false
                for ((prefix, uri) in own) {
                    val existing = map.putIfAbsent(prefix, uri)
                    if (existing != null && existing != uri) clash = true
                }
                own to clash
            }
        return rewrite { fragments -> if (conflict) fragments.map { it.declaring(own) } else fragments }
    }
}
