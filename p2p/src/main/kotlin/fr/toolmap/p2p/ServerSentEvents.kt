package fr.toolmap.p2p

import okio.BufferedSource
import java.io.IOException

/**
 * One event of the stream is larger than the reader accepts: it is not read. The whole room arrives as one event when
 * the stream opens, and whoever holds the room id can fill a room (P36 review: 300 MB in one event, and every member's
 * phone ran out of memory at each reconnection).
 */
internal class EventTooLarge(
    message: String,
) : IOException(message)

/** Reads a `text/event-stream` (what Firebase sends for a streamed read) and hands over each event. */
internal object ServerSentEvents {
    /**
     * The largest event read, in bytes: several hundred tracks shared with a group at their largest (16 KB each once
     * sealed and written in Base64), and what a phone can hold and parse without trouble.
     */
    const val MAX_EVENT_BYTES = 8L * 1024 * 1024

    /**
     * Calls [onEvent] with the event name and its data for every event, until the stream ends or throws.
     * @throws EventTooLarge when a line or an event is longer than [maxEventBytes]
     */
    fun read(
        source: BufferedSource,
        maxEventBytes: Long = MAX_EVENT_BYTES,
        onEvent: (event: String, data: String) -> Unit,
    ) {
        var event = ""
        // Firebase writes the data of an event on one line: it is handed over as it was read, not copied.
        val data = ArrayList<String>(1)
        var size = 0L
        while (true) {
            val line = line(source, maxEventBytes) ?: break
            when {
                line.isEmpty() -> {
                    if (event.isNotEmpty() || data.isNotEmpty()) {
                        onEvent(event.ifEmpty { "message" }, data.singleOrNull() ?: data.joinToString("\n"))
                    }
                    event = ""
                    data.clear()
                    size = 0L
                }

                line.startsWith(":") -> {
                    Unit
                }

                line.startsWith("event:") -> {
                    event = line.substring("event:".length).trim()
                }

                line.startsWith("data:") -> {
                    size += line.length
                    if (size > maxEventBytes) throw EventTooLarge("event larger than $maxEventBytes bytes")
                    data += line.substring("data:".length).removePrefix(" ")
                }
            }
        }
    }

    /** The next line, `null` at the end of the stream; a line longer than [limit] bytes is not buffered further. */
    private fun line(
        source: BufferedSource,
        limit: Long,
    ): String? {
        val newline = source.indexOf('\n'.code.toByte(), 0, limit + 1)
        // No end of line in the first bytes, and they are all there: the line is longer than the limit.
        if (newline < 0 && source.request(limit + 1)) throw EventTooLarge("line longer than $limit bytes")
        return source.readUtf8Line()
    }
}
