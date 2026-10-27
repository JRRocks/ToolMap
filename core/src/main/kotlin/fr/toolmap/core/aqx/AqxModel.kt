package fr.toolmap.core.aqx

import fr.toolmap.core.source.TileSource

/** A parsed `.aqx` file. */
data class AqxFile(
    val version: Int?,
    val name: String?,
    val description: String?,
    /** File-level `<param>` values (also copied into each source). */
    val params: Map<String, String>,
    val sources: List<TileSource>,
)

/** Something in the file that was ignored or only partly understood. The import still succeeds. */
data class AqxWarning(
    /** Where it happened, e.g. `source IGN_MAPS` or `file`. */
    val context: String,
    val message: String,
)

data class AqxParseResult(
    val file: AqxFile,
    val warnings: List<AqxWarning>,
)

/** The file is not a usable AQX document (not XML, wrong root element, forbidden construct...). */
class AqxParseException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
