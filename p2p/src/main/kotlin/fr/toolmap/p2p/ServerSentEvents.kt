package fr.toolmap.p2p

import okio.BufferedSource

/** Reads a `text/event-stream` (what Firebase sends for a streamed read) and hands over each event. */
internal object ServerSentEvents {
    /** Calls [onEvent] with the event name and its data for every event, until the stream ends or throws. */
    fun read(
        source: BufferedSource,
        onEvent: (event: String, data: String) -> Unit,
    ) {
        var event = ""
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            when {
                line.isEmpty() -> {
                    if (event.isNotEmpty() || data.isNotEmpty()) onEvent(event.ifEmpty { "message" }, data.toString())
                    event = ""
                    data.setLength(0)
                }

                line.startsWith(":") -> {
                    Unit
                }

                line.startsWith("event:") -> {
                    event = line.substring("event:".length).trim()
                }

                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.substring("data:".length).removePrefix(" "))
                }
            }
        }
    }
}
