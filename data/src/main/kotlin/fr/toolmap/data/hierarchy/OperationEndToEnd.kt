package fr.toolmap.data.hierarchy

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import android.util.Log
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.ObjectRef
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.problem.catchReported
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.PrintWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The end-to-end trial of the « Opération » mode between two devices, over the real coordination server (P39.12,
 * ADR-134, `docs/specs/group-hierarchy.md` § 23), without a screen. Builds with the testing tools only
 * (`BuildConfig.DEV_TOOLS`): asked one command at a time by
 * `adb shell dumpsys activity fr.toolmap/.MainActivity operation-e2e <command> [<token> <arguments>…]`, and driven on
 * two emulators by `tools/emulator/operation-e2e.sh`.
 *
 * The commands that wait or speak to the server run in order on a thread of their own; each answers once, in logcat
 * (tag [TAG], `operation-e2e <token> <command> ok|fail key=value…`) and to `result <token>`. A few answer at once, in
 * the dump: `result`, `status`, `clock`, `counters`, and `invite`, the only one that writes the invitation, and
 * never in logcat.
 *
 * Its operations live in a folder ([FOLDER]) and under key aliases ([ALIAS_PREFIX]) of their own, with an anonymous
 * identity on the server of their own ([AUTH_FILE]): it never touches a real operation. What it writes is only
 * results: never an invitation, a key, the room, a token, the server's address or a member's identifier.
 */
object OperationEndToEnd {
    const val DUMP_ARGUMENT = "operation-e2e"
    const val TAG = "ToolMapOpE2E"
    const val FOLDER = "hierarchy/e2e"
    const val AUTH_FILE = "hierarchy/e2e-auth/firebase-auth.token"
    internal const val ALIAS_PREFIX = "toolmap.hierarchy.e2eop."
    private const val INVITE_WAIT_MS = 3_000L

    private val results = ConcurrentHashMap<String, String>()
    private val worker =
        Executors.newSingleThreadExecutor { r -> Thread(r, "operation-e2e").apply { isDaemon = true } }

    @Volatile
    private var session: EndToEndSession? = null

    /**
     * One command, [args] being what follows [DUMP_ARGUMENT]. [relays] makes the relay of the coordination server over
     * the client it is given (the meter's); [server] is the one built into the APK, `null` when there is none.
     */
    fun dump(
        context: Context,
        args: List<String>,
        writer: PrintWriter,
        relays: (OkHttpClient) -> RelayTransportFactory,
        server: RelayConfig?,
    ) {
        val s = sessionFor(context, relays, server)
        when (val command = args.firstOrNull()) {
            null -> {
                writer.println(
                    "$DUMP_ARGUMENT commands: status clock counters invite result <token>, or <command> <token> …",
                )
            }

            "status" -> {
                writer.println("$DUMP_ARGUMENT status ${s.status()}")
            }

            "clock" -> {
                writer.println("$DUMP_ARGUMENT clock ms=${System.currentTimeMillis()}")
            }

            "counters" -> {
                val uid = Process.myUid()
                val traffic = "uid.tx=${TrafficStats.getUidTxBytes(uid)} uid.rx=${TrafficStats.getUidRxBytes(uid)}"
                writer.println("$DUMP_ARGUMENT counters t=${System.currentTimeMillis()} ${s.counters()} $traffic")
            }

            // The only answer that carries the invitation: to adb's output, which the script keeps to itself.
            "invite" -> {
                val link = runBlocking { withTimeoutOrNull(INVITE_WAIT_MS) { s.invitation() } }
                writer.println("$DUMP_ARGUMENT invite ${link ?: "none"}")
                Log.i(TAG, "$DUMP_ARGUMENT invite ${if (link == null) "none" else "made"} epoch=${s.epoch()}")
            }

            "result" -> {
                val token = args.getOrNull(1).orEmpty()
                writer.println("$DUMP_ARGUMENT result $token ${results[token] ?: "pending"}")
            }

            else -> {
                val token = args.getOrNull(1)
                if (token == null || results.containsKey(token)) {
                    writer.println("$DUMP_ARGUMENT refused: a new token is needed")
                    return
                }
                results[token] = "pending"
                worker.execute {
                    val say: (String) -> Unit = { Log.i(TAG, "$DUMP_ARGUMENT $token $it") }
                    val answer =
                        catchReported("operation.e2e.command") {
                            runBlocking { s.run(command, args.drop(2), say) }
                        }.getOrElse { "fail crashed error=${it.javaClass.simpleName}" }
                    results[token] = answer
                    Log.i(TAG, "$DUMP_ARGUMENT $token $command $answer")
                }
                writer.println("$DUMP_ARGUMENT queued $token $command")
            }
        }
    }

