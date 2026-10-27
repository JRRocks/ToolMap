package fr.toolmap.data.sources

import fr.toolmap.core.aqx.AqxParseException
import fr.toolmap.core.aqx.AqxParser
import fr.toolmap.core.aqx.AqxWarning
import fr.toolmap.core.aqx.AqxWriter
import fr.toolmap.core.source.BuiltinSources
import fr.toolmap.core.source.DuplicateInfo
import fr.toolmap.core.source.SourceDuplicates
import fr.toolmap.core.source.TileSource
import fr.toolmap.data.tiles.SourceLookup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** What to do when an imported source has the id of one already present. */
enum class DuplicatePolicy {
    /** Replace the existing imported source (a built-in one is never replaced: the import is renamed). */
    REPLACE,

    /** Keep both: the imported source gets a new id (`id-2`) and name (`name (2)`). */
    KEEP_BOTH,

    /** Do not import it. */
    SKIP,
}

data class ImportEntry(
    val source: TileSource,
    /** Another source (imported or built-in) already has this id. */
    val idInUse: Boolean,
    val idInUseByBuiltin: Boolean,
    /** Maps of the catalogue serving the same tiles (ADR-031): no second download either way. */
    val duplicates: DuplicateInfo = DuplicateInfo.NONE,
)

/** Result of reading an `.aqx` file before anything is stored: shown to the user for confirmation. */
data class ImportPreview(
    val fileName: String?,
    val description: String?,
    val entries: List<ImportEntry>,
    val warnings: List<AqxWarning>,
)

data class ImportOutcome(
    val added: List<TileSource>,
    val replaced: List<TileSource>,
    val skippedIds: List<String>,
)

class SourceImportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * All known tile sources: the built-in catalogue plus the ones the user imported or created. Imported
 * sources are stored one file each (`<dir>/<id>.aqx`, written with [AqxWriter]) so they survive restarts and
 * can be exported unchanged. Implements [SourceLookup] for the tile interceptor, and always reflects the
 * latest list (no restart needed after an import).
 */
class SourceRepository(
    private val dir: File,
    private val builtin: List<TileSource> = BuiltinSources.load(),
) : SourceLookup {
    private data class Snapshot(
        val imported: List<TileSource>,
    )

    private val state = MutableStateFlow(Snapshot(emptyList()))
    private val builtinIds = builtin.map { it.id }.toSet()

    /** Built-in sources first, then imported ones in import order. */
    private val allFlow = MutableStateFlow(builtin)
    val sources: StateFlow<List<TileSource>> = allFlow.asStateFlow()

    init {
        reload()
    }

    override fun find(sourceId: String): TileSource? = allFlow.value.firstOrNull { it.id == sourceId }

    fun isBuiltin(id: String): Boolean = id in builtinIds

    fun isImported(id: String): Boolean = state.value.imported.any { it.id == id }

    /** Reads the files of the storage directory (damaged files are skipped, never fatal). */
    fun reload() {
        val loaded =
            dir
                .listFiles { f -> f.isFile && f.name.endsWith(".aqx") }
                .orEmpty()
                .sortedBy { it.name }
                .flatMap { file ->
                    runCatching {
                        AqxParser.parse(file.readText(Charsets.UTF_8)).file.sources
                    }.getOrDefault(emptyList())
                }.filter { it.id !in builtinIds }
                .distinctBy { it.id }
        publish(loaded)
    }

    /** Parses [text] without storing anything. */
    @Throws(SourceImportException::class)
    fun preview(
        text: String,
        fileName: String? = null,
    ): ImportPreview {
        val result =
            try {
                AqxParser.parse(text)
            } catch (e: AqxParseException) {
                throw SourceImportException(e.message ?: "Invalid AQX file", e)
            }
        if (result.file.sources.isEmpty()) throw SourceImportException("The file contains no map source")
        val catalogue = allFlow.value
        val entries =
            result.file.sources.map {
                ImportEntry(
                    source = it,
                    idInUse = find(it.id) != null,
                    idInUseByBuiltin = it.id in builtinIds,
                    duplicates = SourceDuplicates.of(it, catalogue),
                )
            }
        return ImportPreview(fileName, result.file.description, entries, result.warnings)
    }

    /** Stores the sources of [preview] whose id is in [selectedIds] (all by default). */
    fun import(
        preview: ImportPreview,
        policy: DuplicatePolicy = DuplicatePolicy.KEEP_BOTH,
        selectedIds: Set<String>? = null,
    ): ImportOutcome {
        val added = mutableListOf<TileSource>()
        val replaced = mutableListOf<TileSource>()
        val skipped = mutableListOf<String>()
        for (entry in preview.entries) {
            val source = entry.source
            if (selectedIds != null && source.id !in selectedIds) continue
            val taken = find(source.id) != null
            when {
                !taken -> {
                    store(source)
                    added += source
                }

                policy == DuplicatePolicy.SKIP -> {
                    skipped += source.id
                }

                policy == DuplicatePolicy.REPLACE && !isBuiltin(source.id) -> {
                    store(source)
                    replaced += source
                }

                else -> {
                    val renamed = renamedCopy(source)
                    store(renamed)
                    added += renamed
                }
            }
        }
        return ImportOutcome(added, replaced, skipped)
    }

    /** Creates or updates one imported source (used by the add-a-map wizard). Built-in ids are refused. */
    fun save(source: TileSource) {
        require(!isBuiltin(source.id)) { "Built-in source ${source.id} cannot be modified" }
        store(source)
    }

    /**
     * The map of the catalogue that already gives [source]: the one with its id, or else one with the same tiles (a map
     * of the online catalogue is not offered again under another name, ADR-031).
     */
    fun alreadyHave(source: TileSource): TileSource? =
        find(source.id) ?: SourceDuplicates.of(source, allFlow.value).duplicateOf

    /** Adds a map of the online catalogue (P16.4). Returns false, storing nothing, when its id is already in use. */
    fun addFromCatalog(source: TileSource): Boolean {
        if (find(source.id) != null) return false
        store(source)
        return true
    }

    /** Removes an imported source; built-in ones cannot be removed. Returns whether something was removed. */
    fun delete(id: String): Boolean {
        if (!isImported(id)) return false
        fileFor(id).delete()
        publish(state.value.imported.filterNot { it.id == id })
        return true
    }

    /** The sources of [ids] as one shareable `.aqx` text. [redactedParams] blank the named parameters. */
    fun export(
        ids: Collection<String>,
        redactedParams: Set<String> = emptySet(),
    ): String = AqxWriter.write(ids.mapNotNull(::find), redactedParams = redactedParams)

    private fun store(source: TileSource) {
        dir.mkdirs()
        val target = fileFor(source.id)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(AqxWriter.write(listOf(source)), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            target.delete()
            check(temp.renameTo(target)) { "Cannot write ${target.name}" }
        }
        publish(state.value.imported.filterNot { it.id == source.id } + source)
    }

    private fun renamedCopy(source: TileSource): TileSource {
        var n = 2
        while (find("${source.id}-$n") != null) n++
        return source.copy(id = "${source.id}-$n", name = "${source.name} ($n)")
    }

    private fun publish(imported: List<TileSource>) {
        state.update { Snapshot(imported) }
        allFlow.value = builtin + imported
    }

    private fun fileFor(id: String): File {
        val safe = id.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val hash = Integer.toHexString(id.hashCode())
        return File(dir, "$safe-$hash.aqx")
    }
}
