package fr.toolmap.debugkit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** How far the collector got in the log of one member: every line up to [seq] of the log [epoch]. */
class Ack(
    val epoch: String,
    val seq: Long,
)

/** What the test tools send each other in [fr.toolmap.core.p2p.Msg.Diag] (`docs/specs/event-log.md` §4). */
sealed interface DiagPayload {
    /** Lines of the event log of the sender, the next ones after line [after] of its log [epoch]. */
    class Lines(
        val epoch: String,
        val after: Long,
        val lines: List<String>,
    ) : DiagPayload

    /**
     * The collector is there, and what it has of each member's log (members by hexadecimal id). [awakeS]: the phones
     * keep their screen on for that many seconds from now (0: no longer, P21.6).
     */
    class Acks(
        val acks: Map<String, Ack>,
        val awakeS: Long = 0,
    ) : DiagPayload
}

/**
 * A payload is JSON compressed with Deflate: `{"k":"log","e","f","l":[...]}` or `{"k":"col","a":{m:{"e","s"}},"w"}`
 * (`w` only while the screens are to stay on; older versions ignore it).
 */
object DiagCodec {
    /** A line longer than this is cut: one line must always fit in a payload. */
    const val MAX_LINE_CHARS = 2_000

    /** Inflated payloads larger than this are refused. */
    private const val MAX_INFLATED = 1 shl 20
    private const val BUFFER = 4096

    fun encode(payload: DiagPayload): ByteArray = deflate(json(payload).toString().toByteArray(Charsets.UTF_8))

    /** The payload of [body]; `null` when it is not one (damaged, from a newer version). */
    fun decode(body: ByteArray): DiagPayload? {
        val text = inflate(body)?.toString(Charsets.UTF_8) ?: return null
        return runCatching { parse(Json.parseToJsonElement(text).jsonObject) }.getOrNull()
    }

    /**
     * Packs the first of [lines] (which come after line [after]) into one payload of at most [maxBytes]; the payload
     * and how many lines it holds, at least one.
     */
    fun packLines(
        epoch: String,
        after: Long,
        lines: List<String>,
        maxBytes: Int,
    ): Pair<ByteArray, Int>? {
        if (lines.isEmpty()) return null
        val cut = lines.map { if (it.length > MAX_LINE_CHARS) it.take(MAX_LINE_CHARS) + "…" else it }
        var count = cut.size
        while (count > 0) {
            val body = encode(DiagPayload.Lines(epoch, after, cut.subList(0, count)))
            if (body.size <= maxBytes) return body to count
            // Compression is about even along a log: aim just under the limit, then step down.
            val aimed = (count.toLong() * maxBytes * AIM_PERCENT / (body.size.toLong() * PERCENT)).toInt()
            count = if (aimed in 1 until count) aimed else count - 1
        }
        return null
    }

    private const val AIM_PERCENT = 95
    private const val PERCENT = 100

    private fun json(payload: DiagPayload): JsonObject =
        when (payload) {
            is DiagPayload.Lines -> {
                buildJsonObject {
                    put("k", "log")
                    put("e", payload.epoch)
                    put("f", payload.after)
                    put("l", JsonArray(payload.lines.map(::JsonPrimitive)))
                }
            }

            is DiagPayload.Acks -> {
                buildJsonObject {
                    put("k", "col")
                    put(
                        "a",
                        JsonObject(
                            payload.acks.mapValues { (_, ack) ->
                                buildJsonObject {
                                    put("e", ack.epoch)
                                    put("s", ack.seq)
                                }
                            },
                        ),
                    )
                    if (payload.awakeS > 0) put("w", payload.awakeS)
                }
            }
        }

    private fun parse(o: JsonObject): DiagPayload? =
        when (o["k"]?.jsonPrimitive?.contentOrNull) {
            "log" -> {
                DiagPayload.Lines(
                    o.getValue("e").jsonPrimitive.content,
                    o.getValue("f").jsonPrimitive.longOrNull ?: return null,
                    (o["l"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull },
                )
            }

            "col" -> {
                DiagPayload.Acks(
                    (o["a"] as? JsonObject).orEmpty().mapNotNull { (member, value) ->
                        val ack = value as? JsonObject ?: return@mapNotNull null
                        val epoch = ack["e"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val seq = ack["s"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                        member to Ack(epoch, seq)
                    }.toMap(),
                    o["w"]?.jsonPrimitive?.longOrNull ?: 0,
                )
            }

            else -> {
                null
            }
        }

    private fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(bytes.size / 2 + BUFFER)
            val buffer = ByteArray(BUFFER)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflate(bytes: ByteArray): ByteArray? {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(BUFFER)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buffer, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            return null
        } finally {
            inflater.end()
        }
    }
}
