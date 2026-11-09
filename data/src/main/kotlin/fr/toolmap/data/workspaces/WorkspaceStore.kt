package fr.toolmap.data.workspaces

import fr.toolmap.core.backup.BackupPaths
import fr.toolmap.core.layers.Favorites
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.WorkspaceCodec
import fr.toolmap.core.layers.Workspaces
import fr.toolmap.core.problem.catchExpected
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The workspaces the user saved, one JSON file each (`<dir>/<id>.json`, replaced atomically). The built-in
 * presets live in code (`Workspaces.builtin`); the user's version of one is a file under the built-in id, which takes
 * its place until « Rétablir l'original » (ADR-108). The ones the user deleted are listed in
 * `<dir>/hidden-builtins.txt`. A damaged file is ignored, never fatal.
 */
class WorkspaceStore(
    private val dir: File,
) {
    private val loaded = load()
    private val state = MutableStateFlow(loaded.filterNot { it.isBuiltin })

    // The user's versions of the built-in presets; a file of a preset no longer shipped is ignored.
    private val overrides =
        MutableStateFlow(loaded.filter { Favorites.original(it.id) != null }.associateBy { it.id })
    private val hidden = MutableStateFlow(loadHidden())
    private val favoritesState = MutableStateFlow(Favorites.list(hidden.value, overrides.value, state.value))

    /** Saved workspaces, oldest first (the user's versions of built-in presets are in [favorites] only). */
    val workspaces: StateFlow<List<Workspace>> = state.asStateFlow()

    /** Ids of the built-in presets the user deleted. */
    val hiddenBuiltins: StateFlow<Set<String>> = hidden.asStateFlow()

    /**
     * What the favorites list shows: the built-in presets not deleted (the user's version of each when there is one),
     * then the saved workspaces.
     */
    val favorites: StateFlow<List<Workspace>> = favoritesState.asStateFlow()

    /**
     * Saves [workspace]: a new one, or in place of the one with the same id. A built-in preset is stored as the user's
     * version of it; saved as shipped, it is the original again.
     */
    fun save(workspace: Workspace) {
        val original = Favorites.original(workspace.id)
        require(!workspace.isBuiltin || original != null) { "Unknown built-in preset ${workspace.id}" }
        if (workspace == original) return restoreOriginal(workspace.id)
        dir.mkdirs()
        val target = fileFor(workspace.id)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(WorkspaceCodec.encode(workspace), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write ${target.name}" }
        }
        if (original != null) {
            overrides.value += workspace.id to workspace
        } else {
            state.value = state.value.filterNot { it.id == workspace.id } + workspace
        }
        refresh()
    }

    /** Puts back the built-in preset [id] as shipped, forgetting the user's version of it (ADR-108). */
    fun restoreOriginal(id: String) {
        if (Favorites.original(id) == null) return
        fileFor(id).delete()
        overrides.value -= id
        refresh()
    }

    /** Deletes a saved workspace, or hides a built-in preset (it comes back with [restoreBuiltins]). */
    fun delete(id: String) {
        if (Workspaces.builtin.any { it.id == id }) {
            writeHidden(hidden.value + id)
        } else {
            fileFor(id).delete()
            state.value = state.value.filterNot { it.id == id }
        }
        refresh()
    }

    /** Shows again the built-in preset [id] the user just deleted (« Annuler » after the delete, P29.3). */
    fun unhide(id: String) {
        writeHidden(hidden.value - id)
        refresh()
    }

    /** Shows again every built-in preset the user deleted. */
    fun restoreBuiltins() {
        writeHidden(emptySet())
        refresh()
    }

    private fun refresh() {
        favoritesState.value = Favorites.list(hidden.value, overrides.value, state.value)
    }

    private fun writeHidden(ids: Set<String>) {
        val file = File(dir, HIDDEN_FILE)
        if (ids.isEmpty()) {
            file.delete()
        } else {
            dir.mkdirs()
            file.writeText(ids.sorted().joinToString("\n"), Charsets.UTF_8)
        }
        hidden.value = ids
    }

    /** The built-in presets the user deleted; none when the file is missing (none deleted) or unreadable (logged). */
    private fun loadHidden(): Set<String> {
        val lines =
            catchExpected("workspaces.store.read_hidden", IOException::class) {
                File(dir, HIDDEN_FILE).takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty()
            }.getOrDefault(emptyList())
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun load(): List<Workspace> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { file ->
                catchExpected("workspaces.store.read", IOException::class) { file.readText(Charsets.UTF_8) }
                    .getOrNull()
                    ?.let(WorkspaceCodec::decodeOrNull)
            }

    private fun fileFor(id: String): File =
        File(
            dir,
            id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(MAX_NAME) + EXTENSION,
        )

    companion object {
        private const val EXTENSION = ".json"
        private const val MAX_NAME = 80

        // Part of the backup's `workspaces` area (BackupPaths), so that deleted built-ins stay deleted after a restore.
        private const val HIDDEN_FILE = BackupPaths.HIDDEN_BUILTINS_FILE

        /** An id for a workspace the user is saving now. */
        fun newId(): String = "ws-${UUID.randomUUID()}"
    }
}