    private fun sessionFor(
        context: Context,
        relays: (OkHttpClient) -> RelayTransportFactory,
        server: RelayConfig?,
    ): EndToEndSession =
        session ?: synchronized(this) {
            session ?: run {
                val files = context.applicationContext.filesDir
                val meter = TrafficMeter()
                val client = OkHttpClient.Builder().addNetworkInterceptor(meter).build()
                EndToEndSession(File(files, FOLDER), AndroidKeyVault(), relays(client), server, meter)
            }.also { session = it }
        }
}

/**
 * The commands of [OperationEndToEnd] over one [OperationController] (JVM-testable: `OperationEndToEndTest`). Each
 * returns `ok key=value…` or `fail <why> key=value…`, one line, words without spaces. Times are this device's clock
 * (`…AtMs`, epoch milliseconds) or durations (`…Ms`); a latency between two devices is worked out by the script,
 * which measures the gap between their clocks.
 */
internal class EndToEndSession(
    root: File,
    vault: KeyVault,
    relays: RelayTransportFactory?,
    private val server: RelayConfig?,
    private val meter: TrafficMeter,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val storage = OperationStorage(root, vault, OperationEndToEnd.ALIAS_PREFIX)
    val controller = OperationController(storage, relays?.let(meter::wrap), { server }, clock)
    private var shared: ObjectRef? = null

    /** The epoch of the invitation key when this phone last gave an invitation. */
    private var invitedAtEpoch = 0

    private val view: OperationView get() = controller.view.value

    fun epoch(): Int = view.inviteEpoch

    suspend fun invitation(): String? = controller.invitationLink().also { invitedAtEpoch = view.inviteEpoch }

    fun status(): String {
        val v = view
        return words(
            "phase" to v.phase,
            "server" to v.server,
            "members" to v.members.size,
            "online" to v.online.size,
            "requests" to v.joinRequests.size,
            "epoch" to v.inviteEpoch,
            "positions" to v.positions.size,
            "messages" to v.threads.values.sumOf { it.size },
            "alerts" to v.alerts.size,
            "objects" to v.objects.size,
            "ending" to v.ending,
            "byTrace" to v.endedByTrace,
            "traceKept" to (v.traceKeptUntilMs != null),
        )
    }

    fun counters(): String = meter.snapshot().entries.joinToString(" ") { "${it.key}=${it.value}" }

    suspend fun run(
        command: String,
        args: List<String>,
        say: (String) -> Unit,
    ): String {
        val a = Args(args)
        return when (command) {
            "reset" -> reset()
            "create" -> create(a.text(0), a.text(1))
            "join" -> join(a.text(0), a.text(1), a.text(2))
            "open" -> reopen()
            "close" -> controller.close().let { ok() }
            "await-server" -> awaitServer(a.state(0), a.seconds(1))
            "await-request" -> awaitRequest(a.text(0), a.seconds(1))
            "admit" -> admit(a.text(0))
            "await-phase" -> awaitPhase(a.phase(0), a.seconds(1))
            "organize" -> organize(a.text(0))
            "await-placed" -> awaitPlaced(a.text(0), a.text(1), a.seconds(2))
            "send-positions" -> sendPositions(a.number(0), a.number(1).toLong(), say)
            "await-positions" -> awaitPositions(a.number(0), a.seconds(1), say)
            "send-messages" -> sendMessages(a.text(0), say)
            "await-messages" -> awaitMessages(a.text(0), a.number(1), a.seconds(2), say)
            "send-alert" -> sendAlert(a.text(0))
            "await-alert" -> awaitAlert(a.text(0), a.seconds(1))
            "lift-alert" -> liftAlert()
            "await-alert-gone" -> awaitAlertGone(a.seconds(0))
            "put-waypoint" -> putWaypoint()
            "await-object" -> awaitObject(true, a.seconds(0))
            "remove-waypoint" -> removeWaypoint()
            "await-object-gone" -> awaitObject(false, a.seconds(0))
            "rename-group" -> renameGroup(a.text(0))
            "await-group" -> awaitGroup(a.text(0), a.seconds(1))
            "leave" -> leave()
            "await-departure" -> awaitDeparture(a.seconds(0))
            "await-no-request" -> awaitNoRequest(a.text(0), a.seconds(1))
            "dissolve" -> dissolve()
            "announce" -> announce()
            "await-announced" -> awaitAnnounced(a.seconds(0))
            "await-ended" -> awaitEnded(a.seconds(0))
            else -> fail("unknown-command")
        }.let { if (a.bad) fail("bad-arguments") else it }
    }

    // ---- start -------------------------------------------------------------------------------------------------

    private suspend fun reset(): String {
        controller.close()
        storage.wipeAll()
        return ok("stored" to controller.ids().size, "traces" to controller.traces().size)
    }

    private suspend fun create(
        operation: String,
        me: String,
    ): String {
        if (server == null) return fail("no-server-in-this-apk")
        val t0 = clock()
        val started = controller.create(operation, me)
        if (started != StartResult.STARTED) return fail("not-created", "result" to started)
        val startMs = clock() - t0
        await(ONLINE_S) { it.takeIf { v -> v.server == ServerState.ONLINE } }
            ?: return fail("server-not-online", "server" to view.server, "startMs" to startMs)
        val v = view
        return ok(
            "startMs" to startMs,
            "onlineMs" to clock() - t0,
            "phase" to v.phase,
            "mayInvite" to v.mayInvite,
            "epoch" to v.inviteEpoch,
        )
    }

    private suspend fun join(
        me: String,
        operation: String,
        link: String,
    ): String {
        val preview = controller.preview(link) ?: return fail("not-an-invitation")
        val t0 = clock()
        val started = controller.join(link, me)
        if (started != StartResult.STARTED) return fail("not-joined", "result" to started)
        await(ONLINE_S) { it.takeIf { v -> v.server == ServerState.ONLINE } }
            ?: return fail("server-not-online", "server" to view.server)
        return ok(
            "onlineMs" to clock() - t0,
            "phase" to view.phase,
            "previewName" to (preview.operationName == operation),
            "viewName" to (view.operationName == operation),
            "stale" to preview.staleness,
        )
    }

    private suspend fun reopen(): String {
        val id =
            controller.ids().singleOrNull()
                ?: return fail("not-one-stored-operation", "stored" to controller.ids().size)
        val t0 = clock()
        val started = controller.open(id)
        if (started != StartResult.STARTED) return fail("not-opened", "result" to started)
        val openMs = clock() - t0
        await(ONLINE_S) { it.takeIf { v -> v.server == ServerState.ONLINE } }
            ?: return fail("server-not-online", "server" to view.server, "openMs" to openMs)
        return ok("openMs" to openMs, "onlineMs" to clock() - t0, "phase" to view.phase, "members" to view.members.size)
    }

    private suspend fun awaitServer(
        state: ServerState?,
        seconds: Int,
    ): String {
        val t0 = clock()
        await(seconds) { it.takeIf { v -> v.server == state } }
            ?: return fail("server", "server" to view.server)
        return ok("ms" to clock() - t0, "atMs" to clock(), "server" to view.server)
    }

    // ---- let in and place ----------------------------------------------------------------------------------------

    private suspend fun awaitRequest(
        name: String,
        seconds: Int,
    ): String {
        val t0 = clock()
        val found =
            await(seconds) { v -> v.joinRequests.firstOrNull { it.name == name } }
                ?: return fail(
                    "no-request",
                    "requests" to view.joinRequests.size,
                    "nameless" to view.joinRequests.count { it.name == null },
                    "online" to view.online.size,
                )
        return ok(
            "ms" to clock() - t0,
            "nameShown" to true,
            "requests" to view.joinRequests.size,
            "receivedAtMs" to found.receivedAtMs,
        )
    }

    private suspend fun admit(name: String): String {
        val request = view.joinRequests.firstOrNull { it.name == name } ?: return fail("no-request")
        val at = clock()
        controller.admit(request.member)?.let { return fail("refused", "why" to it) }
        return ok("writtenAtMs" to at)
    }

    private suspend fun awaitPhase(
        phase: OperationPhase?,
        seconds: Int,
    ): String {
        val t0 = clock()
        val v =
            await(seconds) { it.takeIf { v -> v.phase == phase } }
                ?: return fail("phase", "phase" to view.phase, "online" to view.online.size, "server" to view.server)
        return ok(
            "ms" to clock() - t0,
            "seenAtMs" to clock(),
            "phase" to v.phase,
            "position" to v.targets?.position?.lane,
        )
    }

    /** The creator: a group, the other phone placed in it and its leader, this phone leader of the operation. */
    private suspend fun organize(group: String): String {
        val v = view
        val self = v.self ?: return fail("no-member")
        val peer = other(v) ?: return fail("not-one-other-member", "members" to v.members.size)
        val at = clock()
        val (id, refused) = controller.createGroup(group)
        if (id == null) return fail("group-refused", "why" to refused)
        controller.place(peer, id)?.let { return fail("not-placed", "why" to it) }
        controller.setPost(id, Post.LEADER, peer)?.let { return fail("not-group-leader", "why" to it) }
        controller.setPost(HierarchyState.ROOT, Post.LEADER, self)?.let { return fail("not-leader", "why" to it) }
        return ok("writtenAtMs" to at, "ms" to clock() - at)
    }

    private suspend fun awaitPlaced(
        group: String,
        creator: String,
        seconds: Int,
    ): String {
        val t0 = clock()
        val v =
            await(seconds) { v ->
                v.takeIf {
                    it.phase == OperationPhase.PLACED && it.groups.any { g -> g.name == group } &&
                        creatorName(it) == creator
                }
            } ?: return fail(
                "not-placed",
                "phase" to view.phase,
                "groupNamed" to view.groups.any { it.name == group },
                "creatorNamed" to (creatorName(view) == creator),
            )
        val t = v.targets
        return ok(
            "ms" to clock() - t0,
            "seenAtMs" to clock(),
            "alert" to t?.alert?.lane,
            "alertReachesCreator" to (v.hierarchy?.creator?.let { it in t?.alertReaders.orEmpty() } == true),
            "position" to t?.position?.lane,
            "threads" to t?.messages?.size,
        )
    }

    // ---- share ---------------------------------------------------------------------------------------------------

    private suspend fun sendPositions(
        count: Int,
        intervalMs: Long,
        say: (String) -> Unit,
    ): String {
        var sent = 0
        val times = ArrayList<Long>()
        for (i in 1..count) {
            val at = clock()
            val outcome = controller.sendPosition(PeerFix(BASE_LAT + i * STEP_DEG, BASE_LON, timeSec = at / MS))
            say("position i=$i outcome=$outcome sentAtMs=$at")
            times += at
            if (outcome == ShareOutcome.SENT) sent++
            if (i < count) delay(intervalMs)
        }
        if (sent == 0) return fail("none-sent")
        return ok("sent" to sent, "of" to count, "sentAtMs" to times.joinToString(","))
    }

    private suspend fun awaitPositions(
        count: Int,
        seconds: Int,
        say: (String) -> Unit,
    ): String {
        val peer = other(view) ?: return fail("not-one-other-member")
        val seen = LinkedHashMap<Int, Long>()
        var lane: Any? = null
        withTimeoutOrNull(seconds * MS) {
            while (count !in seen) {
                view.positions[peer]?.let { p ->
                    val i = Math.round((p.fix.lat - BASE_LAT) / STEP_DEG).toInt()
                    if (i in 1..count && i !in seen) {
                        seen[i] = p.receivedAtMs
                        lane = p.channel.lane
                        say("position-in i=$i receivedAtMs=${p.receivedAtMs}")
                    }
                }
                if (count !in seen) delay(FAST_POLL_MS)
            }
        }
        val received = seen.entries.joinToString(",") { "${it.key}:${it.value}" }
        if (count !in
            seen
        ) {
            return fail("last-position-missing", "got" to seen.size, "of" to count, "receivedAtMs" to received)
        }
        return ok("got" to seen.size, "of" to count, "lane" to lane, "receivedAtMs" to received)
    }

    private suspend fun sendMessages(
        prefix: String,
        say: (String) -> Unit,
    ): String {
        val v = view
        val peer = other(v)
        val threads = v.targets?.messages.orEmpty()
        var expected = 0
        val times = ArrayList<String>()
        for ((i, channel) in threads.withIndex()) {
            val reads = peer != null && readsIn(v, peer, channel)
            val at = clock()
            val outcome = controller.sendMessage(channel, "$prefix-$i")
            say("message i=$i lane=${channel.lane} outcome=$outcome peerReads=$reads sentAtMs=$at")
            if (outcome != ShareOutcome.SENT &&
                outcome != ShareOutcome.QUEUED
            ) {
                return fail("not-sent", "lane" to channel.lane, "outcome" to outcome)
            }
            if (reads) expected++
            times += "$i:${channel.lane}:$reads"
        }
        if (threads.isEmpty()) return fail("no-thread")
        return ok("threads" to threads.size, "peerReads" to expected, "sent" to times.joinToString(","))
    }

    private suspend fun awaitMessages(
        prefix: String,
        count: Int,
        seconds: Int,
        say: (String) -> Unit,
    ): String {
        val t0 = clock()
        val got = await(seconds) { v -> received(v, prefix).takeIf { it.size >= count } }
        val waitedMs = clock() - t0
        // A moment more: a message of a thread this phone does not read must not come.
        delay(GRACE_MS)
        val all = received(view, prefix)
        val lanes = all.joinToString(",") { (channel, m) -> "${channel.lane}:${m.receivedAtMs - m.sentAtMs}" }
        for ((channel, m) in all) {
            say(
                "message-in lane=${channel.lane} text=${m.text} latencyRawMs=${m.receivedAtMs - m.sentAtMs}",
            )
        }
        if (got == null) return fail("missing", "got" to all.size, "expected" to count, "lanes" to lanes)
        if (all.size != count) return fail("one-too-many", "got" to all.size, "expected" to count, "lanes" to lanes)
        return ok("got" to all.size, "ms" to waitedMs, "lanes" to lanes)
    }

    private suspend fun sendAlert(text: String): String {
        val v = view
        val peer = other(v)
        val targets = v.targets ?: return fail("no-target")
        val at = clock()
        val outcome = controller.sendAlert(text, null)
        if (outcome != ShareOutcome.SENT &&
            outcome != ShareOutcome.QUEUED
        ) {
            return fail("not-sent", "outcome" to outcome)
        }
        return ok(
            "lane" to targets.alert?.lane,
            "peerReads" to (peer != null && peer in targets.alertReaders),
            "outcome" to outcome,
            "sentAtMs" to at,
        )
    }

    private suspend fun awaitAlert(
        text: String,
        seconds: Int,
    ): String {
        val peer = other(view) ?: return fail("not-one-other-member")
        val t0 = clock()
        val alert =
            await(seconds) { v -> v.alerts.firstOrNull { it.author == peer && it.text == text } }
                ?: return fail("no-alert", "alerts" to view.alerts.size)
        return ok(
            "ms" to clock() - t0,
            "lane" to alert.channel.lane,
            "latencyRawMs" to alert.receivedAtMs - alert.sentAtMs,
        )
    }

    private suspend fun liftAlert(): String {
        val at = clock()
        val outcome = controller.liftAlert()
        if (outcome != ShareOutcome.SENT &&
            outcome != ShareOutcome.QUEUED
        ) {
            return fail("not-lifted", "outcome" to outcome)
        }
        return ok("outcome" to outcome, "sentAtMs" to at)
    }

    private suspend fun awaitAlertGone(seconds: Int): String {
        val peer = other(view) ?: return fail("not-one-other-member")
        val t0 = clock()
        await(seconds) { v -> v.takeIf { it.alerts.none { a -> a.author == peer } } }
            ?: return fail("still-shown", "alerts" to view.alerts.size)
        return ok("ms" to clock() - t0, "seenAtMs" to clock())
    }

    /** A waypoint in the first place this phone may put one that the other phone reads. */
    private suspend fun putWaypoint(): String {
        val v = view
        val self = v.self ?: return fail("no-member")
        val peer = other(v) ?: return fail("not-one-other-member")
        val channel =
            v.targets?.waypoints.orEmpty().firstOrNull { readsIn(v, peer, it) }
                ?: return fail("no-place-the-other-reads", "places" to v.targets?.waypoints?.size)
        val at = clock()
        val (outcome, id) = controller.putObject(ShareKind.WAYPOINT, channel, WAYPOINT)
        if (outcome != ShareOutcome.SENT &&
            outcome != ShareOutcome.QUEUED
        ) {
            return fail("not-shared", "outcome" to outcome)
        }
        shared = ObjectRef(channel, self, id)
        return ok("lane" to channel.lane, "outcome" to outcome, "sentAtMs" to at)
    }

    private suspend fun removeWaypoint(): String {
        val ref =
            shared
                ?: view.objects.firstOrNull {
                    it.author == view.self
                }?.let { ObjectRef(it.channel, it.author, it.objectId) }
        ref ?: return fail("nothing-shared")
        val at = clock()
        val outcome = controller.removeObject(ref)
        if (outcome != ShareOutcome.SENT &&
            outcome != ShareOutcome.QUEUED
        ) {
            return fail("not-removed", "outcome" to outcome)
        }
        shared = null
        return ok("outcome" to outcome, "sentAtMs" to at)
    }

    private suspend fun awaitObject(
        present: Boolean,
        seconds: Int,
    ): String {
        val peer = other(view) ?: return fail("not-one-other-member")
        val t0 = clock()
        val v =
            await(seconds) { v -> v.takeIf { it.objects.any { o -> o.author == peer && !o.deleted } == present } }
                ?: return fail(if (present) "not-shown" else "still-shown", "objects" to view.objects.size)
        return ok("ms" to clock() - t0, "seenAtMs" to clock(), "objects" to v.objects.size)
    }

    private suspend fun renameGroup(name: String): String {
        val group = view.groups.singleOrNull() ?: return fail("not-one-group", "groups" to view.groups.size)
        val at = clock()
        controller.renameGroup(group.id, name)?.let { return fail("refused", "why" to it) }
        return ok("writtenAtMs" to at)
    }

    private suspend fun awaitGroup(
        name: String,
        seconds: Int,
    ): String {
        val t0 = clock()
        await(seconds) { v -> v.takeIf { it.groups.any { g -> g.name == name } } }
            ?: return fail("not-renamed", "groups" to view.groups.size)
        return ok("ms" to clock() - t0, "seenAtMs" to clock())
    }

    // ---- leave, renew, dissolve ----------------------------------------------------------------------------------

    private suspend fun leave(): String {
        val at = clock()
        controller.leave()?.let { return fail("refused", "why" to it) }
        val v =
            await(ENDED_S) { it.takeIf { v -> v.phase == OperationPhase.ENDED } }
                ?: return fail("not-ended", "phase" to view.phase)
        return ok(
            "writtenAtMs" to at,
            "endedMs" to clock() - at,
            "ending" to v.ending,
            "stored" to controller.ids().size,
        )
    }

    /** The other phone left: gone from the members, and the invitation key renewed (older invitations die). */
    private suspend fun awaitDeparture(seconds: Int): String {
        val self = view.self ?: return fail("no-member")
        val before = invitedAtEpoch
        val t0 = clock()
        await(seconds) { v -> v.takeIf { it.members.all { m -> m.id == self } } }
            ?: return fail("still-a-member", "members" to view.members.size)
        val goneMs = clock() - t0
        val goneAt = clock()
        await(seconds) { v -> v.takeIf { it.inviteEpoch > before } }
            ?: return fail("not-renewed", "epoch" to view.inviteEpoch, "before" to before, "goneMs" to goneMs)
        return ok(
            "goneMs" to goneMs,
            "goneAtMs" to goneAt,
            "renewedMs" to clock() - t0,
            "epochBefore" to before,
            "epoch" to view.inviteEpoch,
        )
    }

    /** For [seconds], no request to come in named [name], while requests under an old invitation key are dropped. */
    private suspend fun awaitNoRequest(
        name: String,
        seconds: Int,
    ): String {
        val before = droppedOldKey()
        val shown = await(seconds) { v -> v.joinRequests.firstOrNull { it.name == name } }
        val dropped = droppedOldKey() - before
        if (shown != null) return fail("request-shown", "droppedOldKey" to dropped)
        if (dropped <= 0) return fail("no-attempt-heard", "droppedOldKey" to dropped, "online" to view.online.size)
        return ok("seconds" to seconds, "droppedOldKey" to dropped, "requests" to view.joinRequests.size)
    }

    private suspend fun droppedOldKey(): Int = controller.withNode { it.dropped["join.old_key"] ?: 0 } ?: 0

    private suspend fun dissolve(): String {
        val at = clock()
        controller.dissolve()?.let { return fail("refused", "why" to it) }
        val v = awaitEndedView(ENDED_S)?.first ?: return fail("not-ended", "phase" to view.phase)
        val kept = v.traceKeptUntilMs
        return ok(
            "writtenAtMs" to at,
            "endedMs" to clock() - at,
            "ending" to v.ending,
            "traceKept" to (kept != null),
            "keptDays" to kept?.let { (it - at + DAY_MS / 2) / DAY_MS },
            "traces" to controller.traces().size,
            "stored" to controller.ids().size,
        )
    }

    /** The app comes to the front: a moment in the room of each trace kept (R1, ADR-131). */
    private suspend fun announce(): String {
        val traces = controller.traces().size
        val at = clock()
        val announcing = controller.announceDissolutions()
        if (announcing == 0) return fail("nothing-announced", "traces" to traces)
        return ok("traces" to traces, "announcing" to announcing, "atMs" to at)
    }

    /** Until the moments of the traces are over (asking again within ten minutes opens none). */
    private suspend fun awaitAnnounced(seconds: Int): String {
        val t0 = clock()
        withTimeoutOrNull(seconds * MS) {
            while (controller.announceDissolutions() > 0) delay(SLOW_POLL_MS)
        } ?: return fail("still-announcing")
        return ok("ms" to clock() - t0)
    }

    private suspend fun awaitEnded(seconds: Int): String {
        val t0 = clock()
        val v =
            awaitEndedView(seconds)
                ?: return fail(
                    "not-ended",
                    "phase" to view.phase,
                    "online" to view.online.size,
                    "server" to view.server,
                )
        return ok(
            "ms" to clock() - t0,
            "seenAtMs" to v.second,
            "ending" to v.first.ending,
            "byTrace" to v.first.endedByTrace,
            "traceKept" to (v.first.traceKeptUntilMs != null),
            "traces" to controller.traces().size,
            "stored" to controller.ids().size,
        )
    }

    /**
     * The view once the operation is over, with when that was first seen: a dissolution is said
     * ([OperationPhase.ENDED]) a moment before its trace is written ([OperationView.traceKeptUntilMs]), which is then
     * waited for a little.
     */
    private suspend fun awaitEndedView(seconds: Int): Pair<OperationView, Long>? {
        await(seconds) { it.takeIf { v -> v.phase == OperationPhase.ENDED } } ?: return null
        val seenAt = clock()
        val v =
            await(TRACE_WRITTEN_S) { it.takeIf { v -> v.ending != Ending.DISSOLVED || v.traceKeptUntilMs != null } }
                ?: view
        return v to seenAt
    }

    // ---- inside --------------------------------------------------------------------------------------------------

    /** The one other member, or the one phone asking to come in when there is no other member yet. */
    private fun other(v: OperationView): Long? {
        val others = v.members.filter { it.id != v.self }
        return others.singleOrNull()?.id ?: if (others.isEmpty()) v.joinRequests.singleOrNull()?.member else null
    }

    private fun readsIn(
        v: OperationView,
        member: Long,
        channel: Channel,
    ): Boolean = v.hierarchy?.readersOf(channel)?.any { it.id == member } == true

    private fun creatorName(v: OperationView): String? = v.members.firstOrNull { it.id == v.hierarchy?.creator }?.name

    private fun received(
        v: OperationView,
        prefix: String,
    ) = v.threads.flatMap { (channel, list) ->
        list.filter { it.author != v.self && it.text.startsWith("$prefix-") }.map { channel to it }
    }

    private suspend fun <T> await(
        seconds: Int,
        probe: (OperationView) -> T?,
    ): T? =
        withTimeoutOrNull(seconds * MS) {
            var found = probe(view)
            while (found == null) {
                delay(POLL_MS)
                found = probe(view)
            }
            found
        }

    /** The arguments of a command; a missing or malformed one marks them [bad] (the command then fails as such). */
    private class Args(
        private val values: List<String>,
    ) {
        var bad = false

        fun text(i: Int): String = values.getOrNull(i) ?: "".also { bad = true }

        fun number(i: Int): Int = values.getOrNull(i)?.toIntOrNull() ?: 0.also { bad = true }

        fun seconds(i: Int): Int = number(i).coerceIn(1, MAX_WAIT_S)

        fun phase(i: Int): OperationPhase? =
            OperationPhase.entries.firstOrNull { it.name == text(i) }.also {
                if (it ==
                    null
                ) {
                    bad = true
                }
            }

        fun state(i: Int): ServerState? =
            ServerState.entries.firstOrNull { it.name == text(i) }.also {
                if (it ==
                    null
                ) {
                    bad = true
                }
            }
    }

    companion object {
        /** The fictitious place of the tests (`OperationControllerTest`), one step north per position. */
        const val BASE_LAT = 45.2
        const val BASE_LON = 5.7
        private const val STEP_DEG = 0.0001
        private val WAYPOINT = byteArrayOf(0x45, 0x32, 0x45, 1, 2, 3)
        private const val MS = 1000L
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val POLL_MS = 50L
        private const val FAST_POLL_MS = 10L
        private const val SLOW_POLL_MS = 5_000L
        private const val GRACE_MS = 3_000L
        private const val ONLINE_S = 45
        private const val ENDED_S = 30
        private const val TRACE_WRITTEN_S = 5
        private const val MAX_WAIT_S = 600

        fun ok(vararg values: Pair<String, Any?>): String = ("ok " + words(*values)).trim()

        fun fail(
            why: String,
            vararg values: Pair<String, Any?>,
        ): String = ("fail $why " + words(*values)).trim()

        fun words(vararg values: Pair<String, Any?>): String = values.joinToString(" ") { (k, v) -> "$k=$v" }
    }
}
