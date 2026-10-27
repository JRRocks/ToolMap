package fr.toolmap.core.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Why a backup could not be written, read or applied. */
enum class BackupFailure {
    /** The file is not a ToolMap backup (not a zip, no manifest first, wrong application). */
    NOT_A_BACKUP,

    /** Made by a newer version of the format than this one reads. */
    UNSUPPORTED_VERSION,

    /** A size or a checksum does not match, an entry is missing or not listed, a file changed while it was copied. */
    CORRUPT,

    /** An entry would land outside the places a backup may write to. */
    UNSAFE,

    /** The offline maps are on a removable card that is not mounted: they can be neither read nor written (ADR-042). */
    TILES_UNAVAILABLE,

    NO_SPACE,
    IO,
}

class BackupException(
    val failure: BackupFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** What a backup covers. A restore replaces the whole of each area the backup lists, and leaves the others alone. */
enum class BackupArea(
    val id: String,
    /** Left out of a backup unless asked for (big). */
    val optional: Boolean = false,
) {
    /** The tracks, routes, waypoints and folders. */
    DATABASE("database"),
    SETTINGS("settings"),
    ALERTS("alerts"),

    /** The camera and layer stack of the last session. */
    STATE("state"),

    /** The map sources the user imported or created (`.aqx`). */
    SOURCES("sources"),
    WORKSPACES("workspaces"),
    PRINT("print"),

    /** The offline maps (`.mbtiles`). */
    TILES("tiles", optional = true),
    ;

    companion object {
        fun of(id: String): BackupArea? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Which files a backup may hold and where each belongs. Everything else is refused, so that a crafted archive cannot
 * write anywhere else (path traversal) nor put the secret key of the position sharing (`p2p/`) or a half-written
 * recording (`recording/`) back. Paths use `/`, are relative, and have at most one folder level.
 */
object BackupPaths {
    const val MANIFEST = "manifest.json"
    const val DATABASE_FILE = "tracks.db"
    const val SETTINGS_FILE = "settings.json"
    const val ALERTS_FILE = "alerts.json"

    /** The built-in favorites the user deleted (ADR-052), kept beside the saved ones in `workspaces/`. */
    const val HIDDEN_BUILTINS_FILE = "hidden-builtins.txt"
    private const val MAX_NAME = 200

    /** The area of an allowed path, `null` for any other. */
    fun areaOf(path: String): BackupArea? {
        if (!isSafe(path)) return null
        val parts = path.split('/')
        return when (parts.size) {
            1 -> {
                when (path) {
                    DATABASE_FILE -> BackupArea.DATABASE
                    SETTINGS_FILE -> BackupArea.SETTINGS
                    ALERTS_FILE -> BackupArea.ALERTS
                    else -> null
                }
            }

            2 -> {
                val (folder, name) = parts
                when {
                    folder == "state" && name.endsWith(".json") -> {
                        BackupArea.STATE
                    }

                    folder == "sources" && name.endsWith(".aqx") -> {
                        BackupArea.SOURCES
                    }

                    folder == "workspaces" && (name.endsWith(".json") || name == HIDDEN_BUILTINS_FILE) -> {
                        BackupArea.WORKSPACES
                    }

                    folder == "print" && name.endsWith(".json") -> {
                        BackupArea.PRINT
                    }

                    folder == "tiles" && name.endsWith(".mbtiles") -> {
                        BackupArea.TILES
                    }

                    else -> {
                        null
                    }
                }
            }

            else -> {
                null
            }
        }
    }

    /** Relative, forward slashes, no `.` or `..` segment, no empty segment, no control character or backslash. */
    fun isSafe(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/")) return false
        return path.split('/').all { segment ->
            segment.isNotEmpty() &&
                segment != "." &&
                segment != ".." &&
                segment.length <= MAX_NAME &&
                !segment.startsWith('.') &&
                segment.none { it == '\\' || it.code < SPACE }
        }
    }

    private const val SPACE = 0x20
}

@Serializable
data class BackupEntry(
    val path: String,
    val size: Long,
    /** Lower-case hexadecimal SHA-256 of the content. */
    val sha256: String,
)

/**
 * The first entry of a backup (`manifest.json`): what the archive holds, with a checksum of each file, so that a
 * restore can check everything before it touches anything.
 */
@Serializable
data class BackupManifest(
    val format: Int = FORMAT,
    val app: String = APP,
    val appVersion: String = "",
    val createdAtMillis: Long = 0L,
    /** The [BackupArea.id]s the backup covers, empty ones included. */
    val areas: List<String> = emptyList(),
    val entries: List<BackupEntry> = emptyList(),
) {
    val totalBytes: Long get() = entries.sumOf { it.size }

    fun covers(area: BackupArea): Boolean = area.id in areas

    fun fileCount(area: BackupArea): Int = entries.count { BackupPaths.areaOf(it.path) == area }

    companion object {
        const val FORMAT = 1
        const val APP = "ToolMap"
    }
}

object BackupManifestCodec {
    private const val MAX_ENTRIES = 100_000
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024 * 1024
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(manifest: BackupManifest): String = json.encodeToString(BackupManifest.serializer(), manifest)

    /** @throws BackupException when [text] is not a manifest this version can apply. */
    fun decode(text: String): BackupManifest {
        val manifest =
            try {
                json.decodeFromString(BackupManifest.serializer(), text)
            } catch (e: SerializationException) {
                throw BackupException(BackupFailure.NOT_A_BACKUP, "Unreadable manifest", e)
            } catch (e: IllegalArgumentException) {
                throw BackupException(BackupFailure.NOT_A_BACKUP, "Unreadable manifest", e)
            }
        validate(manifest)
        return manifest
    }

    fun validate(manifest: BackupManifest) {
        if (manifest.app !=
            BackupManifest.APP
        ) {
            throw BackupException(BackupFailure.NOT_A_BACKUP, "Not a ToolMap backup")
        }
        if (manifest.format > BackupManifest.FORMAT) {
            throw BackupException(BackupFailure.UNSUPPORTED_VERSION, "Backup format ${manifest.format}")
        }
        if (manifest.format < 1) throw BackupException(BackupFailure.NOT_A_BACKUP, "Backup format ${manifest.format}")
        val areas =
            manifest.areas.map {
                BackupArea.of(it) ?: throw BackupException(BackupFailure.NOT_A_BACKUP, "Unknown area $it")
            }
        if (areas.toSet().size != areas.size) throw BackupException(BackupFailure.NOT_A_BACKUP, "Area listed twice")
        if (manifest.entries.size > MAX_ENTRIES) throw BackupException(BackupFailure.NOT_A_BACKUP, "Too many files")
        val seen = HashSet<String>()
        for (entry in manifest.entries) {
            val area =
                BackupPaths.areaOf(entry.path)
                    ?: throw BackupException(BackupFailure.UNSAFE, "Path not allowed: ${entry.path}")
            if (area !in areas) throw BackupException(BackupFailure.CORRUPT, "${entry.path} is outside the areas")
            if (!seen.add(entry.path)) throw BackupException(BackupFailure.CORRUPT, "${entry.path} listed twice")
            if (entry.size < 0 || entry.size > MAX_ENTRY_BYTES) {
                throw BackupException(BackupFailure.CORRUPT, "Size of ${entry.path}")
            }
            if (!SHA256.matches(entry.sha256)) throw BackupException(BackupFailure.CORRUPT, "Checksum of ${entry.path}")
        }
    }
}

/** What happened to a restore applied at start-up, kept until the user has seen it. */
@Serializable
data class RestoreReport(
    val ok: Boolean,
    val atMillis: Long,
    /** When the backup was made. */
    val backupMillis: Long = 0L,
    val files: Int = 0,
    /** Why it failed, for the log; the screen only says that it did. */
    val error: String? = null,
)

object RestoreReportCodec {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    fun encode(report: RestoreReport): String = json.encodeToString(RestoreReport.serializer(), report)

    fun decodeOrNull(text: String?): RestoreReport? =
        if (text.isNullOrBlank()) {
            null
        } else {
            try {
                json.decodeFromString(RestoreReport.serializer(), text)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
