package fr.toolmap.p2p

import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.MsgType
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.p2p.Wire
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The server side of the sharing (ADR-033): a room of a Firebase Realtime Database used as a bus. Every member writes
 * its frames under its own node of `rooms/<room>/b` and streams the whole `b` node, so one write reaches the whole
 * group. A member is present while its heartbeat (server time, every [HEARTBEAT_S] s) is recent; each present member is
 * handed to the group logic as a [MeshLink]. Frames are the same sealed frames as in the direct mode: the server only
 * ever sees ciphertext.
 *
 * Threads: a stream thread (reads), a one-thread executor for writes (kept in order), a timer (heartbeat, presence,
 * clean-up). The listener may be called from the stream thread and from the timer.
 */
class FirebaseRelay(
    private val config: RelayConfig,
    groupId: ByteArray,
    private val listener: LinkListener,
    authStore: AuthStore,
    private val http: OkHttpClient = OkHttpClient(),
    endpoints: FirebaseEndpoints = FirebaseEndpoints(),
    private val clockMs: () -> Long = System::currentTimeMillis,
) : RelayTransport {
    /**
     * The relay's connections, in a pool of their own: a connection opened on a network keeps using it until Android
     * tears it down (30 s after Wi-Fi comes back), so a new default network gets a new pool (P21.5 f).
     */
    private class Clients(
        http: OkHttpClient,
    ) {
        val pool = ConnectionPool()
        val write: OkHttpClient =
            http.newBuilder().connectionPool(pool).callTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS).build()
        val stream: OkHttpClient =
            http.newBuilder().connectionPool(pool).readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS).build()
    }

    private class Peer(
        val id: Long,
    ) {
        var name = ""
        var color = 0
        var lastBeat = 0L
        var droppedAt = 0L
        var banned = false
        var link: RelayLink? = null
    }

    @Volatile
    override var identity: TransportIdentity? = null

    @Volatile
    override var onState: ((ServerState) -> Unit)? = null

    @Volatile
    override var state: ServerState = ServerState.CONNECTING
        private set

    override val pendingWrites: Int get() = queuedWrites.get()

    private val room = RelayPaths.room(groupId)

    @Volatile
    private var clients = Clients(http)

    /** The pool left with the previous network: emptied once its calls are over (P21.5 f). */
    @Volatile
    private var retiredPool: ConnectionPool? = null
    private val auth = FirebaseAuth(config, { clients.write }, authStore, endpoints, clockMs)
    private val db = FirebaseDb((endpoints.dbBase ?: config.base).trimEnd('/'), auth) { clients.write }
    private val writer =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "toolmap-relay-io").apply { isDaemon = true }
        }
    private val timer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "toolmap-relay-timer").apply { isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private val lock = Any()
    private val peers = HashMap<Long, Peer>()
    private val ownKeys = ArrayDeque<Pair<String, Long>>()
    private var streamThread: Thread? = null

    @Volatile
    private var streamResponse: Response? = null

    @Volatile
    private var streamUp = false

    @Volatile
    private var streamOpenedAt = 0L

    @Volatile
    private var streamCall: Call? = null

    @Volatile
    private var writesOk = true

    @Volatile
    private var refused = false

    @Volatile
    private var offsetMs: Long? = null

    @Volatile
    private var pendingPosition: ByteArray? = null

    /** The position frame being written now: replacing it loses nothing (P21.5 b). */
    @Volatile
    private var writingPosition: ByteArray? = null

    /** The token the open stream was opened with (P21.5 g). */
    @Volatile
    private var streamToken: String? = null

    /** The default network as the app last told it, and the one the open stream was opened on (P21.5 f). */
    @Volatile
    private var network: String? = null

    @Volatile
    private var streamNetwork: String? = null

    /** Android keeps the app off the network (P21.5 h); written under [wakeLock]. */
    @Volatile
    private var blocked = false

    /** No network at all (P21.5 p); written under [wakeLock]. */
    @Volatile
    private var offline = false

    /** When the last heartbeat was due, on the wall clock (P21.5 q). */
    @Volatile
    private var lastBeatAt = 0L

    /** The frames a previous process left on the server are still to be deleted (P21.5 r). */
    private val purgeDue = AtomicBoolean(true)

    /** The stream is being closed on purpose to take a renewed token: it reopens at once, the state stays. */
    private val reopening = AtomicBoolean(false)

    /** A position write or a heartbeat already waits in the writer queue: the next one only updates what it sends. */
    private val positionQueued = AtomicBoolean(false)
    private val heartbeatQueued = AtomicBoolean(false)

    /** Wakes the connection loop out of its pause between two attempts ([reconnectNow]). */
    private val wakeLock = ReentrantLock()
    private val wake = wakeLock.newCondition()
    private var woken = false
    private var lastCtrlFrame: ByteArray? = null
    private var lastPosFrame: ByteArray? = null

    /** Counters read by the event log's gauge ([EventLog.gauge], P21). */
    private val queuedWrites = AtomicInteger(0)
    private val writes = AtomicLong(0)
    private val writeFails = AtomicLong(0)
    private val bytesUp = AtomicLong(0)
    private val framesIn = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private val positionsReplaced = AtomicLong(0)
    private var gauge: AutoCloseable? = null

    private val sink =
        object : RoomState.Sink {
            override fun heartbeat(
                member: String,
                serverMs: Long,
                name: String,
                color: Int,
            ) {
                val id = RelayPaths.memberOf(member) ?: return
                if (id == identity?.memberId) return
                EventLog.detail("relay.hb.in", "peer" to MeshNode.hex(id), "ageMs" to serverNow() - serverMs)
                synchronized(lock) {
                    val p = peers.getOrPut(id) { Peer(id) }
                    p.lastBeat = serverMs
                    if (name.isNotEmpty()) p.name = name
                    p.color = color
                }
                evaluate()
            }

            override fun gone(member: String) {
                val id = RelayPaths.memberOf(member) ?: return
                EventLog.event("relay.peer.gone", "peer" to MeshNode.hex(id))
                val closing =
                    synchronized(lock) {
                        peers[id]?.let {
                            it.droppedAt = serverNow()
                            it.link?.closed = true
                            val had = it.link != null
                            it.link = null
                            had
                        }
                    }
                if (closing == true) listener.onLinkClosed(id)
            }

            override fun frame(
                member: String,
                bytes: ByteArray,
                serverMs: Long,
                isPosition: Boolean,
            ) {
                val id = RelayPaths.memberOf(member) ?: return
                if (id == identity?.memberId) return
                val present = synchronized(lock) { peers[id]?.link != null }
                val ageMs = serverNow() - serverMs
                val drop =
                    when {
                        !present -> "nolink"
                        !isPosition && ageMs > CONTROL_MAX_AGE_MS -> "old"
                        else -> null
                    }
                if (drop != null) {
                    framesDropped.incrementAndGet()
                    EventLog.event(
                        "relay.frame.drop",
                        "peer" to MeshNode.hex(id),
                        "why" to drop,
                        "pos" to isPosition,
                        "type" to Wire.typeOf(bytes),
                        "fid" to Wire.frameId(bytes),
                        "ageMs" to ageMs,
                    )
                    return
                }
                framesIn.incrementAndGet()
                EventLog.detail(
                    "relay.frame.in",
                    "peer" to MeshNode.hex(id),
                    "pos" to isPosition,
                    "type" to Wire.typeOf(bytes),
                    "fid" to Wire.frameId(bytes),
                    "ageMs" to ageMs,
                    "bytes" to bytes.size,
                )
                listener.onFrame(id, bytes)
            }
        }

    // ---- life -------------------------------------------------------------------------------------------------

    override fun start() {
        if (streamThread != null || closed.get()) return
        EventLog.event(
            "relay.start",
            "room" to room.take(ROOM_PREFIX),
            "me" to identity?.memberId?.let { MeshNode.hex(it) },
        )
        gauge = EventLog.gauge("relay", ::gaugeValues)
        streamThread = Thread(::connectLoop, "toolmap-relay-stream").apply { isDaemon = true }.also { it.start() }
        lastBeatAt = clockMs()
        timer.scheduleWithFixedDelay({ safely(::beatIfDue) }, 1, 1, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ safely(::evaluate) }, PRESENCE_EVERY_S, PRESENCE_EVERY_S, TimeUnit.SECONDS)
        timer.scheduleWithFixedDelay({ safely(::cleanUp) }, CLEANUP_S, CLEANUP_S, TimeUnit.SECONDS)
    }

    override fun ban(peer: Long) {
        EventLog.event("relay.ban", "peer" to MeshNode.hex(peer))
        val link =
            synchronized(lock) {
                peers.getOrPut(peer) { Peer(peer) }.also { it.banned = true }.link.also { peers[peer]?.link = null }
            }
        link?.closed = true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        EventLog.event(
            "relay.close",
            "writes" to writes.get(),
            "fails" to writeFails.get(),
            "queued" to queuedWrites.get(),
        )
        gauge?.close()
        timer.shutdownNow()
        synchronized(lock) { peers.values.forEach { it.link?.closed = true } }
        // Closing the TLS stream and the final delete touch the network: never on the caller's (main) thread.
        val response = streamResponse
        val stream = streamThread
        val me = identity?.memberId
        Thread({
            runCatching { response?.close() }
            stream?.interrupt()
            writer.execute { if (me != null) runCatching { db.delete("rooms/$room/b/${RelayPaths.member(me)}") } }
            writer.shutdown()
            runCatching { writer.awaitTermination(CLOSE_WAIT_S, TimeUnit.SECONDS) }
            // The pools are the relay's own: nothing else will use these connections.
            clients.pool.evictAll()
            retiredPool?.evictAll()
        }, "toolmap-relay-close").apply { isDaemon = true }.start()
    }

    // ---- MeshTransport: links appear by themselves, nothing to negotiate -------------------------------------------

    override fun offer(
        peer: Long,
        done: (PairingPayload?) -> Unit,
    ) = done(null)

    override fun answer(
        offer: PairingPayload,
        done: (PairingPayload?) -> Unit,
    ) = done(null)

    override fun accept(answer: PairingPayload) = Unit

    override fun drop(peer: Long) {
        synchronized(lock) {
            peers[peer]?.let {
                it.link?.closed = true
                it.link = null
                it.droppedAt = serverNow()
            }
        }
    }

    // ---- connection ---------------------------------------------------------------------------------------------

    private fun connectLoop() {
        var backoffS = 1L
        var attempt = 0
        while (!closed.get()) {
            try {
                waitWhileOff()
            } catch (e: InterruptedException) {
                return
            }
            if (closed.get()) return
            val startedAt = clockMs()
            attempt++
            EventLog.event("relay.connect", "attempt" to attempt)
            var ended: Exception? = null
            var planned = false
            reopening.set(false)
            try {
                syncClock()
                refused = false
                writesOk = true
                if (purgeDue.compareAndSet(true, false)) enqueue(work = ::purgeOldFrames)
                openStream()
            } catch (e: FirebaseRefused) {
                if (closed.get()) return
                ended = e
                auth.invalidate()
                refused = true
            } catch (e: IOException) {
                if (closed.get()) return
                ended = e
                // Closed on purpose to take the renewed token: not a failure.
                planned = reopening.getAndSet(false)
                if (!planned) writesOk = false
            } catch (e: InterruptedException) {
                return
            } catch (e: RuntimeException) {
                if (closed.get()) return
                ended = e
                writesOk = false
            }
            EventLog.event(
                "relay.stream.end",
                "upS" to (clockMs() - startedAt) / MS_PER_S,
                "error" to ended?.javaClass?.simpleName,
                "why" to ended?.message,
            )
            streamUp = false
            // A planned reopen takes a fraction of a second: the state does not flicker to "unreachable".
            if (!planned) publishState()
            if (clockMs() - startedAt > STABLE_MS) backoffS = 1L
            val kicked =
                try {
                    pause(backoffS * MS_PER_S)
                } catch (e: InterruptedException) {
                    return
                }
            EventLog.event("relay.retry", "waitedS" to backoffS, "kicked" to kicked)
            backoffS = if (kicked) 1L else (backoffS * 2).coerceAtMost(MAX_BACKOFF_S)
        }
    }

    /** Waits before the next attempt; returns early (true) when [reconnectNow] is called meanwhile. */
    private fun pause(millis: Long): Boolean {
        wakeLock.withLock {
            var left = millis * NS_PER_MS
            while (!woken && left > 0) left = wake.awaitNanos(left)
            return woken.also { woken = false }
        }
    }

    /**
     * Waits while Android keeps the app off the network (P21.5 h) or while there is no network at all (P21.5 p): every
     * attempt would fail. Without a network the wait is capped, in case the call telling its return is missed.
     */
    private fun waitWhileOff() {
        wakeLock.withLock {
            if (blocked) {
                EventLog.event("relay.blocked.wait")
                while (blocked && !closed.get()) wake.await()
                woken = false
            }
            if (offline && !closed.get()) {
                EventLog.event("relay.offline.wait")
                var left = OFFLINE_MAX_WAIT_MS * NS_PER_MS
                while (offline && !closed.get() && left > 0) left = wake.awaitNanos(left)
                woken = false
            }
        }
    }

    private fun wakeUp() {
        wakeLock.withLock {
            woken = true
            wake.signalAll()
        }
    }

    override fun reconnectNow() {
        if (closed.get()) return
        wakeUp()
        // A stream opened on the network that went away may hang until its read timeout: cancel it now (thread-safe,
        // nothing written to the socket); the loop then reconnects on the new network. A stream opened in the last
        // seconds is kept, unless it was opened on another network than the one now in use (P21.5 f).
        val call = streamCall
        val fresh = clockMs() - streamOpenedAt < FRESH_STREAM_MS
        val moved = network != null && streamNetwork != network
        val cancel = call != null && (!fresh || moved)
        EventLog.event("relay.reconnect.now", "cancel" to cancel, "moved" to moved)
        if (cancel) call?.cancel()
    }

    override fun onNetwork(network: String) {
        if (offline) {
            EventLog.event("relay.offline", "offline" to false)
            wakeLock.withLock {
                offline = false
                woken = true
                wake.signalAll()
            }
        }
        val previous = this.network
        this.network = network
        if (previous == null || previous == network || closed.get()) return
        // Calls from now on open new connections on the new network; the old idle ones are closed now, the busy ones
        // (the stream, a write in flight) when they end.
        val old = clients
        clients = Clients(http)
        retiredPool = old.pool
        old.pool.evictAll()
        EventLog.event("relay.network", "pool" to "new")
    }

    override fun onBlocked(blocked: Boolean) {
        if (closed.get() || blocked == this.blocked) return
        EventLog.event("relay.blocked", "blocked" to blocked, "stream" to streamUp)
        wakeLock.withLock {
            this.blocked = blocked
            if (!blocked) {
                woken = true
                wake.signalAll()
            }
        }
        // Let back on the network with the stream still open: a position held meanwhile goes now.
        if (!blocked && streamUp && pendingPosition != null) ownNode()?.let(::queuePosition)
    }

    override fun onOffline() {
        if (closed.get() || offline) return
        EventLog.event("relay.offline", "offline" to true, "stream" to streamUp)
        wakeLock.withLock { offline = true }
    }

    override fun onClockChanged() {
        if (closed.get() || offsetMs == null) return
        EventLog.event("relay.clock.changed")
        enqueue(work = ::syncClock)
    }

    private fun ownNode(): String? = identity?.memberId?.let { "rooms/$room/b/${RelayPaths.member(it)}" }

    /**
     * Writes the heartbeat and reads its server time back: the difference with the phone clock is the offset. A
     * measure whose round trip is negative or too long (the clock was set meanwhile) is made again (P21.5 n).
     */
    private fun syncClock() {
        val me = RelayPaths.member(identity?.memberId ?: error("identity not set"))
        for (attempt in 1..CLOCK_TRIES) {
            val t0 = clockMs()
            db.put("rooms/$room/b/$me/h", heartbeatJson())
            val server = db.get("rooms/$room/b/$me/h/t").trim().toLongOrNull() ?: throw IOException("no server time")
            val t1 = clockMs()
            val rtt = t1 - t0
            // The last try is kept when there is no offset yet: the presence needs one.
            if (rtt in 0..MAX_CLOCK_RTT_MS || (attempt == CLOCK_TRIES && offsetMs == null)) {
                offsetMs = server - (t0 + t1) / 2
                EventLog.event("relay.clock", "offsetMs" to offsetMs, "rttMs" to rtt)
                return
            }
            EventLog.event("relay.clock.bad", "rttMs" to rtt, "try" to attempt)
        }
    }

    private fun openStream() {
        streamOpenedAt = clockMs()
        streamNetwork = network
        // The stream that used the previous network's pool has ended: nothing there is in use any more.
        retiredPool?.evictAll()
        retiredPool = null
        val token = auth.token()
        val response = db.stream("rooms/$room/b", clients.stream, token) { streamCall = it }
        streamResponse = response
        response.use { r ->
            EventLog.event("relay.stream.open", "ms" to clockMs() - streamOpenedAt, "proto" to r.protocol.toString())
            streamToken = token
            streamUp = true
            publishState()
            // A position held during the outage goes at once, not at the next heartbeat (P21.5 e).
            if (pendingPosition != null) ownNode()?.let(::queuePosition)
            val state = RoomState(sink)
            val source = r.body?.source() ?: throw IOException("empty stream")
            ServerSentEvents.read(source) { event, data -> onEvent(state, token, event, data) }
        }
        streamResponse = null
    }

    /**
     * A write renewed the token (2 min before it expires) but the stream still carries the old one, which the server
     * revokes at its expiry, cutting the stream: reopen it now with the new token, without the pause (P21.5 g).
     */
    private fun reopenIfTokenRenewed() {
        val used = streamToken ?: return
        val now = auth.current ?: return
        if (!streamUp || used == now) return
        streamToken = null
        EventLog.event("relay.stream.renew", "streamAgeS" to (clockMs() - streamOpenedAt) / MS_PER_S)
        reopening.set(true)
        wakeUp()
        streamCall?.cancel()
    }

    private fun onEvent(
        state: RoomState,
        token: String,
        event: String,
        data: String,
    ) {
        EventLog.detail("relay.sse", "event" to event, "bytes" to data.length)
        when (event) {
            "put", "patch" -> {
                val body = Json.parseToJsonElement(data).jsonObject
                val path = body["path"]?.jsonPrimitive?.content ?: return
                val payload = body["data"] ?: JsonNull
                if (event == "put") {
                    state.put(path, payload)
                } else if (payload is JsonObject) {
                    state.patch(path, payload)
                }
            }

            "cancel" -> {
                throw FirebaseRefused("stream cancelled by the server")
            }

            "auth_revoked" -> {
                // Only this stream's token: a newer one, renewed by a write meanwhile, serves as it is. No pause.
                auth.invalidate(token)
                wakeUp()
                throw IOException("token expired")
            }
        }
    }

    private fun publishState() {
        val next =
            when {
                closed.get() -> state
                refused -> ServerState.REFUSED
                streamUp && writesOk -> ServerState.ONLINE
                state == ServerState.CONNECTING && !streamUp && writesOk -> ServerState.CONNECTING
                else -> ServerState.UNREACHABLE
            }
        if (next != state) {
            EventLog.event(
                "relay.state",
                "from" to state,
                "to" to next,
                "stream" to streamUp,
                "writesOk" to writesOk,
                "refused" to refused,
            )
            state = next
            onState?.invoke(next)
        }
    }

    // ---- presence -----------------------------------------------------------------------------------------------

    private fun serverNow(): Long = clockMs() + (offsetMs ?: 0L)

    /** Opens the link of every member that is present and closes that of those who stopped answering. */
    private fun evaluate() {
        if (offsetMs == null || closed.get()) return
        val now = serverNow()
        val opened = ArrayList<Pair<Peer, RelayLink>>()
        val dropped = ArrayList<Long>()
        synchronized(lock) {
            for (p in peers.values) {
                val age = now - p.lastBeat
                if (p.link == null) {
                    if (!p.banned && age <= OPEN_WITHIN_MS && p.lastBeat > p.droppedAt) {
                        val link = RelayLink(p.id)
                        p.link = link
                        opened += p to link
                        EventLog.event("relay.peer.open", "peer" to MeshNode.hex(p.id), "beatAgeMs" to age)
                    }
                } else if (age > CLOSE_AFTER_MS) {
                    EventLog.event("relay.peer.close", "peer" to MeshNode.hex(p.id), "beatAgeMs" to age)
                    p.link?.closed = true
                    p.link = null
                    p.droppedAt = now
                    dropped += p.id
                }
            }
        }
        for (id in dropped) listener.onLinkClosed(id)
        for ((p, link) in opened) listener.onLinkOpen(p.id, p.name.ifBlank { "?" }, p.color, link)
    }

    // ---- writing --------------------------------------------------------------------------------------------------

    private fun heartbeatJson(): String {
        val id = identity
        return buildJsonObject {
            put("t", buildJsonObject { put(".sv", "timestamp") })
            put("n", id?.name.orEmpty())
            put("k", id?.color ?: 0)
        }.toString()
    }

    private fun frameJson(frame: ByteArray): String =
        buildJsonObject {
            put("f", Base64.getEncoder().encodeToString(frame))
            put("t", buildJsonObject { put(".sv", "timestamp") })
        }.toString()

    /**
     * Sends the heartbeat when [HEARTBEAT_S] seconds of wall clock have passed (or the clock went back), checked every
     * second: a timer's own delay does not count the time the processor sleeps, and stretched to 90 s screen off
     * (P21.5 q).
     */
    private fun beatIfDue() {
        val t = clockMs()
        if (t >= lastBeatAt && t - lastBeatAt < HEARTBEAT_S * MS_PER_S) return
        lastBeatAt = t
        heartbeat()
    }

    private fun heartbeat() {
        if (!streamUp || blocked || closed.get()) return
        val me = identity?.memberId ?: return
        // Offline, writes fail slowly (up to the call timeout): never let heartbeats pile up behind each other.
        if (!heartbeatQueued.compareAndSet(false, true)) return
        enqueue(onStart = { heartbeatQueued.set(false) }) {
            val json = heartbeatJson()
            timed("h", null, json.length) { db.put("rooms/$room/b/${RelayPaths.member(me)}/h", json) }
        }
        if (pendingPosition != null) queuePosition("rooms/$room/b/${RelayPaths.member(me)}")
    }

    /** Writes the latest position not yet written; one write at most waits in the queue, always with the newest fix. */
    private fun queuePosition(node: String) {
        if (!positionQueued.compareAndSet(false, true)) return
        enqueue(onStart = { positionQueued.set(false) }) {
            val pos = pendingPosition ?: return@enqueue
            val json = frameJson(pos)
            writingPosition = pos
            try {
                timed("p", pos, json.length) { db.put("$node/p", json) }
            } finally {
                writingPosition = null
            }
            // A newer fix that came during the write stays pending for the next write.
            if (pendingPosition === pos) pendingPosition = null
        }
    }

    private fun cleanUp() {
        val me = identity?.memberId ?: return
        val cutoff = clockMs() - OWN_FRAME_TTL_MS
        val old = ArrayList<String>()
        synchronized(ownKeys) {
            while (ownKeys.isNotEmpty() && ownKeys.first().second < cutoff) old += ownKeys.removeFirst().first
        }
        if (old.isEmpty()) return
        val body = JsonObject(old.associateWith { JsonNull }).toString()
        EventLog.detail("relay.c.clean", "keys" to old.size)
        enqueue { timed("c.clean", null, body.length) { db.patch("rooms/$room/b/${RelayPaths.member(me)}/c", body) } }
    }

    /**
     * Deletes the control frames of this member that a previous process left on the server (stopped, reinstalled,
     * data cleared): [cleanUp] only knows the keys written by this one (P21.5 r). The time is read in the key.
     */
    private fun purgeOldFrames() {
        val node = ownNode() ?: return
        val cutoff = serverNow() - OWN_FRAME_TTL_MS
        val mine = synchronized(ownKeys) { ownKeys.mapTo(HashSet()) { it.first } }
        val keys = timed("c.list", null, 0) { db.keys("$node/c") }
        val old = keys.filter { it !in mine && (PushIds.timeMs(it) ?: Long.MAX_VALUE) < cutoff }
        EventLog.event("relay.c.purge", "keys" to keys.size, "old" to old.size)
        if (old.isEmpty()) return
        val body = JsonObject(old.associateWith { JsonNull }).toString()
        timed("c.purge", null, body.length) { db.patch("$node/c", body) }
    }

    /**
     * Runs one server call and logs it (P21): what was written ([what]: `h`, `p`, `c`), the frame, how long it took;
     * a failure is logged with its cause and passed on.
     */
    private fun <T> timed(
        what: String,
        frame: ByteArray?,
        bytes: Int,
        call: () -> T,
    ): T {
        val t0 = clockMs()
        val result =
            try {
                call()
            } catch (e: IOException) {
                throw failed(what, frame, t0, e)
            } catch (e: RuntimeException) {
                throw failed(what, frame, t0, e)
            }
        writes.incrementAndGet()
        bytesUp.addAndGet(bytes.toLong())
        val fields =
            arrayOf<Pair<String, Any?>>(
                "what" to what,
                "ms" to clockMs() - t0,
                "type" to frame?.let { Wire.typeOf(it) },
                "fid" to frame?.let { Wire.frameId(it) },
                "bytes" to bytes,
            )
        // The position writes are the subject of the tests: always kept; the others only in the test mode.
        if (what == "p") EventLog.event("relay.write", *fields) else EventLog.detail("relay.write", *fields)
        return result
    }

    private fun failed(
        what: String,
        frame: ByteArray?,
        t0: Long,
        e: Exception,
    ): Exception {
        writeFails.incrementAndGet()
        EventLog.event(
            "relay.write.fail",
            "what" to what,
            "ms" to clockMs() - t0,
            "type" to frame?.let { Wire.typeOf(it) },
            "fid" to frame?.let { Wire.frameId(it) },
            "error" to e.javaClass.simpleName,
            "why" to e.message,
        )
        return e
    }

    private fun gaugeValues(): Map<String, Any?> {
        val now = serverNow()
        val peerList =
            synchronized(lock) {
                peers.values.joinToString(",") {
                    MeshNode.hex(it.id) + ":" + (now - it.lastBeat) / MS_PER_S + "s:" +
                        if (it.link != null) "on" else "off"
                }
            }
        return mapOf(
            "state" to state,
            "stream" to streamUp,
            "streamAgeS" to if (streamUp) (clockMs() - streamOpenedAt) / MS_PER_S else null,
            "writesOk" to writesOk,
            "refused" to refused,
            "offsetMs" to offsetMs,
            "queued" to queuedWrites.get(),
            "posPending" to (pendingPosition != null),
            "blocked" to blocked,
            "writes" to writes.get(),
            "fails" to writeFails.get(),
            "bytesUp" to bytesUp.get(),
            "framesIn" to framesIn.get(),
            "dropped" to framesDropped.get(),
            "pReplaced" to positionsReplaced.get(),
            "ownKeys" to synchronized(ownKeys) { ownKeys.size },
            "peers" to peerList,
        )
    }

    /** Runs a write on the writer thread; a network failure or a refusal is reflected in the connection state. */
    private fun enqueue(
        onStart: () -> Unit = {},
        work: () -> Unit,
    ) {
        if (closed.get()) return
        queuedWrites.incrementAndGet()
        val queued =
            runCatching {
                writer.execute {
                    queuedWrites.decrementAndGet()
                    try {
                        // Cleared first: a write that comes during this one queues again rather than being lost.
                        onStart()
                        work()
                        if (!writesOk) {
                            writesOk = true
                            publishState()
                        }
                        reopenIfTokenRenewed()
                    } catch (e: FirebaseRefused) {
                        refused = true
                        publishState()
                    } catch (e: IOException) {
                        writesOk = false
                        publishState()
                    } catch (e: RuntimeException) {
                        // A malformed answer must not stop the writer.
                        writesOk = false
                        publishState()
                    }
                }
            }
        if (queued.isFailure) {
            queuedWrites.decrementAndGet()
            onStart()
        }
    }

    private fun send(
        frame: ByteArray,
        onPositionChannel: Boolean,
    ) {
        val node = ownNode() ?: return
        // `p` holds a single frame, read by a member who arrives: only positions go there. A PING (liveness, every
        // 15 s) goes with the control frames, else it would hide the position until the next one (P21.5 b).
        val toPositionSlot = onPositionChannel && Wire.typeOf(frame) == MsgType.POS
        synchronized(lock) {
            // The group logic sends one broadcast to every link with the same array: one write is enough.
            if (toPositionSlot) {
                if (frame === lastPosFrame) return
                lastPosFrame = frame
            } else {
                if (frame === lastCtrlFrame) return
                lastCtrlFrame = frame
            }
        }
        if (toPositionSlot) {
            val replaced = pendingPosition
            pendingPosition = frame
            if (replaced != null && replaced !== writingPosition) {
                // Never written and never will be (a write already started goes through): only the newest goes.
                positionsReplaced.incrementAndGet()
                EventLog.event(
                    "relay.p.replaced",
                    "old" to Wire.typeOf(replaced),
                    "oldFid" to Wire.frameId(replaced),
                    "new" to Wire.typeOf(frame),
                    "newFid" to Wire.frameId(frame),
                    "queued" to queuedWrites.get(),
                )
            }
            queuePosition(node)
        } else {
            enqueue {
                val json = frameJson(frame)
                val key = timed("c", frame, json.length) { db.push("$node/c", json) }
                if (key.isNotEmpty()) synchronized(ownKeys) { ownKeys.addLast(key to clockMs()) }
            }
        }
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // A failing periodic task must not stop the others.
        }
    }

    private inner class RelayLink(
        val peer: Long,
    ) : MeshLink {
        @Volatile
        var closed = false

        override val isBroadcastMedium: Boolean get() = true

        override fun send(
            frame: ByteArray,
            onPositionChannel: Boolean,
        ): Boolean {
            if (closed || this@FirebaseRelay.closed.get()) return false
            this@FirebaseRelay.send(frame, onPositionChannel)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            synchronized(lock) {
                peers[peer]?.takeIf { it.link === this }?.let {
                    it.link = null
                    it.droppedAt = serverNow()
                }
            }
        }
    }

    companion object {
        const val HEARTBEAT_S = 15L
        const val PRESENCE_EVERY_S = 5L
        const val CLEANUP_S = 60L

        /** A member is linked while its last heartbeat is at most this old (two missed beats plus margin). */
        const val OPEN_WITHIN_MS = 40_000L
        const val CLOSE_AFTER_MS = 75_000L
        const val CONTROL_MAX_AGE_MS = 150_000L
        const val OWN_FRAME_TTL_MS = 120_000L
        private const val WRITE_TIMEOUT_S = 20L

        /** Firebase sends a keep-alive event every 30 s: a silent stream is a dead one well before this. */
        private const val STREAM_READ_TIMEOUT_S = 45L
        private const val CLOSE_WAIT_S = 3L
        private const val STABLE_MS = 15_000L

        /** Short on purpose: in the field the network comes and goes, and a member must come back quickly. */
        private const val MAX_BACKOFF_S = 8L

        /** Without a network, an attempt all the same after this long, in case its return was not told (P21.5 p). */
        private const val OFFLINE_MAX_WAIT_MS = 300_000L

        /** A clock measure is made again when its round trip is longer (P21.5 n). */
        private const val MAX_CLOCK_RTT_MS = 10_000L
        private const val CLOCK_TRIES = 3
        private const val MS_PER_S = 1000L
        private const val NS_PER_MS = 1_000_000L

        /** A stream opened this recently is already on the new network: [reconnectNow] keeps it. */
        private const val FRESH_STREAM_MS = 5_000L

        /** Enough of the room name to tell two rooms apart in the event log. */
        private const val ROOM_PREFIX = 6
    }
}

/** The anonymous identity kept in a small file, so that the device keeps the same Firebase user between launches. */
class FileAuthStore(
    private val file: File,
) : AuthStore {
    override fun read(): String? =
        runCatching {
            file.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        }.getOrNull()

    override fun write(refreshToken: String?) {
        runCatching {
            if (refreshToken == null) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(refreshToken)
                if (!tmp.renameTo(file)) file.writeText(refreshToken)
            }
        }
    }
}

class FirebaseRelayFactory(
    private val authStore: AuthStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val endpoints: FirebaseEndpoints = FirebaseEndpoints(),
) : RelayTransportFactory {
    override fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
    ): RelayTransport = FirebaseRelay(config, groupId, listener, authStore, http, endpoints)
}
