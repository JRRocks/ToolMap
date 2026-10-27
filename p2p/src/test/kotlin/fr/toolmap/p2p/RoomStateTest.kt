package fr.toolmap.p2p

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okio.Buffer
import org.junit.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomStateTest {
    private val events = ArrayList<String>()
    private val sink =
        object : RoomState.Sink {
            override fun heartbeat(
                member: String,
                serverMs: Long,
                name: String,
                color: Int,
            ) {
                events += "beat $member $serverMs $name $color"
            }

            override fun frame(
                member: String,
                bytes: ByteArray,
                serverMs: Long,
                isPosition: Boolean,
            ) {
                events += "${if (isPosition) "pos" else "ctrl"} $member ${String(bytes)} $serverMs"
            }

            override fun gone(member: String) {
                events += "gone $member"
            }
        }
    private val state = RoomState(sink)

    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())

    private fun json(s: String) = Json.parseToJsonElement(s)

    @Test
    fun `the first snapshot gives heartbeats before frames`() {
        state.put(
            "/",
            json(
                """{"m1":{"h":{"t":100,"n":"Ann","k":7},"p":{"f":"${b64("P")}","t":101},
                   "c":{"-a":{"f":"${b64("A")}","t":102},"-b":{"f":"${b64("B")}","t":103}}},
                   "m2":{"h":{"t":200,"n":"Bob","k":8}}}""",
            ),
        )
        assertEquals(
            listOf("beat m1 100 Ann 7", "beat m2 200 Bob 8", "pos m1 P 101", "ctrl m1 A 102", "ctrl m1 B 103"),
            events,
        )
    }

    @Test
    fun `a pushed child arrives once and a replayed snapshot does not repeat it`() {
        state.put("/m1/c/-a", json("""{"f":"${b64("A")}","t":5}"""))
        state.put("/m1/c/-a", json("""{"f":"${b64("A")}","t":5}"""))
        assertEquals(listOf("ctrl m1 A 5"), events)
    }

    @Test
    fun `a position overwritten in place is delivered each time it changes`() {
        state.put("/m1/p", json("""{"f":"${b64("1")}","t":1}"""))
        state.put("/m1/p", json("""{"f":"${b64("2")}","t":2}"""))
        state.put("/m1/p", json("""{"f":"${b64("2")}","t":2}"""))
        assertEquals(listOf("pos m1 1 1", "pos m1 2 2"), events)
    }

    @Test
    fun `a heartbeat update is seen and a patch sets several children`() {
        state.put("/m1/h", json("""{"t":10,"n":"Ann","k":1}"""))
        state.patch("/m1/h", Json.parseToJsonElement("""{"t":25}""").jsonObject)
        assertEquals(listOf("beat m1 10 Ann 1", "beat m1 25 Ann 1"), events)
    }

    @Test
    fun `removing a member node or its heartbeat says it is gone`() {
        state.put("/m1/h", json("""{"t":10,"n":"Ann","k":1}"""))
        state.put("/m1", json("null"))
        state.put("/m2/h", json("""{"t":10,"n":"Bob","k":1}"""))
        state.put("/m2/h", json("null"))
        assertEquals(listOf("beat m1 10 Ann 1", "gone m1", "beat m2 10 Bob 1", "gone m2"), events)
    }

    @Test
    fun `a frame that is not Base64 is ignored`() {
        state.put("/m1/c/-a", json("""{"f":"@@@","t":5}"""))
        assertTrue(events.isEmpty())
    }

    @Test
    fun `server sent events are split into events`() {
        val text =
            ": comment\nevent: put\ndata: {\"path\":\"/\",\"data\":null}\n\n" +
                "event: keep-alive\ndata: null\n\nevent: patch\ndata: a\ndata: b\n\n"
        val out = ArrayList<Pair<String, String>>()
        ServerSentEvents.read(Buffer().writeUtf8(text)) { e, d -> out += e to d }
        assertEquals(
            listOf("put" to "{\"path\":\"/\",\"data\":null}", "keep-alive" to "null", "patch" to "a\nb"),
            out,
        )
    }
}
