package fr.toolmap.data.workspaces

import fr.toolmap.core.backup.BackupPaths
import fr.toolmap.core.layers.Workspace
import fr.toolmap.core.layers.WorkspaceCodec
import fr.toolmap.core.layers.Workspaces
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * The workspaces the user saved, one JSON file each (`<dir>/<id>.json`, replaced atomically). The built-in
 * presets live in code (`Workspaces.builtin`) and are not stored; the ones the user deleted are listed in
 * `<dir>/hidden-builtins.txt`. A damaged file is ignored, never fatal.
 */
class WorkspaceStore(
    private val dir: File,
) {
    private val state = MutableStateFlow(load())
    private val hidden = MutableStateFlow(loadHidden())
    private val favoritesState = MutableStateFlow(favoritesOf(hidden.value, state.value))

    /** Saved workspaces, oldest first. */
    val workspaces: StateFlow<List<Workspace>> = state.asStateFlow()

    /** Ids of the built-in presets the user deleted. */
    val hiddenBuiltins: StateFlow<Set<String>> = hidden.asStateFlow()

    /** What the favorites list shows: the built-in presets not deleted, then the saved workspaces. */
    val favorites: StateFlow<List<Workspace>> = favoritesState.asStateFlow()

    /** Saves [stackAsWorkspace] under a fresh id (or replaces the saved workspace with the same id). */
    fun save(workspace: Workspace) {
        require(!workspace.isBuiltin) { "Built-in presets cannot be overwritten" }
        dir.mkdirs()
        val target = fileFor(workspace.id)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(WorkspaceCodec.encode(workspace), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write ${target.name}" }
        }
        state.value = state.value.filterNot { it.id == workspace.id } + workspace
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

    /** Shows again every built-in preset the user deleted. */
    fun restoreBuiltins() {
        writeHidden(emptySet())
        refresh()
    }

    private fun refresh() {
        favoritesState.value = favoritesOf(hidden.value, state.value)
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

    private fun loadHidden(): Set<String> =
        runCatching { File(dir, HIDDEN_FILE).readLines(Charsets.UTF_8) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    private fun load(): List<Workspace> =
        dir
            .listFiles { f -> f.isFile && f.name.endsWith(EXTENSION) }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .mapNotNull { file ->
                runCatching { file.readText(Charsets.UTF_8) }.getOrNull()?.let(WorkspaceCodec::decodeOrNull)
            }
            .filterNot { it.isBuiltin }

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

        private fun favoritesOf(
            hidden: Set<String>,
            saved: List<Workspace>,
        ): List<Workspace> = Workspaces.builtin.filterNot { it.id in hidden } + saved

        /** An id for a workspace the user is saving now. */
        fun newId(): String = "ws-${UUID.randomUUID()}"
    }
}
