package fr.toolmap.data.tracks

/** Everything in the library at one moment: what the list screen and the map layer are drawn from. */
class TrackLibrary(
    val folders: List<FolderEntity>,
    val tracks: List<TrackEntity>,
    val routes: List<RouteEntity>,
    val waypoints: List<WaypointEntity>,
) {
    private val folderById = folders.associateBy { it.id }

    val isEmpty: Boolean get() = tracks.isEmpty() && routes.isEmpty() && waypoints.isEmpty() && folders.isEmpty()

    /** The folder and its ancestors, innermost first. */
    fun chain(folderId: Long?): List<FolderEntity> {
        val out = ArrayList<FolderEntity>()
        var id = folderId
        while (id != null && out.size < MAX_DEPTH) {
            val f = folderById[id] ?: break
            out += f
            id = f.parentId
        }
        return out
    }

    /** `Trip / Day 1`: the folders an item sits in, outermost first. */
    fun pathOf(folderId: Long?): String = chain(folderId).asReversed().joinToString(" / ") { it.name }

    /** An item is drawn when it is visible itself and so is every folder above it. */
    fun isShown(
        folderId: Long?,
        itemVisible: Boolean,
    ): Boolean = itemVisible && chain(folderId).all { it.visible }

    /** Ids of the folder and of every folder below it. */
    fun descendants(folderId: Long): Set<Long> {
        val out = linkedSetOf(folderId)
        var grew = true
        while (grew) {
            grew = false
            for (f in folders) if (f.parentId in out && out.add(f.id)) grew = true
        }
        return out
    }

    private companion object {
        const val MAX_DEPTH = 64
    }
}

enum class ItemKind { TRACK, ROUTE, WAYPOINT }

/** One item of the library, whatever its kind. */
data class ItemRef(
    val kind: ItemKind,
    val id: Long,
)

/** What to export: the items, plus whole folders (their contents, at any depth). */
data class TrackSelection(
    val trackIds: List<Long> = emptyList(),
    val routeIds: List<Long> = emptyList(),
    val waypointIds: List<Long> = emptyList(),
    val folderIds: List<Long> = emptyList(),
)

/** What an import put in the library. */
class ImportSummary(
    val folderId: Long,
    val tracks: Int,
    val routes: Int,
    val waypoints: Int,
    val points: Int,
    val issues: List<fr.toolmap.core.track.TrackIssue>,
)
