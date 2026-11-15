package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.NameCard
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.ObjectRef
import fr.toolmap.core.hierarchy.OperationInvite
import fr.toolmap.core.hierarchy.OperationInviteCodec
import fr.toolmap.core.hierarchy.OperationText
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.Role
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.p2p.PeerFix
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.problem.Problem
import fr.toolmap.core.problem.Problems
import java.util.SplittableRandom

/** What a step of a scenario does; the weights say how often the generator draws it. */
enum class StepKind(
    val weight: Double,
    /** Weight in the first third of a scenario, while the operation fills up. */
    val early: Double = weight,
    /** Weight in the last quarter. */
    val late: Double = weight,
) {
    JOIN(5.0, early = 14.0, late = 2.0),
    ADMIT(7.0, early = 16.0),
    DECLINE(0.3),
    GROUP_NEW(3.0, early = 6.0),
    GROUP_RENAME(0.8),
    GROUP_DELETE(0.8, early = 0.2),
    ASSIGN(9.0, early = 12.0),
    UNASSIGN(1.5),
    POST_SET(5.0),
    POST_CLEAR(0.8),
    ADMIN_ADD(2.0, early = 3.0),
    ADMIN_REMOVE(0.8),
    REMOVE(1.0, early = 0.3),
    LEAVE(0.8, early = 0.2),
    HAND_OVER(0.4, early = 0.0),
    DISSOLVE(0.0, early = 0.0, late = 0.6),
    RENEW(0.4),
    POSITIONS(3.0),
    MESSAGE(5.0),
    ALERT(1.5),
    LIFT(0.8),
    OBJECT_PUT(3.5),
    OBJECT_REMOVE(1.2),
    CUT(3.5, early = 1.0),
    ABSENT(0.8, early = 0.1),
    RESTART(1.2, early = 0.4),
    CRASH_AFTER(0.8, early = 0.2),
    LOSE(0.15, early = 0.0),
    WAIT(3.0),
    KEEPER(0.6),

    /** A burst of messages in one thread: long series reach the 500 kept per thread (R2). */
    CHATTER(0.25, early = 0.0),
}

/** One step: what it does, and the seed every choice it makes and the network during it are drawn from. */
data class Step(
    val kind: StepKind,
    val seed: Long,
) {
    override fun toString() = "$kind/${java.lang.Long.toHexString(seed)}"
}

/** The size and the roughness of a scenario. */
data class SimSetup(
    val phones: Int = 10,
    val steps: Int = 120,
    val faults: Faults = Faults.ROUGH,
    val positions: Boolean = true,
    val label: String = "",
) {
    override fun toString() =
        "phones=$phones steps=$steps faults=${if (faults == Faults.CALM) {
            "calm"
        } else if (faults.reorder) {
            "rough"
        } else {
            "rough-ordered"
        }}$label"
}

/** A broken invariant: [category] groups the same failure for the shrinker. */
class Failure(
    val category: String,
    val detail: String,
) {
    override fun toString() = "[$category] $detail"
}

/** The measures of one run. */
class SimStats {
    var virtualMs = 0L
    var wallMs = 0L
    var cpuNs = 0L
    var slowestCallNs = 0L
    var opsIssued = 0
    var opsRefusedLocally = 0
    var sharesSent = 0
    var members = 0
    var survivors = 0
    var dissolved = false
    var probes = 0
    var expectedProblems = LinkedHashMap<String, Int>()
    var problemsByPhone = LinkedHashMap<String, Int>()
    var strayed = 0

    /** Phones in one of the cases left to the user (spec § 21): how many, by case. */
    var known = LinkedHashMap<String, Int>()
}

class SimResult(
    val seed: Long,
    val setup: SimSetup,
    val steps: List<Step>,
    val failures: List<Failure>,
    val trail: List<String>,
    val stats: SimStats,
    /** One line per phone at the end: where it stands. */
    val phones: List<String> = emptyList(),
) {
    val ok: Boolean get() = failures.isEmpty()

    fun describe(): String =
        buildString {
            appendLine("seed=0x${java.lang.Long.toHexString(seed)} $setup, ${steps.size} steps")
            for (f in failures.take(12)) appendLine("  $f")
            if (failures.size > 12) appendLine("  … ${failures.size - 12} more")
        }
}

