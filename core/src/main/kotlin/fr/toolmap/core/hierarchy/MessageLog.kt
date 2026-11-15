package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteWriter

/**
 * One message of a thread as this phone keeps it: who wrote it ([author]), the identifier its author drew ([id]: with
 * the author, it names the message on every phone), when it was sent and when this phone had it, and its text.
 */
data class LoggedMessage(
    val author: Long,
    val id: Long,
    val sentAtMs: Long,
    val receivedAtMs: Long,
    val text: String,
)

/**
 * The messages this phone keeps of one operation (P39.9, the user's answer R2 of 2026-11-15: « Oui, les 500 derniers
 * de chaque fil »): for each thread ([Channel]), the last [maxPerThread] messages, oldest first, its own included. The
 * caller seals it with the rest of the operation ([HierarchySnapshot.encodeMessages]) and wipes it with it; it never
 * goes into the trace of a dissolution ([DissolutionTrace]). Alerts are not kept here: they live in memory.
 *
 * A message already in its thread (same author, same identifier: a frame played again after a restart) is not added
 * twice. A text is kept as it travels, cut at [OperationPayloads.MAX_TEXT_BYTES] bytes of UTF-8: a restart reads back
 * exactly what was shown.
 */
class MessageLog(
    val ensembleId: Long,
    val maxPerThread: Int = MAX_PER_THREAD,
) {
    init {
        require(maxPerThread >= 1) { "a thread keeps at least one message" }
    }

    internal val threads = LinkedHashMap<Channel, ArrayDeque<LoggedMessage>>()

    /** Adds [message] at the end of the thread of [channel]; `false` when that thread already has it. */
    @Synchronized
    fun add(
        channel: Channel,
        message: LoggedMessage,
    ): Boolean {
        val thread = threads.getOrPut(channel) { ArrayDeque() }
        if (thread.any { it.author == message.author && it.id == message.id }) return false
        val cut = ByteWriter.truncateUtf8(message.text, OperationPayloads.MAX_TEXT_BYTES)
        thread.addLast(message.copy(text = String(cut, Charsets.UTF_8)))
        while (thread.size > maxPerThread) thread.removeFirst()
        return true
    }

    /** Forgets the threads of the channels not in [read] (this phone no longer reads them); `true` if it forgot one. */
    @Synchronized
    fun retain(read: Collection<Channel>): Boolean {
        val keep = read.toHashSet()
        return threads.keys.removeAll { it !in keep }
    }

    /** Every thread that has a message, its messages oldest first. */
    @Synchronized
    fun threads(): Map<Channel, List<LoggedMessage>> =
        threads.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() }

    /** The number of messages kept, every thread together. */
    val size: Int
        @Synchronized get() = threads.values.sumOf { it.size }

    @Synchronized
    fun clear() = threads.clear()

    companion object {
        /** The user's answer R2. */
        const val MAX_PER_THREAD = 500
    }
}
