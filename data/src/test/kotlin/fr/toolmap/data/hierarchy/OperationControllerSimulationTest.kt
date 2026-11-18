package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.MessageLog
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPresence
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import java.util.SplittableRandom
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [SecureRandom] that gives the same bytes for the same seed (tests only), as in the simulation of `:core`. */
internal class SeededSecureRandom(
    seed: Long,
) : SecureRandom() {
    private val inner = SplittableRandom(seed)

    override fun nextBytes(bytes: ByteArray) {
        var i = 0
        while (i < bytes.size) {
            var v = inner.nextLong()
            var k = 0
            while (k < Long.SIZE_BYTES && i < bytes.size) {
                bytes[i++] = v.toByte()
                v = v ushr 8
                k++
            }
        }
    }

    override fun setSeed(seed: Long) = Unit

    override fun setSeed(seed: ByteArray) = Unit

    override fun generateSeed(numBytes: Int): ByteArray = ByteArray(numBytes).also(::nextBytes)
}

/**
 * The smaller series of P39.10 (`docs/specs/group-hierarchy.md` § 21): whole [OperationController]s, each with its
 * storage on the disk and a software key store, killed and started again, on the simulated coordination server
 * ([ControllerServer]) and one virtual clock ([VirtualTime]); scenarios drawn from a seed. What the controller adds to
 * the node is checked: the storage read back after a restart, the threads kept (R2), everything wiped when the
 * operation is over for a phone, the trace of a dissolution and its announcement to a phone that was away (R1).
 */
class OperationControllerSimulationTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val problems = ArrayList<Problem>()
    private val previousSink = Problems.sink

    @BeforeTest
    fun catchProblems() {
        Problems.sink = Problems.Sink { problems.add(it) }
    }

    @AfterTest
    fun restoreSink() {
        Problems.sink = previousSink
    }

    private val config = RelayConfig.of("https://example.invalid", "key")

    private inner class World(
        val seed: Long,
    ) {
        val time = VirtualTime()
        val server = ControllerServer(time, seed)
        val r = SplittableRandom(seed)
        val phones = ArrayList<Phone>()
        val failures = ArrayList<String>()
        val trail = ArrayList<String>()

        fun note(text: String) {
            trail += "${(time.now - VirtualTime.START_MS) / 1000} s $text"
        }

        fun runFor(ms: Long) = time.runFor(ms)
    }

    private inner class Phone(
        val w: World,
        val index: Int,
    ) {
        val label = "C$index"
        val dir = File(temp.root, "${w.seed}-$index")
        val skew = w.r.nextLong(-60_000, 60_001)
        val vault = SoftwareKeyVault(random = SeededSecureRandom(w.seed * 1_000 + index))
        val net = w.server.PhoneNet()
        private val random = SeededSecureRandom(w.seed * 7_919 + index)
        private var executor = w.time.Executor()
        var controller = newController()
        var started = false
        var ended = false

        fun clock(): Long = w.time.now + skew

        private fun newController() =
            OperationController(
                OperationStorage(dir, vault, random = random, clock = ::clock),
                net,
                { config },
                ::clock,
                executor,
                random,
            )

        val view: OperationView get() = controller.view.value

        /** The app stops: cleanly (what is due is saved), or killed (the process dies: nothing more runs). */
        fun stop(killed: Boolean) {
            if (killed) {
                executor.shutdownNow()
                net.kill()
            } else {
                runBlocking { controller.close() }
                executor.shutdown()
            }
        }

        /** A new process on the same folder and the same key store, as after a restart of the phone. */
        fun start() {
            executor = w.time.Executor()
            controller = newController()
            val id = controller.ids().firstOrNull() ?: return
            runBlocking { controller.open(id) }
        }
    }

    private fun Phone.inOperation(): Boolean =
        view.phase == OperationPhase.WAITING || view.phase == OperationPhase.PLACED

    private fun World.check(
        cond: Boolean,
        what: () -> String,
    ) {
        if (!cond) failures += what()
    }

    /** One scenario: who comes in, who leads, who shares, who is cut off or restarted; then the calm and the checks. */
    private fun play(
        seed: Long,
        phones: Int,
        steps: Int,
    ): World {
        val w = World(seed)
        repeat(phones) { w.phones += Phone(w, it) }
        val creator = w.phones[0]
        runBlocking { creator.controller.create("Opération ${seed and 0xFF}", "Chef") }
        creator.started = true
        w.server.loss = 0.02
        w.server.maxDelayMs = 900
        for (i in 0 until steps) {
            val r = w.r
            val inside = w.phones.filter { it.started && !it.ended && it.inOperation() }
            val inviters = inside.filter { it.view.mayInvite }
            when (r.nextInt(16)) {
                0, 1 -> {
                    val newcomer = w.phones.firstOrNull { !it.started }
                    val inviter = inviters.randomOrNull(r)
                    val link = inviter?.let { runBlocking { it.controller.invitationLink() } }
                    if (newcomer != null && link != null) {
                        runBlocking { newcomer.controller.join(link, "Membre ${newcomer.index}") }
                        newcomer.started = true
                        w.note("${newcomer.label} asks to come in")
                    }
                }

                2, 3 -> {
                    val admin = inviters.firstOrNull { it.view.joinRequests.isNotEmpty() }
                    if (admin != null) {
                        val who = admin.view.joinRequests.first().member
                        w.note(
                            "${admin.label} lets ${java.lang.Long.toHexString(who).take(6)} in: ${runBlocking {
                                admin.controller.admit(who)
                            }}",
                        )
                    }
                }

                4 -> {
                    val admin = inviters.randomOrNull(r)
                    if (admin != null) {
                        val (g, refused) = runBlocking { admin.controller.createGroup("Groupe $i") }
                        w.note("${admin.label} creates a group: ${g != null} $refused")
                    }
                }

                5, 6 -> {
                    val admin = inviters.randomOrNull(r)
                    val s = admin?.view?.hierarchy
                    if (admin != null && s != null && s.groups.isNotEmpty()) {
                        val m = s.members.keys.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }.random(r)
                        val g = s.groups.keys.sortedWith { a, b -> java.lang.Long.compareUnsigned(a, b) }.random(r)
                        val refused =
                            runBlocking {
                                if (r.nextInt(3) ==
                                    0
                                ) {
                                    admin.controller.setPost(g, Post.LEADER, m)
                                } else {
                                    admin.controller.place(m, g)
                                }
                            }
                        w.note("${admin.label} places or names: $refused")
                    }
                }

                7 -> {
                    val admin = inside.firstOrNull { it.view.self == it.view.hierarchy?.creator }
                    val s = admin?.view?.hierarchy
                    if (admin != null && s != null) {
                        val m = s.members.keys.filter { it != admin.view.self }.randomOrNull(r)
                        if (m !=
                            null
                        ) {
                            w.note(
                                "${admin.label} names an administrator: ${runBlocking {
                                    admin.controller.addAdmin(m)
                                }}",
                            )
                        }
                    }
                }

                8, 9 -> {
                    val p = inside.randomOrNull(r)
                    val channel = p?.view?.targets?.messages?.randomOrNull(r)
                    if (p != null && channel != null) {
                        w.note(
                            "${p.label} writes in $channel: ${runBlocking {
                                p.controller.sendMessage(channel, "Message $i de ${p.label}")
                            }}",
                        )
                    }
                }

                10 -> {
                    for (p in inside) {
                        runBlocking {
                            p.controller.sendPosition(
                                PeerFix(
                                    45.0 + i * 1e-4,
                                    5.7,
                                    timeSec =
                                        p.clock() / 1000,
                                ),
                            )
                        }
                    }
                }

                11 -> {
                    val p = inside.randomOrNull(r)
                    val channel = p?.view?.targets?.waypoints?.randomOrNull(r)
                    if (p != null && channel != null) {
                        w.note(
                            "${p.label} puts a waypoint in $channel: ${runBlocking {
                                p.controller.putObject(ShareKind.WAYPOINT, channel, byteArrayOf(i.toByte())).first
                            }}",
                        )
                    }
                }

                12 -> {
                    val p = w.phones.filter { it.started && !it.ended }.randomOrNull(r)
                    if (p != null && p.net.up) {
                        p.net.up = false
                        val ms = 5_000L + r.nextLong(240_000)
                        w.note("${p.label} loses the network for ${ms / 1000} s")
                        w.time.post(ms) { p.net.up = true }
                    }
                }

                13 -> {
                    val p = w.phones.filter { it.started && !it.ended }.randomOrNull(r)
                    if (p != null) {
                        val killed = r.nextBoolean()
                        val before = p.view.threads
                        p.stop(killed)
                        p.start()
                        w.note("${p.label} restarts (${if (killed) "killed" else "stopped"})")
                        if (!killed && p.inOperation()) {
                            for ((c, list) in before) {
                                val after = p.view.threads[c].orEmpty()
                                w.check(
                                    after.map {
                                        it.text
                                    } ==
                                        list.map {
                                            it.text
                                        },
                                ) { "${p.label}: the thread of $c read back differs after a clean restart" }
                            }
                        }
                    }
                }

                14 -> {
                    val p = inside.filter { it.view.self != it.view.hierarchy?.creator }.randomOrNull(r)
                    if (p != null && r.nextInt(3) == 0) {
                        w.note("${p.label} leaves: ${runBlocking { p.controller.leave() }}")
                    }
                }

                else -> {
                    w.runFor(1_000L + r.nextLong(90_000))
                }
            }
            w.runFor(500L + r.nextLong(6_000))
            for (p in w.phones) if (p.view.phase == OperationPhase.ENDED) p.ended = true
        }
        calmAndCheck(w)
        return w
    }

    private fun calmAndCheck(w: World) {
        w.server.loss = 0.0
        w.server.maxDelayMs = 300
        for (p in w.phones) p.net.up = true
        w.runFor(12 * 60_000L)
        for (p in w.phones) if (p.view.phase == OperationPhase.ENDED) p.ended = true
        val inside = w.phones.filter { it.started && !it.ended && it.inOperation() }
        val ref = inside.maxByOrNull { it.view.hierarchy?.members?.size ?: 0 } ?: return
        val state = ref.view.hierarchy ?: return
        for (p in inside) {
            w.check(p.view.hierarchy == state || p.view.self !in state.members) {
                "${p.label}: another state than ${ref.label}"
            }
            for ((c, list) in p.view.threads) {
                w.check(list.size <= MessageLog.MAX_PER_THREAD) { "${p.label}: ${list.size} messages in $c" }
            }
            w.check(p.controller.ids().size == 1) { "${p.label}: ${p.controller.ids().size} operations stored" }
        }
        for (p in w.phones) {
            if (p.started && p.view.phase == OperationPhase.ENDED && p.view.ending == Ending.OUT) {
                w.check(p.controller.ids().isEmpty()) { "${p.label} is out and its operation is still stored" }
                w.check(p.vault.aliases(OperationStorage.ALIAS_PREFIX).isEmpty()) { "${p.label} is out and keeps keys" }
            }
        }
        // Who is in the operation as far as the others know learns it once the network is calm (P39.10, defect 1).
        for (p in w.phones) {
            if (!p.started || p.ended) continue
            val self = p.view.self
            if (self != null && self in state.members && p.view.phase == OperationPhase.ASKING) {
                w.failures += "${p.label} was let in and still asks"
            }
        }
        // The end: the creator dissolves while one member is away; it learns it from a trace when it is back (R1).
        val away = inside.firstOrNull { it !== ref && it.view.self != state.creator }
        val creator = inside.firstOrNull { it.view.self == state.creator } ?: return
        away?.net?.up = false
        // A cut shows at the phone's next second: before that, a frame on its way still reaches it.
        w.runFor(5_000)
        runBlocking { creator.controller.dissolve() }
        w.runFor(60_000)
        val keepers =
            w.phones.filter {
                it.started && it.view.phase == OperationPhase.ENDED &&
                    it.view.ending == Ending.DISSOLVED
            }
        for (p in keepers) {
            w.check(p.controller.ids().isEmpty()) { "${p.label}: dissolved and still stored" }
            w.check(runBlocking { p.controller.traces() }.size == 1) { "${p.label}: dissolved without a trace" }
        }
        if (away != null) {
            w.runFor(3 * 3_600_000L)
            away.net.up = true
            w.runFor(30_000)
            for (k in keepers) runBlocking { k.controller.announceDissolutions() }
            w.runFor(4 * 60_000L)
            w.check(away.view.phase == OperationPhase.ENDED && away.view.ending == Ending.DISSOLVED) {
                "${away.label}, away at the dissolution, still in the operation (${away.view.phase})"
            }
            w.check(away.view.endedByTrace) { "${away.label} did not learn it from a trace" }
        }
        val bugs = problems.filter { !it.expected }
        for (b in bugs) w.failures += "bug reported at ${b.where}: ${b.error}"
        for (o in w.server.oversized) w.failures += "frame over a limit: $o"
    }

    private fun <T> List<T>.randomOrNull(r: SplittableRandom): T? = if (isEmpty()) null else this[r.nextInt(size)]

    private fun <T> List<T>.random(r: SplittableRandom): T = this[r.nextInt(size)]

    private fun runSeeds(
        seeds: List<Long>,
        phones: Int,
        steps: Int,
    ) {
        val report = StringBuilder()
        for (seed in seeds) {
            problems.clear()
            val w = play(seed, phones, steps)
            if (w.failures.isEmpty()) continue
            report.appendLine("seed=$seed phones=$phones steps=$steps")
            for (f in w.failures) report.appendLine("  $f")
            for (t in w.trail) report.appendLine("    $t")
        }
        assertTrue(report.isEmpty(), report.toString())
    }

    @Test
    fun `whole controllers, restarted and cut off, agree and keep their storage right`() =
        runSeeds(listOf(1L, 2L, 3L), phones = 5, steps = 60)

    @Test
    fun `whole controllers, a larger operation`() = runSeeds(listOf(4L), phones = 8, steps = 90)

    /**
     * Presence (reported by the session of the screens, P39.8, spec § 19 of its branch): the relay closes the link of
     * a phone whose heartbeat stopped (5 min in an operation since P39.15, evaluated every 5 s); the others must show it
     * offline within a bounded time, and online again when it is back, without waiting for another change of the
     * operation.
     */
    @Test
    fun `a phone cut off shows offline on the others within a bounded time, then online again`() {
        val w = World(77)
        repeat(3) { w.phones += Phone(w, it) }
        val (a, b, c) = w.phones
        runBlocking { a.controller.create("Opération Présence", "Alice") }
        a.started = true
        for (p in listOf(b, c)) {
            val link = assertNotNull(runBlocking { a.controller.invitationLink() })
            runBlocking { p.controller.join(link, "Membre ${p.index}") }
            p.started = true
            w.runFor(30_000)
            val request = a.view.joinRequests.firstOrNull { it.member == p.view.self }
            assertNotNull(request, "${p.label} asks to come in")
            assertNull(runBlocking { a.controller.admit(request.member) })
            w.runFor(30_000)
        }
        val cId = assertNotNull(c.view.self)
        assertTrue(cId in a.view.online && cId in b.view.online, "C online on A and B")
        // One heartbeat a minute (P39.15) keeps the link: no phone goes offline between two of them.
        w.runFor(STEADY_MS)
        assertTrue(cId in a.view.online && cId in b.view.online, "C still online on A and B after 4 min")
        c.net.up = false
        w.runFor(PRESENCE_BOUND_MS)
        assertFalse(cId in a.view.online, "C cut off for ${PRESENCE_BOUND_MS / 1000} s: offline on A")
        assertFalse(cId in b.view.online, "C cut off for ${PRESENCE_BOUND_MS / 1000} s: offline on B")
        assertFalse(a.view.members.first { it.id == cId }.online)
        c.net.up = true
        w.runFor(BACK_BOUND_MS)
        assertTrue(cId in a.view.online, "C back: online on A")
        assertTrue(a.view.members.first { it.id == cId }.online)
        assertTrue(problems.none { !it.expected }, "bugs reported: $problems")
    }

    companion object {
        /** 5 min without a heartbeat (P39.15), an evaluation every 5 s, and the delays of the network. */
        val PRESENCE_BOUND_MS = RelayPresence.OPERATION.closeAfterMs + 25_000L

        /** The next heartbeat (one a minute in an operation), an evaluation every 5 s, and the delays of the network. */
        val BACK_BOUND_MS = RelayPresence.OPERATION.heartbeatS * 1_000L + 25_000L

        const val STEADY_MS = 240_000L
    }
}
