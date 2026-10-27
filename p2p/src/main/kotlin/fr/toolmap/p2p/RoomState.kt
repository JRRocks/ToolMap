package fr.toolmap.p2p

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * The mirror of the `b` node of a room, kept up to date from the `put` and `patch` events of the stream, and the
 * changes it turns into calls on a [Sink]: a member's heartbeat, a member's frame. Layout of a member `m`:
 * `h` = `{t, n, k}` (server time, name, colour), `p` = `{f, t}` (latest position frame), `c` = `{<id>: {f, t}}`
 * (control frames). `f` is the frame in Base64.
 */
internal class RoomState(
    private val sink: Sink,
) {
    interface Sink {
        fun heartbeat(
            member: String,
            serverMs: Long,
            name: String,
            color: Int,
        )

        fun frame(
            member: String,
            bytes: ByteArray,
            serverMs: Long,
            isPosition: Boolean,
        )

        /** The member removed its node: it left the room (or the room was emptied). */
        fun gone(member: String)
    }

    /** A node is a map of children or a leaf value. */
    private var root: MutableMap<String, Any> = LinkedHashMap()
    private val lastBeat = HashMap<String, Long>()
    private val lastPos = HashMap<String, String>()
    private val seenControl = HashMap<String, MutableSet<String>>()

    /** `put`: replaces what is at [path] ("/" is the whole room) by [data]. */
    fun put(
        path: String,
        data: JsonElement,
    ) {
        val segments = segments(path)
        set(segments, convert(data))
        emit(affected(segments))
    }

    /** `patch`: sets each child of [data] under [path]; a `null` child deletes it. */
    fun patch(
        path: String,
        data: JsonObject,
    ) {
        val segments = segments(path)
        for ((k, v) in data) set(segments + segments(k), convert(v))
        emit(affected(segments))
    }

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }

    private fun affected(segments: List<String>): Collection<String> =
        if (segments.isEmpty()) root.keys.toList() else listOf(segments[0])

    private fun convert(e: JsonElement): Any? =
        when (e) {
            is JsonNull -> {
                null
            }

            is JsonObject -> {
                LinkedHashMap<String, Any>().also { m ->
                    for ((k, v) in e) convert(v)?.let { m[k] = it }
                }.takeIf { it.isNotEmpty() }
            }

            is JsonPrimitive -> {
                e
            }

            else -> {
                null
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun set(
        segments: List<String>,
        value: Any?,
    ) {
        if (segments.isEmpty()) {
            root = (value as? MutableMap<String, Any>) ?: LinkedHashMap()
            return
        }
        // nodes[i] is the map that holds segments[i].
        val nodes = ArrayList<MutableMap<String, Any>>()
        var node = root
        nodes += node
        for (s in segments.dropLast(1)) {
            val next = node[s] as? MutableMap<String, Any>
            if (next == null) {
                if (value == null) return
                node = LinkedHashMap<String, Any>().also { node[s] = it }
            } else {
                node = next
            }
            nodes += node
        }
        if (value == null) node.remove(segments.last()) else node[segments.last()] = value
        // An empty node does not exist in the database: prune it.
        var depth = nodes.size - 1
        while (depth > 0 && nodes[depth].isEmpty()) {
            nodes[depth - 1].remove(segments[depth - 1])
            depth--
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun emit(members: Collection<String>) {
        val frames = ArrayList<() -> Unit>()
        for (member in members) {
            val m = root[member] as? Map<String, Any>
            if (m == null) {
                if (lastBeat.remove(member) != null) sink.gone(member)
                lastPos.remove(member)
                seenControl.remove(member)
                continue
            }
            if (m["h"] == null && lastBeat.remove(member) != null) sink.gone(member)
            (m["h"] as? Map<String, Any>)?.let { h ->
                val t = (h["t"] as? JsonPrimitive)?.longOrNull
                if (t != null && lastBeat[member] != t) {
                    lastBeat[member] = t
                    val name = (h["n"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                    val color = (h["k"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0
                    sink.heartbeat(member, t, name, color)
                }
            }
            (m["p"] as? Map<String, Any>)?.let { p ->
                val f = (p["f"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                val t = (p["t"] as? JsonPrimitive)?.longOrNull
                if (f != null && t != null && lastPos[member] != "$t:$f") {
                    lastPos[member] = "$t:$f"
                    decode(f)?.let { bytes -> frames += { sink.frame(member, bytes, t, true) } }
                }
            }
            val control = m["c"] as? Map<String, Any>
            val seen = seenControl.getOrPut(member) { HashSet() }
            seen.retainAll(control?.keys.orEmpty())
            control?.forEach { (id, entry) ->
                if (!seen.add(id)) return@forEach
                val e = entry as? Map<String, Any> ?: return@forEach
                val f = (e["f"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                val t = (e["t"] as? JsonPrimitive)?.longOrNull ?: return@forEach
                decode(f)?.let { bytes -> frames += { sink.frame(member, bytes, t, false) } }
            }
        }
        frames.forEach { it() }
    }

    private fun decode(f: String): ByteArray? = runCatching { Base64.getDecoder().decode(f) }.getOrNull()
}
