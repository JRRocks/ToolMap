package fr.toolmap.core.eventlog

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Where the event log is kept on the phone (P21): `current.log` and the file before it, `previous.log`, of at most
 * [maxFileBytes] each, so the log never takes more than twice that. Every line has a sequence number that goes on
 * across launches; [epoch] names this log and changes only when its folder is emptied (app data cleared), so that a
 * reader never mixes two logs with the same numbers.
 *
 * Lines are written to disk by a background thread (logging never waits for the disk); the last [memoryLines] lines
 * also stay in memory for [linesAfter] (the debug build sends them to the test collector).
 */
class EventStore(
    private val dir: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val memoryLines: Int = DEFAULT_MEMORY_LINES,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Every line is also given here as it is made (logcat in the debug build). */
    private val mirror: ((String) -> Unit)? = null,
) : EventSink,
    Closeable {
    private val current = File(dir, CURRENT)
    private val previous = File(dir, PREVIOUS)
    private val lock = Any()
    private val ring = ArrayDeque<Pair<Long, String>>()
    private val queue = LinkedBlockingQueue<Any>()
    private var out: BufferedWriter? = null
    private var written = 0L

    val epoch: String

    /** The number of the last line made (written or about to be). */
    @Volatile
    var lastSeq: Long = 0L
        private set

    private val writer: Thread

    init {
        dir.mkdirs()
        epoch = readOrMakeEpoch()
        lastSeq = lastSeqOnDisk()
        writer = Thread(::drain, "toolmap-eventlog").apply { isDaemon = true }
        writer.start()
    }

    override fun write(event: Event) {
        val line: String
        synchronized(lock) {
            val seq = ++lastSeq
            line = EventLine.format(seq, event, zone)
            ring.addLast(seq to line)
            while (ring.size > memoryLines) ring.removeFirst()
        }
        queue.offer(line)
        mirror?.invoke(line)
    }

    /**
     * The lines after number [seq], oldest first, at most [maxBytes] of text but one line at least: from memory when it
     * still has them, else from the files (older lines; ask again for the next ones).
     */
    fun linesAfter(
        seq: Long,
        maxBytes: Int,
    ): List<String> {
        val inMemory =
            synchronized(lock) {
                val first = ring.firstOrNull()?.first
                if (first != null &&
                    first <= seq + 1
                ) {
                    take(ring.asSequence().filter { it.first > seq }, maxBytes)
                } else {
                    null
                }
            }
        if (inMemory != null) return inMemory
        val firstInMemory = synchronized(lock) { ring.firstOrNull()?.first ?: Long.MAX_VALUE }
        val fromFiles = ArrayList<Pair<Long, String>>()
        var bytes = 0
        for (file in listOf(previous, current)) {
            if (!file.isFile || bytes >= maxBytes) continue
            try {
                file.useLines(Charsets.UTF_8) { lines ->
                    for (line in lines) {
                        val n = EventLine.seqOf(line) ?: continue
                        // Lines still in memory come from memory: the file may hold the last one half written.
                        if (n <= seq || n >= firstInMemory) continue
                        if (bytes > 0 && bytes + line.length + 1 > maxBytes) break
                        fromFiles += n to line
                        bytes += line.length + 1
                    }
                }
            } catch (e: IOException) {
                // A file being rotated: what was read is still good.
            }
        }
        if (fromFiles.isNotEmpty()) return fromFiles.map { it.second }
        return synchronized(lock) { take(ring.asSequence().filter { it.first > seq }, maxBytes) }
    }

    /** Waits until every line made so far is on disk (tests, and before reading the files). */
    fun flush(timeoutMs: Long = FLUSH_WAIT_MS) {
        val done = CountDownLatch(1)
        queue.offer(done)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        flush()
        queue.offer(STOP)
        writer.join(FLUSH_WAIT_MS)
    }

    private fun take(
        lines: Sequence<Pair<Long, String>>,
        maxBytes: Int,
    ): List<String> {
        val result = ArrayList<String>()
        var bytes = 0
        for ((_, line) in lines) {
            if (result.isNotEmpty() && bytes + line.length + 1 > maxBytes) break
            result += line
            bytes += line.length + 1
        }
        return result
    }

    private fun drain() {
        while (true) {
            val first = queue.take()
            if (first === STOP) break
            val batch = ArrayList<Any>()
            batch += first
            queue.drainTo(batch)
            val latches = ArrayList<CountDownLatch>()
            var stop = false
            for (item in batch) {
                when (item) {
                    is String -> append(item)
                    is CountDownLatch -> latches += item
                    STOP -> stop = true
                }
            }
            runCatching { out?.flush() }
            latches.forEach { it.countDown() }
            if (stop) break
        }
        runCatching { out?.close() }
        out = null
    }

    private fun append(line: String) {
        try {
            val bytes = line.length + 1L
            if (out == null) open()
            if (written > 0 && written + bytes > maxFileBytes) rotate()
            out?.apply {
                write(line)
                write("\n")
            }
            written += bytes
        } catch (e: IOException) {
            // Disk full or folder gone: the line stays in memory only.
            runCatching { out?.close() }
            out = null
        }
    }

    private fun open() {
        dir.mkdirs()
        out = BufferedWriter(OutputStreamWriter(FileOutputStream(current, true), Charsets.UTF_8), BUFFER_BYTES)
        written = current.length()
    }

    private fun rotate() {
        runCatching { out?.close() }
        out = null
        previous.delete()
        if (!current.renameTo(previous)) current.delete()
        open()
    }

    private fun readOrMakeEpoch(): String {
        val file = File(dir, EPOCH)
        val known = runCatching { file.readText().trim() }.getOrNull()
        if (!known.isNullOrEmpty()) return known
        val bytes = ByteArray(EPOCH_BYTES).also { SecureRandom().nextBytes(it) }
        val made = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        runCatching { file.writeText(made) }
        return made
    }

    /** The number of the last complete line of the newest file that has one. */
    private fun lastSeqOnDisk(): Long {
        for (file in listOf(current, previous)) {
            if (!file.isFile || file.length() == 0L) continue
            val tail =
                runCatching {
                    RandomAccessFile(file, "r").use { raf ->
                        val size = raf.length()
                        val from = (size - TAIL_BYTES).coerceAtLeast(0)
                        val buffer = ByteArray((size - from).toInt())
                        raf.seek(from)
                        raf.readFully(buffer)
                        String(buffer, Charsets.UTF_8)
                    }
                }.getOrNull() ?: continue
            // Only lines followed by a line break are complete.
            val complete = tail.substringBeforeLast('\n', "")
            val last = complete.split('\n').asReversed().firstNotNullOfOrNull { EventLine.seqOf(it) }
            if (last != null) return last
        }
        return 0L
    }

    companion object {
        const val CURRENT = "current.log"
        const val PREVIOUS = "previous.log"
        const val EPOCH = "epoch"
        const val DEFAULT_MAX_FILE_BYTES = 1L shl 20
        const val DEFAULT_MEMORY_LINES = 256
        private const val EPOCH_BYTES = 4
        private const val TAIL_BYTES = 16 * 1024L
        private const val BUFFER_BYTES = 8 * 1024
        private const val FLUSH_WAIT_MS = 5_000L
        private val STOP = Any()
    }
}