/**
 * One scenario run in a [World] (P39.10): the steps are played one after the other, each with its own seed, on phones
 * whose network comes and goes; then the network calms down, every phone still in the operation comes back, and the
 * invariants are checked ([SimChecks]); then every phone shares once more in every place it may, and who heard what
 * is checked against [SimOracle].
 */
class Simulation(
    val seed: Long,
    val setup: SimSetup,
    val steps: List<Step>,
) {
    val world = World(seed)
    private val server = RelayConfig.of("https://example.invalid", "key")
    val phones: List<SimPhone>
    val byId = LinkedHashMap<Long, SimPhone>()
    val keepers = ArrayList<Keeper>()
    val trail = ArrayList<String>()
    val stats = SimStats()
    val problems = ArrayList<Problem>()
    val problemPhones = ArrayList<String>()
    val failures = ArrayList<Failure>()
    var problemsSeen = 0

    /** Everything private the phones put into the operation, for the checks of what a trace may hold (R1). */
    val names = LinkedHashSet<String>()
    val salts = ArrayList<ByteArray>()
    val texts = LinkedHashSet<String>()
    val payloads = ArrayList<ByteArray>()
    val invitations = ArrayList<OperationInvite>()

    /** Phones that left or were taken out, with when (the checks of the invitation key renewed after a departure). */
    val departedAt = LinkedHashMap<Long, Long>()
    private val lastFixSec = HashMap<Long, Long>()
    private var stepIndex = 0
    private var r = SplittableRandom(seed)

    init {
        val perm = (0 until KeyPool.SIZE / 2).toMutableList()
        val shuffle = SplittableRandom(seed xor 0x6B65_7973L)
        for (i in perm.indices.reversed()) {
            val j = shuffle.nextInt(i + 1)
            perm[i] = perm[j].also { perm[j] = perm[i] }
        }
        phones =
            List(setup.phones) { i ->
                val skew =
                    when (val d = shuffle.nextDouble()) {
                        in 0.0..0.70 -> {
                            shuffle.nextLong(-90_000, 90_001)
                        }

                        in 0.70..0.95 -> {
                            shuffle.nextLong(-20 * 60_000L, 20 * 60_000L + 1)
                        }

                        else -> {
                            if (d >
                                0.975
                            ) {
                                shuffle.nextLong(3_600_000, 7_200_000)
                            } else {
                                -shuffle.nextLong(3_600_000, 7_200_000)
                            }
                        }
                    }
                SimPhone(world, i, "P$i", KeyPool.keys(perm[i], seed * 31 + i), skew, seed * 131 + i, server)
            }
        for (p in phones) byId[p.id] = p
        for (p in phones) {
            p.onEpoch = { phone, epoch, announcer ->
                if (epoch >
                    1
                ) {
                    note(
                        "${phone.label} at epoch $epoch (announced by ${byId[announcer]?.label ?: SimOracle.hex(
                            announcer,
                        )})",
                    )
                }
            }
        }
    }

    private fun note(text: String) {
        trail += "#$stepIndex ${minuteOf(world.now)} $text"
    }

    private fun minuteOf(t: Long): String {
        val s = (t - World.START_MS) / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun run(): SimResult {
        val wall = System.currentTimeMillis()
        SimSink.install()
        SimSink.current.set(this)
        try {
            world.faults = setup.faults
            start()
            for ((i, step) in steps.withIndex()) {
                stepIndex = i
                world.reseed(step.seed)
                r = SplittableRandom(step.seed)
                play(step)
                world.runFor(world.between(200, 8_000))
                // The tables of § 4 in the states the phones go through, not only in the last one.
                if (i % 10 == 9) {
                    members().let { m -> if (m.isNotEmpty()) m[r.nextInt(m.size)] else null }?.let { p ->
                        for (d in SimOracle.differences(p.n.state).take(3)) {
                            failures +=
                                Failure("oracle", "${p.label} at step $i: $d")
                        }
                    }
                    if (failures.isNotEmpty()) break
                }
                if (System.currentTimeMillis() - wall > MAX_WALL_MS) {
                    failures += Failure("time", "over ${MAX_WALL_MS / 1000} s of wall time at step $i")
                    break
                }
            }
            stepIndex = steps.size
            if (failures.isEmpty()) SimChecks.afterCalm(this)
        } catch (e: RuntimeException) {
            failures +=
                Failure(
                    "crash:${e.javaClass.simpleName}",
                    "${e.message} at step $stepIndex\n${e.stackTraceToString().lines().take(14).joinToString("\n")}",
                )
        } catch (e: AssertionError) {
            failures += Failure("crash:assert", "${e.message} at step $stepIndex")
        } finally {
            SimSink.current.remove()
        }
        stats.virtualMs = world.now - World.START_MS
        stats.wallMs = System.currentTimeMillis() - wall
        stats.cpuNs = phones.sumOf { it.cpuNs }
        stats.slowestCallNs = phones.maxOf { it.slowestCallNs }
        return SimResult(seed, setup, steps, failures.toList(), trail.toList(), stats, phoneLines())
    }

    private fun phoneLines(): List<String> {
        val ref = SimChecks.reference(this)
        val truth = ref?.n?.state
        val head =
            if (ref == null || truth == null) {
                emptyList()
            } else {
                val refused = ref.n.replica.history().filter { it.rejection != null }
                listOf(
                    "reference ${ref.label}: creator ${truth.creator?.let { label(truth, it) }}, admins " +
                        "${truth.admins.map {
                            label(truth, it)
                        }}, members ${truth.members.size}, epoch ${ref.n.epoch}, " +
                        "refused ${refused.size}: " +
                        refused.takeLast(6).joinToString {
                            "${byId[it.op.author]?.label}#${it.op.seq} " +
                                "${it.op.change::class.simpleName} ${it.rejection}"
                        },
                )
            }
        return head +
            phones.map { p ->
                val node = p.node
                buildString {
                    append("${p.label} ${SimOracle.hex(p.id)}")
                    append(if (!p.joined) " never joined" else "")
                    if (p.lost) append(" LOST")
                    if (p.ended != null) append(" ended=${p.ended}${if (p.endedByTrace) " by trace" else ""}")
                    if (node != null && p.ended == null) {
                        val s = node.state
                        append(" app=${if (p.appUp) "up" else "down"} net=${if (p.networkUp) "up" else "down"}")
                        append(
                            " ${if (node.isMember) "member" else "asking"} epoch=${node.epoch} " +
                                "ops=${node.replica.summary().counts.values.sum()}",
                        )
                        if (truth !=
                            null
                        ) {
                            append(" ${if (p.id in truth.members) label(truth, p.id) else "NOT in the reference"}")
                        }
                        if (s.isWaiting(p.id)) append(" waits")
                    }
                    if (p.trace != null) append(" keeps a trace")
                }
            }
    }

    // ---- the start

    private fun card(name: String): NameCard {
        names += name
        return Names.hide(name, SeededRandom(seed xor name.hashCode().toLong())).also { salts += it.salt }
    }

    private fun start() {
        val creator = phones[0]
        creator.create(card("Opération ${seed and 0xFFF}"), card(personName(0)))
        note("P0 creates the operation")
        world.runFor(1_000)
    }

    private fun personName(i: Int): String = FIRST[i % FIRST.size] + (if (i >= FIRST.size) " ${i / FIRST.size}" else "")

    // ---- helpers

    fun running(): List<SimPhone> = phones.filter { it.running }

    fun members(): List<SimPhone> = running().filter { it.isMember }

    private fun <T> pick(list: List<T>): T? = if (list.isEmpty()) null else list[r.nextInt(list.size)]

    private fun chance(p: Double) = r.nextDouble() < p

    private fun nonZero(): Long {
        var v = 0L
        while (v == 0L) v = r.nextLong()
        return v
    }

    private fun label(
        s: HierarchyState?,
        id: Long,
    ): String {
        val p = byId[id] ?: return SimOracle.hex(id)
        val roles = s?.rolesOf(id).orEmpty()
        val tag =
            buildList {
                if (Role.CREATOR in roles) add("creator")
                if (Role.ADMIN in roles) add("admin")
                if (Role.ENSEMBLE_LEADER in roles) add("chief")
                if (Role.ENSEMBLE_DEPUTY in roles) add("chief-deputy")
                if (Role.GROUP_LEADER in roles) add("leader")
                if (Role.GROUP_DEPUTY in roles) add("deputy")
                if (Role.WAITING in roles) add("waiting")
            }
        return if (tag.isEmpty()) p.label else "${p.label}(${tag.joinToString(",")})"
    }

    private fun groupName(
        @Suppress("UNUSED_PARAMETER") s: HierarchyState?,
        g: Long,
    ): String = "G" + SimOracle.hex(g).take(3)

    /** A phone that, as far as it knows, may change the hierarchy (creator, administrator); sometimes anyone. */
    private fun commander(): SimPhone? {
        val all = members()
        val allowed = all.filter { p -> p.n.state.let { p.id == it.creator || p.id in it.admins } }
        return if (allowed.isEmpty() || chance(0.08)) pick(all) else pick(allowed)
    }

    private fun issue(
        p: SimPhone,
        change: Change,
        what: String,
    ): Boolean {
        val before = p.n.state
        val result = p.call { it.issue(change) } ?: return false
        return when (result) {
            is IssueResult.Issued -> {
                stats.opsIssued++
                note("${label(before, p.id)} $what")
                true
            }

            is IssueResult.Refused -> {
                stats.opsRefusedLocally++
                note("${label(before, p.id)} $what: refused here (${result.reason})")
                false
            }
        }
    }

    private fun fixFor(p: SimPhone): PeerFix {
        val t = maxOf((lastFixSec[p.id] ?: 0L) + 1, p.clock() / 1000)
        lastFixSec[p.id] = t
        return PeerFix(45.0 + r.nextDouble() * 0.5, 5.5 + r.nextDouble() * 0.5, timeSec = t).also {
            payloads += fr.toolmap.core.hierarchy.OperationPayloads.position(it)
        }
    }

    fun sendPosition(p: SimPhone): ShareOutcome? = p.call { it.sendPosition(fixFor(p)) }?.also { stats.sharesSent++ }

    fun sendMessage(
        p: SimPhone,
        channel: Channel,
        text: String,
    ): ShareOutcome? {
        texts += text
        val m = OperationText(nonZero(), p.clock(), text)
        val outcome = p.call { it.sendMessage(channel, m) } ?: return null
        if (outcome == ShareOutcome.SENT || outcome == ShareOutcome.QUEUED) p.keepOwn(channel, m)
        stats.sharesSent++
        return outcome
    }

    fun sendAlert(
        p: SimPhone,
        text: String,
    ): ShareOutcome? {
        texts += text
        stats.sharesSent++
        return p.call { it.sendAlert(text, if (chance(0.5)) fixFor(p) else null) }
    }

    fun putObject(
        p: SimPhone,
        kind: ShareKind,
        channel: Channel,
        objectId: Long,
        body: ByteArray,
    ): ShareOutcome? {
        payloads += body
        stats.sharesSent++
        return p.call { it.putObject(kind, channel, objectId, body) }
    }

    /** A phone that ended keeps, if it was a member at the dissolution, a trace: it becomes a keeper. */
    fun collectKeepers() {
        for (p in phones) {
            val t = p.trace ?: continue
            if (keepers.none { it.owner === p }) {
                keepers += Keeper(world, t, p)
                SimChecks.checkTrace(this, p, t)
            }
        }
    }

    // ---- the steps

    private fun play(step: Step) {
        collectKeepers()
        when (step.kind) {
            StepKind.JOIN -> join()
            StepKind.ADMIT -> admit(decline = false)
            StepKind.DECLINE -> admit(decline = true)
            StepKind.GROUP_NEW -> groupNew()
            StepKind.GROUP_RENAME -> groupRename()
            StepKind.GROUP_DELETE -> groupDelete()
            StepKind.ASSIGN -> assign()
            StepKind.UNASSIGN -> unassign()
            StepKind.POST_SET -> postSet()
            StepKind.POST_CLEAR -> postClear()
            StepKind.ADMIN_ADD -> adminAdd()
            StepKind.ADMIN_REMOVE -> adminRemove()
            StepKind.REMOVE -> remove()
            StepKind.LEAVE -> leave()
            StepKind.HAND_OVER -> handOver()
            StepKind.DISSOLVE -> dissolve()
            StepKind.RENEW -> renew()
            StepKind.POSITIONS -> positions()
            StepKind.MESSAGE -> message()
            StepKind.ALERT -> alert()
            StepKind.LIFT -> lift()
            StepKind.OBJECT_PUT -> objectPut()
            StepKind.OBJECT_REMOVE -> objectRemove()
            StepKind.CUT -> cut()
            StepKind.ABSENT -> absent()
            StepKind.RESTART -> restart()
            StepKind.CRASH_AFTER -> crashAfter()
            StepKind.LOSE -> lose()
            StepKind.WAIT -> waitSome()
            StepKind.KEEPER -> keeperWindow()
            StepKind.CHATTER -> chatter()
        }
    }

    private fun join() {
        val joiner = phones.firstOrNull { !it.joined && !it.lost && it.index > 0 } ?: return
        val inviter = pick(running().filter { it.n.mayInvite() }) ?: return
        val fresh = inviter.call { it.invitation(inviter.clock() / 1000) } ?: return
        invitations += fresh
        // Sometimes an invitation given earlier, before a renewal: it no longer lets anyone in.
        val invite = if (invitations.size > 1 && chance(0.1)) invitations[r.nextInt(invitations.size)] else fresh
        val stale = OperationInviteCodec.staleness(invite, joiner.clock() / 1000)
        if (stale != null) {
            note("${joiner.label} cannot use the invitation of ${inviter.label}: $stale")
            return
        }
        joiner.join(invite, card(personName(joiner.index)))
        note("${joiner.label} asks to come in (invitation of ${inviter.label}, epoch ${invite.epoch})")
    }

    private fun admit(decline: Boolean) {
        val admin = pick(running().filter { it.n.pendingJoins.isNotEmpty() }) ?: return
        val pending = admin.n.pendingJoins
        val who = pending[r.nextInt(pending.size)].member
        if (decline) {
            admin.call { it.decline(who) }
            note("${label(admin.n.state, admin.id)} declines ${label(null, who)}")
            return
        }
        val before = admin.n.state
        when (val result = admin.call { it.admit(who) }) {
            is IssueResult.Issued -> {
                stats.opsIssued++
                note("${label(before, admin.id)} lets ${label(null, who)} in")
            }

            is IssueResult.Refused -> {
                stats.opsRefusedLocally++
                note("${label(before, admin.id)} lets ${label(null, who)} in: refused here (${result.reason})")
            }

            null -> {
                Unit
            }
        }
    }

    private fun groupNew() {
        val p = commander() ?: return
        val name = "Groupe ${names.size}"
        val c = card(name)
        var g = nonZero()
        val s = p.n.state
        while (g in s.groups || g in s.deletedGroups) g = nonZero()
        if (issue(p, Change.CreateGroup(g, c.hidden), "creates ${groupName(s, g)}")) {
            p.call { it.learnAndShare(c) }
        }
    }

    private fun groupRename() {
        val p = commander() ?: return
        val s = p.n.state
        val g = pick(s.groups.keys.sortedWith(UNSIGNED)) ?: return
        val c = card("Équipe ${names.size}")
        if (issue(p, Change.RenameGroup(g, c.hidden), "renames ${groupName(s, g)}")) p.call { it.learnAndShare(c) }
    }

    private fun groupDelete() {
        val p = commander() ?: return
        val s = p.n.state
        val g = pick(s.groups.keys.sortedWith(UNSIGNED)) ?: return
        issue(p, Change.DeleteGroup(g), "deletes ${groupName(s, g)}")
    }

    private fun assign() {
        val p = commander() ?: return
        val s = p.n.state
        val g = pick(s.groups.keys.sortedWith(UNSIGNED)) ?: return
        val waiting = s.waiting().map { it.id }
        val everyone = s.members.keys.sortedWith(UNSIGNED)
        val m = (if (waiting.isNotEmpty() && chance(0.6)) pick(waiting) else pick(everyone)) ?: return
        issue(p, Change.AssignMember(m, g), "places ${label(s, m)} in ${groupName(s, g)}")
    }

    private fun unassign() {
        val p = commander() ?: return
        val s = p.n.state
        val m = pick(s.members.values.filter { it.group != null }.map { it.id }.sortedWith(UNSIGNED)) ?: return
        issue(p, Change.UnassignMember(m), "puts ${label(s, m)} back to waiting")
    }

    private fun postSet() {
        val p = commander() ?: return
        val s = p.n.state
        val post = if (chance(0.6)) Post.LEADER else Post.DEPUTY
        if (s.groups.isEmpty() || chance(0.3)) {
            val m = pick(s.members.keys.sortedWith(UNSIGNED)) ?: return
            issue(p, Change.SetPost(HierarchyState.ROOT, post, m), "names ${label(s, m)} $post of the operation")
        } else {
            val g = pick(s.groups.keys.sortedWith(UNSIGNED)) ?: return
            val inside = s.membersOf(g).map { it.id }
            val m =
                (if (inside.isEmpty() || chance(0.1)) pick(s.members.keys.sortedWith(UNSIGNED)) else pick(inside))
                    ?: return
            issue(p, Change.SetPost(g, post, m), "names ${label(s, m)} $post of ${groupName(s, g)}")
        }
    }

    private fun postClear() {
        val p = commander() ?: return
        val s = p.n.state
        val held =
            buildList {
                if (s.leader != null) add(HierarchyState.ROOT to Post.LEADER)
                if (s.deputy != null) add(HierarchyState.ROOT to Post.DEPUTY)
                for (g in s.groups.values.sortedWith { a, b -> UNSIGNED.compare(a.id, b.id) }) {
                    if (g.leader != null) add(g.id to Post.LEADER)
                    if (g.deputy != null) add(g.id to Post.DEPUTY)
                }
            }
        val (unit, post) = pick(held) ?: return
        issue(
            p,
            Change.ClearPost(unit, post),
            "clears the post $post of ${if (unit == HierarchyState.ROOT) "the operation" else groupName(s, unit)}",
        )
    }

    private fun creatorPhone(): SimPhone? {
        val c = members().filter { it.n.state.creator == it.id }
        return pick(c) ?: if (chance(0.1)) pick(members()) else null
    }

    private fun adminAdd() {
        val p = creatorPhone() ?: return
        val s = p.n.state
        val m = pick(s.members.keys.filter { it !in s.admins && it != s.creator }.sortedWith(UNSIGNED)) ?: return
        issue(p, Change.AddAdmin(m), "names ${label(s, m)} administrator")
    }

    private fun adminRemove() {
        val p = creatorPhone() ?: return
        val s = p.n.state
        val m = pick(s.admins.sortedWith(UNSIGNED)) ?: return
        issue(p, Change.RemoveAdmin(m), "takes the role of administrator from ${label(s, m)}")
    }

    private fun remove() {
        val p = creatorPhone() ?: return
        val s = p.n.state
        val m = pick(s.members.keys.filter { it != p.id }.sortedWith(UNSIGNED)) ?: return
        if (issue(p, Change.RemoveMember(m), "takes ${label(s, m)} out")) departedAt.putIfAbsent(m, world.now)
    }

    private fun leave() {
        val p = pick(members().filter { it.n.state.creator != it.id }) ?: return
        if (issue(p, Change.RemoveMember(p.id), "leaves")) departedAt.putIfAbsent(p.id, world.now)
    }

    private fun handOver() {
        val p = members().firstOrNull { it.n.state.creator == it.id } ?: return
        val s = p.n.state
        val successor = pick(s.admins.sortedWith(UNSIGNED)) ?: return
        if (issue(p, Change.TransferCreator(successor), "hands the operation over to ${label(s, successor)}") &&
            issue(p, Change.RemoveMember(p.id), "leaves after handing over")
        ) {
            departedAt.putIfAbsent(p.id, world.now)
        }
    }

    private fun dissolve() {
        val p = members().firstOrNull { it.n.state.creator == it.id } ?: return
        issue(p, Change.Dissolve, "dissolves the operation")
    }

    private fun renew() {
        val p = pick(running().filter { it.n.mayInvite() }) ?: return
        if (p.call { it.renewInvitation() } ==
            true
        ) {
            note("${label(p.n.state, p.id)} renews the invitation (epoch ${p.n.epoch})")
        }
    }

    private fun positions() {
        if (!setup.positions) return
        val who = members().filter { chance(0.7) }
        for (p in who) sendPosition(p)
        if (who.isNotEmpty()) note("${who.size} phones send their position")
    }

    private fun message() {
        val p = pick(members()) ?: return
        val s = p.n.state
        val channel = pick(s.shareChannels(p.id, ShareKind.MESSAGE)) ?: return
        val text = "Message $stepIndex de ${p.label}"
        val outcome = sendMessage(p, channel, text)
        note("${label(s, p.id)} writes in $channel: $outcome")
    }

    private fun alert() {
        val p = pick(members().filter { it.n.state.alertChannel(it.id) != null }) ?: return
        val s = p.n.state
        val outcome = sendAlert(p, "Alerte $stepIndex de ${p.label}")
        note("${label(s, p.id)} alerts ${s.alertChannel(p.id)}: $outcome")
    }

    private fun lift() {
        val p = pick(members()) ?: return
        val outcome = p.call { it.liftAlert() }
        if (outcome != ShareOutcome.NOT_ALLOWED) note("${label(p.n.state, p.id)} lifts its alert: $outcome")
    }

    private fun objectPut() {
        val p = pick(members()) ?: return
        val s = p.n.state
        val kind = if (chance(0.5)) ShareKind.WAYPOINT else ShareKind.SKETCH
        val channel = pick(s.shareChannels(p.id, kind)) ?: return
        val own = p.n.objects.shown(channel).filter { it.author == p.id && it.kind == kind }
        val id = if (own.isNotEmpty() && chance(0.4)) own[r.nextInt(own.size)].objectId else nonZero()
        val body = ByteArray(16 + r.nextInt(240)).also { b -> for (i in b.indices) b[i] = r.nextInt(256).toByte() }
        val outcome = putObject(p, kind, channel, id, body)
        note("${label(s, p.id)} puts a $kind in $channel: $outcome")
    }

    private fun objectRemove() {
        val p = pick(members()) ?: return
        val s = p.n.state
        val removable = p.n.objects.shown(s, p.id).filter { s.mayShare(p.id, it.kind, it.channel) }
        val o = pick(removable) ?: return
        val outcome = p.call { it.removeObject(ObjectRef(o.channel, o.author, o.objectId)) }
        note("${label(s, p.id)} takes away a ${o.kind} of ${label(s, o.author)} in ${o.channel}: $outcome")
    }

    private fun cut() {
        val p = pick(running().filter { it.networkUp }) ?: return
        val ms =
            when (r.nextInt(10)) {
                in 0..5 -> 5_000L + r.nextLong(85_000)
                in 6..8 -> 120_000L + r.nextLong(18 * 60_000L)
                else -> 30 * 60_000L + r.nextLong(90 * 60_000L)
            }
        p.networkOff()
        note("${p.label} loses the network for ${ms / 1000} s")
        world.after(ms) { if (!p.lost) p.networkOn() }
    }

    private fun absent() {
        if (members().size < 3) return
        val p = pick(running().filter { it.index != 0 || chance(0.3) }) ?: return
        val ms =
            if (chance(0.7)) {
                3_600_000L + r.nextLong(5 * 3_600_000L)
            } else {
                12 * 3_600_000L +
                    r.nextLong(48 * 3_600_000L)
            }
        val killed = chance(0.5)
        p.stopApp(killed)
        note("${p.label} is off for ${ms / 60_000} min (${if (killed) "killed" else "stopped"})")
        world.after(ms) { if (!p.lost) p.startApp() }
    }

    private fun restart() {
        val p = pick(running()) ?: return
        val killed = chance(0.5)
        p.stopApp(killed)
        val ms = 1_000L + r.nextLong(30_000)
        note("${p.label} restarts (${if (killed) "killed" else "stopped"})")
        world.after(ms) { if (!p.lost) p.startApp() }
    }

    /** A change written, then the app killed before its storage was written: the window `OperationController` has. */
    private fun crashAfter() {
        val p = commander() ?: return
        val s = p.n.state
        val m = pick(s.members.keys.sortedWith(UNSIGNED)) ?: return
        val g = pick(s.groups.keys.sortedWith(UNSIGNED))
        val change =
            if (g != null &&
                chance(0.7)
            ) {
                Change.AssignMember(m, g)
            } else {
                Change.SetPost(HierarchyState.ROOT, Post.DEPUTY, m)
            }
        issue(p, change, "writes $change, then is killed before saving")
        p.stopApp(killed = true)
        world.after(5_000L + r.nextLong(55_000)) { if (!p.lost) p.startApp() }
    }

    private fun lose() {
        if (members().size < 4) return
        val p = pick(running().filter { it.index != 0 || chance(0.2) }) ?: return
        p.stopApp(killed = true)
        p.lost = true
        note("${p.label} is lost for good")
    }

    private fun waitSome() {
        val ms =
            when (r.nextInt(10)) {
                in 0..5 -> 1_000L + r.nextLong(20_000)
                in 6..8 -> 60_000L + r.nextLong(240_000)
                else -> 10 * 60_000L + r.nextLong(50 * 60_000L)
            }
        world.runFor(ms)
    }

    private fun chatter() {
        val p = pick(members()) ?: return
        val channel = pick(p.n.state.shareChannels(p.id, ShareKind.MESSAGE)) ?: return
        val n = 40 + r.nextInt(120)
        repeat(n) { k ->
            sendMessage(p, channel, "Bavardage $stepIndex.$k de ${p.label}")
            if (k % 10 == 9) world.runFor(1_000)
        }
        note("${p.label} sends $n messages in $channel")
    }

    private fun keeperWindow() {
        val k = pick(keepers.filter { !it.owner.lost }) ?: return
        k.open()
        note("the trace of ${k.owner.label} listens for two minutes")
    }

    companion object {
        const val MAX_WALL_MS = 180_000L
        val UNSIGNED = Comparator<Long> { a, b -> java.lang.Long.compareUnsigned(a, b) }
        private val FIRST =
            listOf(
                "Alice",
                "Bruno",
                "Chloé",
                "David",
                "Émilie",
                "Fabien",
                "Gaëlle",
                "Hugo",
                "Inès",
                "Jules",
                "Karim",
                "Léa",
                "Marc",
                "Nadia",
                "Olivier",
                "Pauline",
                "Quentin",
                "Rose",
                "Samuel",
                "Thaïs",
                "Ugo",
                "Valérie",
                "William",
                "Xavier",
                "Yasmine",
                "Zoé",
                "Anaïs",
                "Benoît",
                "Céline",
                "Damien",
            )
    }
}

