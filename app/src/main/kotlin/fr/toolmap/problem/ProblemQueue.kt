package fr.toolmap.problem

import fr.toolmap.core.problem.ProblemKind
import fr.toolmap.core.problem.ProblemReport
import fr.toolmap.core.problem.Problems
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException

/**
 * The reports waiting to be sent to GitHub (ADR-116): one file per fingerprint in `files/problems/queue/`, so that the
 * same bug met a thousand times is one report with its count. Files are written whole (temporary file, then renamed):
 * a crash in the middle leaves the previous version. At most [MAX_FILES] are kept and none older than [MAX_AGE_MS].
 */
class ProblemQueue(
    filesDir: File,
) {
    private val dir = File(filesDir, "problems/queue")

    /** Adds [report], or merges it into the waiting one of the same fingerprint (count, last time, worst kind). */
    @Synchronized
    fun add(
        report: ProblemReport,
        extraCount: Int = 0,
    ) {
        dir.mkdirs()
        val file = fileOf(report.fp)
        val previous = read(file)
        val merged =
            if (previous == null) {
                report.copy(count = report.count + extraCount)
            } else {
                previous.copy(
                    kind = worst(previous.kind, report.kind),
                    count = previous.count + report.count + extraCount,
                    lastMs = maxOf(previous.lastMs, report.lastMs),
                    // The latest build that met it is the one worth reading.
                    device = report.device,
                    stack = if (rank(report.kind) >= rank(previous.kind)) report.stack else previous.stack,
                )
            }
        write(file, merged)
        if (previous == null) trim()
    }

    /** Adds [count] occurrences to the waiting report of [fp], if it is still waiting. */
    @Synchronized
    fun addCount(
        fp: String,
        count: Int,
        lastMs: Long,
    ) {
        val file = fileOf(fp)
        val previous = read(file) ?: return
        write(file, previous.copy(count = previous.count + count, lastMs = maxOf(previous.lastMs, lastMs)))
    }

    /** The waiting reports, oldest first; expired ones are deleted on the way. */
    @Synchronized
    fun all(nowMs: Long = System.currentTimeMillis()): List<ProblemReport> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
        return files
            .mapNotNull { file ->
                val report = read(file)
                if (report == null || nowMs - report.lastMs > MAX_AGE_MS) {
                    delete(file)
                    null
                } else {
                    report
                }
            }.sortedBy { it.firstMs }
    }

    /** Forgets the report of [fp] once it is sent. */
    @Synchronized
    fun remove(fp: String) = delete(fileOf(fp))

    fun isEmpty(): Boolean = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.isNullOrEmpty()

    private fun fileOf(fp: String): File {
        require(FP.matches(fp)) { "bad fingerprint" }
        return File(dir, fp + SUFFIX)
    }

    private fun read(file: File): ProblemReport? {
        if (!file.isFile) return null
        return try {
            ProblemReport.fromJson(file.readText())
        } catch (e: SerializationException) {
            // A report the queue wrote itself and cannot read: a bug (or a format change). Dropped, and said.
            Problems.report("problems.queue.read", e)
            delete(file)
            null
        } catch (e: IllegalArgumentException) {
            Problems.report("problems.queue.read", e)
            delete(file)
            null
        } catch (e: IOException) {
            Problems.expected("problems.queue.read", e)
            null
        }
    }

    private fun write(
        file: File,
        report: ProblemReport,
    ) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(report.toJson())
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("could not write the report")
        }
    }

    private fun delete(file: File) {
        if (file.exists() && !file.delete()) {
            Problems.unexpected("problems.queue.delete", "a report file could not be deleted")
        }
    }

    /** Keeps the [MAX_FILES] most recent reports. */
    private fun trim() {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty()
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach(::delete)
    }

    private fun worst(
        a: ProblemKind,
        b: ProblemKind,
    ) = if (rank(b) > rank(a)) b else a

    private fun rank(kind: ProblemKind) =
        when (kind) {
            ProblemKind.ERROR -> 0
            ProblemKind.ANR -> 1
            ProblemKind.CRASH, ProblemKind.NATIVE_CRASH -> 2
        }

    companion object {
        const val MAX_FILES = 40
        const val MAX_AGE_MS = 30L * 24 * 3600 * 1000
        private const val SUFFIX = ".json"
        private val FP = Regex("""^[0-9a-f]{16}$""")
    }
}
