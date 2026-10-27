package fr.toolmap.data.print

import fr.toolmap.core.print.PrintTemplate
import fr.toolmap.core.print.PrintTemplateCodec
import java.io.File

/**
 * The settings of the last print (format, scale, grid, style, options), in a small JSON file replaced atomically, so
 * the next print starts from them (ADR-061). A damaged or foreign file gives defaults, never an error.
 */
class PrintTemplateStore(
    private val dir: File,
) {
    private val lastFile = File(dir, LAST_FILE)

    /** The settings of the last print, or the defaults. */
    fun last(): PrintTemplate = lastOrNull() ?: PrintTemplate()

    /** The settings of the last print; `null` before the first one (or when the file is unreadable). */
    fun lastOrNull(): PrintTemplate? =
        runCatching { lastFile.readText(Charsets.UTF_8) }
            .getOrNull()
            ?.let(PrintTemplateCodec::decode)

    fun remember(template: PrintTemplate) {
        dir.mkdirs()
        val temp = File(dir, lastFile.name + ".tmp")
        temp.writeText(PrintTemplateCodec.encode(template), Charsets.UTF_8)
        if (!temp.renameTo(lastFile)) {
            lastFile.delete()
            check(temp.renameTo(lastFile)) { "Cannot write ${lastFile.name}" }
        }
    }

    private companion object {
        const val LAST_FILE = "last.json"
    }
}