/**
 * Where the problems told while a scenario runs go: to the simulation of the thread that tells them, so that several
 * scenarios can run at once (the long series); elsewhere, to the sink there was before.
 */
object SimSink : Problems.Sink {
    val current = ThreadLocal<Simulation?>()
    private var before: Problems.Sink? = null

    @Synchronized
    fun install() {
        if (Problems.sink === this) return
        before = Problems.sink
        Problems.sink = this
    }

    override fun take(problem: Problem) {
        val sim = current.get()
        if (sim == null) {
            before?.take(problem)
            return
        }
        if (!sim.world.muted) {
            sim.problems += problem
            sim.problemPhones += sim.world.current ?: "-"
        }
    }
}

/** Draws scenarios from a seed, runs them, and shrinks a failing one to the shortest that still fails the same way. */
object Scenarios {
    fun generate(
        seed: Long,
        setup: SimSetup,
    ): List<Step> {
        val r = SplittableRandom(seed)
        return List(setup.steps) { i ->
            val phase =
                when {
                    i < setup.steps / 3 -> 0
                    i >= setup.steps * 3 / 4 -> 2
                    else -> 1
                }
            val weights =
                StepKind.entries.map { k ->
                    if (phase ==
                        0
                    ) {
                        k.early
                    } else if (phase == 2) {
                        k.late
                    } else {
                        k.weight
                    }
                }
            var x = r.nextDouble() * weights.sum()
            var kind = StepKind.WAIT
            for ((k, w) in StepKind.entries.zip(weights)) {
                if (x < w) {
                    kind = k
                    break
                }
                x -= w
            }
            if (!setup.positions && kind == StepKind.POSITIONS) kind = StepKind.WAIT
            Step(kind, r.nextLong())
        }
    }

    fun run(
        seed: Long,
        setup: SimSetup,
        steps: List<Step> = generate(seed, setup),
    ): SimResult = Simulation(seed, setup, steps).run()

    /**
     * The shortest list of steps found that still fails with [category] (ddmin over the steps, each step keeping its
     * own seed): at most [budget] runs.
     */
    fun shrink(
        failed: SimResult,
        category: String = failed.failures.first().category,
        budget: Int = 160,
    ): SimResult {
        var best = failed
        var current = failed.steps
        var n = 2
        var runs = 0
        while (current.size >= 2 && runs < budget) {
            val chunk = (current.size + n - 1) / n
            var reduced = false
            var start = 0
            while (start < current.size && runs < budget) {
                val candidate =
                    current.subList(0, start) + current.subList(minOf(current.size, start + chunk), current.size)
                runs++
                val result = run(failed.seed, failed.setup, candidate)
                if (result.failures.any { it.category == category }) {
                    current = candidate
                    best = result
                    n = maxOf(n - 1, 2)
                    reduced = true
                    break
                }
                start += chunk
            }
            if (!reduced) {
                if (n >= current.size) break
                n = minOf(current.size, n * 2)
            }
        }
        return best
    }
}
