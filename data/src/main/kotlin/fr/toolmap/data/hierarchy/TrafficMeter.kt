package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.FrameGenre
import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.problem.catchExpected
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What the « Opération » transport costs on the network, for its end-to-end trial (P39.12, ADR-134,
 * `docs/specs/group-hierarchy.md` § 23): the coordination server's calls by kind, the bytes of their bodies up and
 * down (as they go on the wire, before any decompression; no header, no TLS), and the frames of the operation by
 * genre. It is an OkHttp network interceptor of the relay's own client, and a wrapper of the links the relay hands
 * over ([wrap]): nothing of the « Entre amis » relay changes. It keeps only counts: never an address, a path, a body
 * or a frame.
 */
class TrafficMeter : Interceptor {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    private fun add(
        key: String,
        n: Long = 1,
    ) {
        counts.computeIfAbsent(key) { AtomicLong() }.addAndGet(n)
    }

    /** Every count, by key: `call.<kind>`, `writes`, `up`, `down`, `frames.out.<genre>`, `bytes.in`… */
    fun snapshot(): Map<String, Long> = counts.mapValues { it.value.get() }.toSortedMap()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val kind = kindOf(request.method, request.url.host, request.url.encodedPath)
        add("call.$kind")
        if (request.method != "GET" && kind != "auth" && kind != "report") add("writes")
        val up = request.body?.contentLength()?.coerceAtLeast(0) ?: 0
        add("up", up)
        add("up.$kind", up)
        if (kind == "c" || kind == "p") written(kind, request.body)
        val response = chain.proceed(request)
        // A refusal of the server (rules, token) or any other answer than a success, by kind.
        if (response.code !in HTTP_OK) add("http.${response.code}.$kind")
        val body = response.body ?: return response
        val counted =
            CountingBody(body) { n ->
                add("down", n)
                add("down.$kind", n)
            }
        return response.newBuilder().body(counted).build()
    }

    /**
     * A frame written to the server ([kind] `c` or `p`): its genre, read in the body the relay sends (`{"f": Base64,
     * …}`), and how long it waited since the node handed it over (the relay writes one call at a time).
     */
    private fun written(
        kind: String,
        body: RequestBody?,
    ) {
        if (body == null || body.isOneShot()) return
        val text = Buffer().also { body.writeTo(it) }.readUtf8()
        val f = FRAME.find(text)?.groupValues?.get(1) ?: return
        val bytes =
            catchExpected("operation.e2e.meter", IllegalArgumentException::class) { Base64.getDecoder().decode(f) }
                .getOrNull() ?: return
        add("writes.$kind.${genreOf(bytes)}")
        val since = synchronized(waiting) { waiting.remove(f) } ?: return
        val ms = (System.nanoTime() - since) / NS_PER_MS
        add("queue.writes")
        add("queue.ms", ms)
        if (ms > SLOW_MS) add("queue.over1s")
    }

    /** The frames handed over by the node and not written yet, by their Base64 (as the relay writes them). */
    private val waiting =
        object : LinkedHashMap<String, Long>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > MAX_WAITING
        }

    /** The same factory, whose transports hand over links and frames that are counted on the way. */
    fun wrap(factory: RelayTransportFactory): RelayTransportFactory =
        RelayTransportFactory {
            config,
            groupId,
            listener,
            ->
            factory.create(config, groupId, CountingListener(listener))
        }

    private fun frame(
        direction: String,
        bytes: ByteArray,
    ) {
        add("frames.$direction")
        add("frames.$direction.${genreOf(bytes)}")
        add("bytes.$direction", bytes.size.toLong())
    }

    /** The links of one transport: the node sends one array to every link of the room, which the server writes once. */
    private inner class CountingListener(
        private val inner: LinkListener,
    ) : LinkListener {
        @Volatile
        private var lastOut: ByteArray? = null

        override fun onLinkOpen(
            peer: Long,
            name: String,
            color: Int,
            link: MeshLink,
        ) = inner.onLinkOpen(peer, name, color, CountingLink(link, this))

        override fun onLinkClosed(peer: Long) = inner.onLinkClosed(peer)

        override fun onFrame(
            peer: Long,
            bytes: ByteArray,
        ) {
            frame("in", bytes)
            inner.onFrame(peer, bytes)
        }

        override fun onKeptFrame(
            bytes: ByteArray,
            fresh: Boolean,
        ) = inner.onKeptFrame(bytes, fresh)

        fun sent(bytes: ByteArray) {
            if (bytes === lastOut) return
            lastOut = bytes
            frame("out", bytes)
            val key = Base64.getEncoder().encodeToString(bytes)
            synchronized(waiting) { waiting[key] = System.nanoTime() }
        }
    }

    private class CountingLink(
        private val inner: MeshLink,
        private val owner: CountingListener,
    ) : MeshLink {
        override val isBroadcastMedium: Boolean get() = inner.isBroadcastMedium

        override fun send(
            frame: ByteArray,
            onPositionChannel: Boolean,
        ): Boolean = inner.send(frame, onPositionChannel).also { if (it) owner.sent(frame) }

        override fun close() = inner.close()
    }

    /** Counts the bytes of a body as the relay reads them: the stream of the room too, event by event. */
    private class CountingBody(
        private val delegate: ResponseBody,
        private val counted: (Long) -> Unit,
    ) : ResponseBody() {
        private val source: BufferedSource by lazy {
            object : ForwardingSource(delegate.source()) {
                override fun read(
                    sink: Buffer,
                    byteCount: Long,
                ): Long = super.read(sink, byteCount).also { if (it > 0) counted(it) }
            }.buffer()
        }

        override fun contentType(): MediaType? = delegate.contentType()

        override fun contentLength(): Long = delegate.contentLength()

        override fun source(): BufferedSource = source
    }

    companion object {
        /**
         * The kind of a call to the coordination server, from its method and path (`docs/specs/firebase-relay.md`, § 3):
         * `h` heartbeat, `clock` its time read back, `p` position, `c` control frame, `c.clean` and `c.list` their
         * clean-up, `stream` the room's stream, `leave` the member's node deleted, `w` kept points, `auth` the
         * anonymous sign-in, `report` a problem report. Never the path itself: it holds the room.
         */
        internal fun kindOf(
            method: String,
            host: String,
            path: String,
        ): String {
            if ("identitytoolkit" in host || "securetoken" in host) return "auth"
            if (path.startsWith("/reports")) return "report"
            val parts = path.removeSuffix(".json").split('/').filter { it.isNotEmpty() }
            if (parts.size < ROOM_PARTS || parts[0] != "rooms") return "other"
            if (parts.size == ROOM_PARTS) return if (method == "GET") "stream" else "other"
            if (parts[ROOM_PARTS] == "w") return "w"
            if (parts.size == ROOM_PARTS + 1) return if (method == "DELETE") "leave" else "other"
            return when (parts[ROOM_PARTS + 1]) {
                "h" -> {
                    if (method == "GET") "clock" else "h"
                }

                "p" -> {
                    "p"
                }

                "c" -> {
                    when (method) {
                        "POST" -> "c"
                        "PATCH" -> "c.clean"
                        "GET" -> "c.list"
                        else -> "c.other"
                    }
                }

                else -> {
                    "other"
                }
            }
        }

        /** The genre of a frame of an operation, read in its header (in clear): `POSITION` for a position. */
        internal fun genreOf(bytes: ByteArray): String =
            when {
                bytes.size < 2 -> {
                    "short"
                }

                bytes[0].toInt() == OperationFrames.POSITION_MARKER -> {
                    "POSITION"
                }

                bytes[0].toInt() and BYTE == OperationFrames.MARKER -> {
                    FrameGenre.of(bytes[1].toInt())?.name
                        ?: "unknown"
                }

                else -> {
                    "foreign"
                }
            }

        /** `rooms`, the room, `b`. */
        private const val ROOM_PARTS = 3
        private const val BYTE = 0xFF
        private const val MAX_WAITING = 256
        private val HTTP_OK = 200..299
        private const val NS_PER_MS = 1_000_000L
        private const val SLOW_MS = 1_000L

        /** The frame in the body of a write of the relay: `"f":"<Base64>"`. */
        private val FRAME = Regex("\"f\":\"([A-Za-z0-9+/=]+)\"")
    }
}
