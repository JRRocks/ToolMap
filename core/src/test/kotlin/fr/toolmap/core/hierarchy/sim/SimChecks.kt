package fr.toolmap.core.hierarchy.sim

import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.KeyRing
import fr.toolmap.core.hierarchy.Lane
import fr.toolmap.core.hierarchy.MessageLog
import fr.toolmap.core.hierarchy.OperationEnding
import fr.toolmap.core.hierarchy.OperationFrames
import fr.toolmap.core.hierarchy.OperationNode
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.hierarchy.SharedObjects

/**
 * The invariants of P39.10, checked once the network has calmed down and every phone still in the operation is back,
 * then after one more round where every phone shares in every place it may (`docs/specs/group-hierarchy.md` § 21).
 */
object SimChecks {
    /** One calm round: the traces listen, everyone talks; summaries go every minute. */
    const val CALM_ROUND_MS = 6 * 60_000L
    const val CALM_ROUNDS = 4

    /** Where an expected problem may come from in an honest run (the rest is a sign of a defect). */
    private val PLAUSIBLE =
        setOf(
            // A phone that learnt a change before another opened what the other sealed under the former rights.
            "operation.share.refused:NOT_ALLOWED",
            // A grant for a member this phone has not seen let in yet: it comes back when it speaks.
            "operation.grant.refused:NOT_ALLOWED",
            // An object re-sent by a phone whose author this phone has not seen let in yet.
            "operation.object.refused:UNKNOWN_EDITOR",
            // Two operations under one number, after an app killed before it saved: one is kept everywhere.
            "operation.ops.conflict:CONFLICT_REPLACED",
            "operation.ops.conflict:CONFLICT_KEPT",
            // Two keys of one epoch (two renewals at once) until the smaller one wins (ADR-132).
            "hierarchy.crypto.open:Tag mismatch",
        )

    /**
     * The phone that holds the most operations: what the operation is. A phone taken out while it was away still
     * believes it is in (it can read nothing that follows: spec § 20, question 1); it is no survivor.
     */
    fun reference(sim: Simulation): SimPhone? =
        sim.phones.filter { it.running && it.isMember }.maxByOrNull { it.n.replica.summary().counts.values.sum() }

    fun survivors(sim: Simulation): List<SimPhone> {
        val ref = reference(sim) ?: return emptyList()
        val members = ref.n.state.members.keys
        return sim.phones.filter { it.running && it.isMember && it.id in members && lagging(sim, it) == null }
    }

    /**
     * Why a phone in the operation (as far as the others know) cannot catch up, when it is one of the two cases found
     * by P39.10 and left to the user (spec § 21, questions): `null` when it is not behind, or behind for another
     * reason (then a failure). Its epoch is older than the oldest the others keep (OperationLinkState.KEPT_EPOCHS):
     * nobody opens its frames; or it does not know that whoever announced the invitation key of now may do so (named
     * administrator or creator in an epoch it missed): it refuses the key every time it is handed to it.
     */
    fun lagging(
        sim: Simulation,
        p: SimPhone,
    ): String? {
        val ref = reference(sim) ?: return null
        val node = p.node ?: return null
        if (!p.running || node.epoch >= ref.n.epoch) return null
        if (ref.n.epoch - node.epoch >= fr.toolmap.core.hierarchy.OperationLinkState.KEPT_EPOCHS) return "epoch too old"
        val announcer = ref.n.linkState().epochs.last().announcement.announcer
        val st = node.state
        if (announcer != st.creator && announcer !in st.admins) return "announcer unknown"
        return null
    }

    /** Phones that believe they are in, taken out while they were away. */
    fun strayed(sim: Simulation): List<SimPhone> {
        val ref = reference(sim) ?: return emptyList()
        return sim.phones.filter { it.running && it.isMember && it.id !in ref.n.state.members }
    }

    private fun fail(
        sim: Simulation,
        category: String,
        detail: String,
    ) {
        if (sim.failures.size < 400) sim.failures += Failure(category, detail)
    }

    fun afterCalm(sim: Simulation) {
        val w = sim.world
        sim.collectKeepers()
        w.faults = Faults.CALM
        for (p in sim.phones) {
            if (p.lost) continue
            if (p.ended == null && p.node != null && !p.appUp) p.startApp()
            if (p.ended == null && p.node == null && p.stored != null) p.startApp()
            p.networkOn()
        }
        for (round in 0 until CALM_ROUNDS) {
            for (k in sim.keepers) if (!k.owner.lost) k.open(CALM_ROUND_MS)
            w.runFor(CALM_ROUND_MS)
            sim.collectKeepers()
            if (round >= 1 && settled(sim)) break
        }
        checkProblems(sim)
        checkConvergence(sim)
        checkDissolution(sim)
        checkState(sim)
        checkBounds(sim)
        if (sim.failures.isEmpty()) probe(sim)
        checkProblems(sim, after = true)
        val s = survivors(sim)
        sim.stats.survivors = s.size
        sim.stats.strayed = strayed(sim).size
        sim.stats.members = s.firstOrNull()?.n?.state?.members?.size ?: 0
        sim.stats.dissolved = sim.phones.any { it.ended == OperationEnding.DISSOLVED }
    }

    private fun settled(sim: Simulation): Boolean {
        val s = survivors(sim)
        if (s.isEmpty()) return true
        val first = s.first().n
        return s.all { it.n.replica.summary() == first.replica.summary() && it.n.epoch == first.epoch }
    }

    // ---- problems --------------------------------------------------------------------------------------------------

    private fun checkProblems(
        sim: Simulation,
        after: Boolean = false,
    ) {
        for ((i, p) in sim.problems.withIndex()) {
            if (after && i < sim.problemsSeen) continue
            val reason = p.error.message.orEmpty().take(40)
            val key = "${p.where}:$reason"
            val who = sim.problemPhones.getOrNull(i) ?: "-"
            sim.stats.problemsByPhone[who] = (sim.stats.problemsByPhone[who] ?: 0) + 1
            if (!p.expected) {
                fail(
                    sim,
                    "bug:${p.where}",
                    "${p.error}\n${p.error.stackTraceToString().lines().take(8).joinToString("\n")}",
                )
                continue
            }
            sim.stats.expectedProblems[key] = (sim.stats.expectedProblems[key] ?: 0) + 1
            if (key !in PLAUSIBLE) fail(sim, "problem:${p.where}:$reason", "expected problem in an honest run: $key")
        }
        sim.problemsSeen = sim.problems.size
        for (phone in sim.phones) {
            for (b in phone.badReads) fail(sim, "read:own-state", "${phone.label} heard $b while not reading it")
            phone.badReads.clear()
        }
        for (v in sim.world.violations) {
            fail(
                sim,
                "frame:size",
                "${sim.byId[v.writer]?.label}: ${v.what} (${v.bytes} bytes)",
            )
        }
        sim.world.violations.clear()
        for (a in sim.world.anomalies) fail(sim, "anomaly", a)
        sim.world.anomalies.clear()
    }

    // ---- convergence ---------------------------------------------------------------------------------------------

    private fun checkConvergence(sim: Simulation) {
        val truth = reference(sim)
        if (truth != null) {
            // Let in, as far as the operation knows, so it must learn it (and catch up) once the network is calm.
            for (p in sim.phones) {
                if (!p.running || p.id !in truth.n.state.members) continue
                val why = lagging(sim, p)
                if (why != null) {
                    sim.stats.known[why] = (sim.stats.known[why] ?: 0) + 1
                } else if (!p.isMember) {
                    fail(
                        sim,
                        "join:stuck",
                        "${p.label} was let in and still asks (epoch ${p.n.epoch}, the operation at ${truth.n.epoch})",
                    )
                }
            }
        }
        val s = survivors(sim)
        if (s.size < 2) return
        val ref = s.first()
        val summary = ref.n.replica.summary()
        val bytes = HierarchyWire.encodeState(ref.n.state)
        val refused = ref.n.replica.history().map { it.op.author to it.op.seq to it.rejection }
        for (p in s.drop(1)) {
            if (p.n.replica.summary() != summary) {
                fail(
                    sim,
                    "converge:ops",
                    "${p.label} holds ${p.n.replica.summary().counts.values.sum()} operations, " +
                        "${ref.label} ${summary.counts.values.sum()}",
                )
                continue
            }
            if (!HierarchyWire.encodeState(
                    p.n.state,
                ).contentEquals(bytes)
            ) {
                fail(sim, "converge:state", "${p.label} and ${ref.label}")
            }
            if (p.n.replica.history().map { it.op.author to it.op.seq to it.rejection } != refused) {
                fail(sim, "converge:history", "${p.label} and ${ref.label} refuse different operations")
            }
        }
        val epoch = ref.n.epoch
        val key = ref.n.inviteKey(epoch)
        for (p in s.drop(1)) {
            if (p.n.epoch != epoch) {
                fail(sim, "converge:epoch", "${p.label} at epoch ${p.n.epoch}, ${ref.label} at $epoch")
            } else if (!p.n.inviteKey(epoch).contentEquals(key)) {
                fail(sim, "converge:epoch-key", "${p.label} and ${ref.label} hold two different keys for epoch $epoch")
            }
        }
    }

    private fun checkDissolution(sim: Simulation) {
        val dissolved = sim.phones.any { it.ended == OperationEnding.DISSOLVED }
        for (p in sim.phones) {
            if (!p.running) continue
            if (p.n.state.dissolved) {
                fail(sim, "dissolution:still-running", "${p.label} holds the dissolution and runs on")
                continue
            }
            if (!dissolved || !p.isMember) continue
            // A phone away at the dissolution: told by a phone that keeps the trace, when one listens while it is here.
            val usable =
                sim.keepers.any { k ->
                    !k.owner.lost && !k.trace.isExpired(sim.world.now + k.owner.skewMs) &&
                        k.trace.keyOf(p.n.epoch) != null
                }
            if (usable) {
                fail(
                    sim,
                    "dissolution:late-not-told",
                    "${p.label} still runs the dissolved operation (epoch ${p.n.epoch})",
                )
            }
        }
    }

    // ---- what each phone holds -------------------------------------------------------------------------------------

    private fun checkState(sim: Simulation) {
        val s = survivors(sim)
        val state = s.firstOrNull()?.n?.state
        if (state != null) {
            for (d in SimOracle.differences(state).take(10)) fail(sim, "oracle", d)
        }
        // Who waits is the only one to tell its name, in its own lane (§ 4.2: the roster there comes from who waits):
        // one that can no longer speak to the others (cases B and C, [lagging]) stays unnamed for whoever was named
        // administrator since.
        val mute = sim.phones.filter { lagging(sim, it) != null }.mapTo(HashSet()) { it.id }
        for (p in s) {
            val st = p.n.state
            val read = st.channelsReadBy(p.id).toSet()
            val held = p.n.ring.heldChannels()
            for (c in held - read) fail(sim, "keys:unread-held", "${p.label} keeps keys of $c it does not read")
            if (st.hasAccess(p.id)) {
                for (c in p.n.ring.missing(st)) {
                    if (c.lane == Lane.UNIT) fail(sim, "keys:missing-unit", "${p.label} has no key for $c")
                }
                // The name of who waits goes to the administrators and the creator only (Q10, Q11, § 17 reading 8).
                val waitingNames = st.waiting().filter { it.id != p.id }.flatMap { st.namesOf(it.id) }.toSet()
                val muteNames = st.waiting().filter { it.id in mute }.flatMap { st.namesOf(it.id) }.toSet()
                val mayKnowWaiting = p.id == st.creator || p.id in st.admins
                val excused = p.n.names.missing(st).filter { it in muteNames }
                if (excused.isNotEmpty()) {
                    sim.stats.known["name of who waits, epoch too old"] =
                        (sim.stats.known["name of who waits, epoch too old"] ?: 0) + excused.size
                }
                val missing =
                    p.n.names.missing(st).filter { (mayKnowWaiting || it !in waitingNames) && it !in muteNames }
                if (missing.isNotEmpty()) {
                    val what =
                        missing.map { h ->
                            when {
                                h == st.name -> {
                                    "the operation"
                                }

                                st.groups.values.any { it.name == h } -> {
                                    "a group"
                                }

                                else -> {
                                    st.members.values.firstOrNull { it.name == h }?.let { m ->
                                        "${sim.byId[m.id]?.label}${if (st.isWaiting(m.id)) " (waiting)" else ""}"
                                    } ?: "?"
                                }
                            }
                        }
                    val holders =
                        s.filter { o ->
                            o !== p && missing.all { it in o.n.names.cardsKnown() }
                        }.map { it.label }
                    fail(sim, "names:missing", "${p.label} lacks the names of $what; known to $holders")
                }
            }
            if (st.isWaiting(p.id)) {
                val extra = p.n.names.cardsKnown() - st.namesFor(p.id)
                if (extra.isNotEmpty()) {
                    fail(
                        sim,
                        "names:waiting-reads",
                        "${p.label} waits and knows ${extra.size} other names",
                    )
                }
            }
            val objectChannels = p.n.objects.heldChannels()
            for (c in objectChannels - read) fail(sim, "objects:unread-held", "${p.label} keeps objects of $c")
            for ((c, list) in p.threads()) {
                if (list.size >
                    MessageLog.MAX_PER_THREAD
                ) {
                    fail(sim, "r2:over", "${p.label}: ${list.size} messages in $c")
                }
                if (list.map { it.author to it.id }.toSet().size !=
                    list.size
                ) {
                    fail(sim, "r2:twice", "${p.label}: a message twice in $c")
                }
                if (c !in read) fail(sim, "r2:unread-thread", "${p.label} keeps the thread of $c")
            }
        }
        if (state != null) {
            for (c in state.channels()) {
                val readers = s.filter { p -> state.readersOf(c).any { it.id == p.id } }
                val digests = readers.map { it.n.objects.digest(c) }.toSet()
                if (digests.size > 1) {
                    val counts =
                        readers.joinToString {
                            "${it.label}=${it.n.objects.shown(c).size}/${it.n.objects.resend(c).size}"
                        }
                    fail(sim, "objects:diverge:${c.lane}", "readers of $c hold different objects: $counts")
                }
            }
        }
        for (p in sim.phones) {
            val node = p.node ?: continue
            if (p.ended == null) continue
            if (p.n.ring.heldChannels().isNotEmpty() || p.n.ring.batchesFor(node.state).isNotEmpty()) {
                fail(sim, "keys:not-cleared", "${p.label} ended and keeps keys")
            }
            if (p.n.names.cardsKnown().isNotEmpty()) fail(sim, "names:not-cleared", "${p.label} ended and keeps names")
            if (p.stored != null) fail(sim, "storage:not-wiped", "${p.label} ended and its storage is not wiped")
        }
    }

    // ---- memory and time ------------------------------------------------------------------------------------------

    private fun sizeOf(
        node: OperationNode,
        field: String,
    ): Int {
        val f = OperationNode::class.java.getDeclaredField(field)
        f.isAccessible = true
        return when (val v = f.get(node)) {
            is Map<*, *> -> v.size
            is Collection<*> -> v.size
            else -> -1
        }
    }

    private fun checkBounds(sim: Simulation) {
        val ids = sim.phones.size + sim.keepers.size + 4
        for (p in sim.phones) {
            val node = p.node ?: continue
            val limits =
                mapOf(
                    "outbox" to OperationNode.MAX_OUTBOX,
                    "joins" to OperationNode.MAX_JOINS,
                    "seenTexts" to 4_096,
                    "duties" to 4 * 64 + 8,
                    "lastFix" to ids,
                    "pulledAt" to ids,
                    "answeredAt" to ids,
                    "grantedAt" to ids,
                    "resendSeenAt" to 3 * 64 + 2 + ids,
                )
            for ((field, max) in limits) {
                val n = sizeOf(node, field)
                if (n > max) fail(sim, "bounds:$field", "${p.label}: $n entries (at most $max)")
            }
            for ((c, keys) in node.ring.heldMap()) {
                if (keys >
                    KeyRing.KEPT_KEYS
                ) {
                    fail(sim, "bounds:keys", "${p.label}: $keys keys of $c")
                }
            }
            if (node.ring.checkedCount() >
                256
            ) {
                fail(sim, "bounds:batches", "${p.label}: ${node.ring.checkedCount()} batches")
            }
            if (node.ring.pendingCount() >
                KeyRing.MAX_PENDING
            ) {
                fail(sim, "bounds:pending", "${p.label}: ${node.ring.pendingCount()}")
            }
            for (c in node.objects.heldChannels()) {
                if (node.objects.resend(c).size >
                    SharedObjects.MAX_PER_CHANNEL
                ) {
                    fail(sim, "bounds:objects", "${p.label}: $c")
                }
            }
            if (p.slowestCallNs > SLOW_CALL_NS) {
                fail(sim, "time:slow-call", "${p.label}: one call of ${p.slowestCallNs / 1_000_000} ms")
            }
        }
    }

    private const val SLOW_CALL_NS = 4_000_000_000L

    // ---- the probes ----------------------------------------------------------------------------------------------

    private class Probe(
        val kind: ShareKind,
        val author: SimPhone,
        val channel: Channel,
        val text: String,
        val objectId: Long = 0,
    )

    private fun probe(sim: Simulation) {
        val s = survivors(sim)
        if (s.size < 2) return
        val w = sim.world
        val state = s.first().n.state
        w.probing = true
        for (p in sim.phones) {
            p.heardMessages.clear()
            p.heardAlerts.clear()
            p.heardPositions.clear()
        }
        val probes = ArrayList<Probe>()
        // Positions: until they go (a position without the key of now is skipped, the next one goes).
        val pending = s.toMutableList()
        repeat(6) {
            val next = ArrayList<SimPhone>()
            for (p in pending) {
                when (sim.sendPosition(p)) {
                    ShareOutcome.SENT -> Unit
                    ShareOutcome.SKIPPED -> next += p
                    else -> fail(sim, "probe:position-refused", "${p.label} may not share its position")
                }
            }
            pending.clear()
            pending += next
            w.runFor(5_000)
        }
        for (p in pending) fail(sim, "probe:position-skipped", "${p.label}: its position never goes")
        for (a in s) {
            for (c in SimOracle.shareChannels(state, a.id, ShareKind.MESSAGE)) {
                val text = "Sonde ${a.label} ${c.lane} ${SimOracle.hex(c.scope)}"
                when (sim.sendMessage(a, c, text)) {
                    ShareOutcome.SENT, ShareOutcome.QUEUED -> probes += Probe(ShareKind.MESSAGE, a, c, text)
                    else -> fail(sim, "probe:message-refused", "${a.label} may not write in $c")
                }
            }
            SimOracle.alertChannel(state, a.id)?.let { c ->
                val text = "Sonde alerte ${a.label}"
                when (sim.sendAlert(a, text)) {
                    ShareOutcome.SENT, ShareOutcome.QUEUED -> probes += Probe(ShareKind.ALERT, a, c, text)
                    else -> fail(sim, "probe:alert-refused", "${a.label} may not alert $c")
                }
            }
            for (c in SimOracle.shareChannels(state, a.id, ShareKind.WAYPOINT)) {
                val id = 0x7000_0000_0000_0000L + probes.size
                val body = "Sonde objet ${a.label}".toByteArray()
                when (sim.putObject(a, ShareKind.WAYPOINT, c, id, body)) {
                    ShareOutcome.SENT, ShareOutcome.QUEUED -> probes += Probe(ShareKind.WAYPOINT, a, c, "", id)
                    else -> fail(sim, "probe:object-refused", "${a.label} may not put a waypoint in $c")
                }
            }
        }
        w.runFor(3 * 60_000L)
        sim.stats.probes = probes.size
        val members = s.toSet()
        for (pr in probes) {
            val expected =
                SimOracle.readers(state, pr.channel).mapNotNull {
                    sim.byId[it]
                }.filter { it in members }.toSet()
            val heard =
                when (pr.kind) {
                    ShareKind.MESSAGE -> {
                        s.filter { p ->
                            p.heardMessages.any {
                                it.author == pr.author.id &&
                                    it.text == pr.text &&
                                    it.channel == pr.channel
                            }
                        }
                    }

                    ShareKind.ALERT -> {
                        s.filter { p ->
                            p.heardAlerts.any {
                                it.author == pr.author.id &&
                                    it.text == pr.text &&
                                    it.channel == pr.channel
                            }
                        }
                    }

                    else -> {
                        s.filter { p ->
                            p.n.objects.shown(p.n.state, p.id).any {
                                it.author == pr.author.id &&
                                    it.objectId == pr.objectId
                            }
                        }
                    }
                }.toSet()
            val wanted = if (pr.kind == ShareKind.WAYPOINT) expected else expected - pr.author
            val missing = wanted - heard
            val extra = heard - wanted
            if (missing.isNotEmpty()) {
                fail(
                    sim,
                    "deliver:missing:${pr.kind}:${pr.channel.lane}",
                    "${pr.text.ifEmpty {
                        "object"
                    }} by ${pr.author.label} in ${pr.channel}: not on $missing",
                )
            }
            if (extra.isNotEmpty()) {
                fail(
                    sim,
                    "deliver:extra:${pr.kind}:${pr.channel.lane}",
                    "${pr.text.ifEmpty {
                        "object"
                    }} by ${pr.author.label} in ${pr.channel}: also on $extra",
                )
            }
        }
        for (a in s) {
            val channel = SimOracle.shareChannels(state, a.id, ShareKind.POSITION).singleOrNull() ?: continue
            val wanted =
                SimOracle.readers(state, channel).mapNotNull { sim.byId[it] }.filter {
                    it in members &&
                        it !== a
                }.toSet()
            val heard = s.filter { p -> p.heardPositions.any { it.author == a.id } }.toSet()
            if ((wanted - heard).isNotEmpty()) {
                fail(
                    sim,
                    "deliver:missing:POSITION:${channel.lane}",
                    "position of ${a.label}: not on ${wanted - heard}",
                )
            }
            if ((heard - wanted).isNotEmpty()) {
                fail(
                    sim,
                    "deliver:extra:POSITION:${channel.lane}",
                    "position of ${a.label}: also on ${heard - wanted}",
                )
            }
        }
        // A phone taken out while it was away believes it is in: it must hear nothing of what follows.
        for (p in strayed(sim)) {
            val heard = p.heardMessages.size + p.heardAlerts.size + p.heardPositions.size
            if (heard >
                0
            ) {
                fail(
                    sim,
                    "closing:strayed-hears",
                    "${p.label}, taken out while away, heard $heard shares of the probes",
                )
            }
        }
        checkClosing(sim, state)
        w.probing = false
    }

    /**
     * What a modified phone could open: for every channel, the keys the readers of now seal with must be wrapped to
     * nobody else, whatever that phone kept since it was let in; the invitation key of now must not be held by a phone
     * that left or was taken out.
     */
    private fun checkClosing(
        sim: Simulation,
        state: HierarchyState,
    ) {
        val s = survivors(sim)
        for (c in state.channels()) {
            val readers = state.readersOf(c).map { it.id }.toSet()
            val keys = s.filter { it.id in readers }.mapNotNull { it.n.ring.sealingKey(state, c)?.id }.toSet()
            for (p in sim.phones) {
                if (p.id in readers) {
                    // A key it made itself reaches its hoard only once written, and a phone with no link open at
                    // that moment writes nothing; a lane where nobody shared yet has no key of now at all.
                    if (p in s && keys.isNotEmpty()) {
                        val own = p.n.ring.checked.values.filter { it.channel == c && it.issuer == p.id }
                        if (keys.none { k -> (c to k) in p.hoard.contentKeys || own.any { it.keyId == k } }) {
                            fail(sim, "hoard:sanity", "${p.label} reads $c and its hoard lacks the key of now")
                        }
                    }
                    continue
                }
                for (k in keys) {
                    if ((c to k) in p.hoard.contentKeys) {
                        val why =
                            if (p.ended !=
                                null
                            ) {
                                "out (${p.ended})"
                            } else if (p.running &&
                                state.isWaiting(p.id)
                            ) {
                                "waiting"
                            } else {
                                "not a reader"
                            }
                        fail(sim, "closing:content-key:${c.lane}", "${p.label}, $why, holds the key of now of $c")
                    }
                }
            }
        }
        val ref = s.firstOrNull() ?: return
        val inviter = s.any { it.id == state.creator || it.id in state.admins }
        val key = ref.n.inviteKey(ref.n.epoch) ?: return
        if (!inviter) return
        for ((id, _) in sim.departedAt) {
            val p = sim.byId[id] ?: continue
            if (id in state.members) continue
            if (p.hoard.holdsEpochKey(key)) {
                fail(
                    sim,
                    "closing:invite-key",
                    "${p.label} left or was taken out and holds the invitation key of now (epoch ${ref.n.epoch})",
                )
            }
        }
    }

    // ---- the trace (R1) -------------------------------------------------------------------------------------------

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.size < 4 || needle.size > haystack.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }

    /** R1: the trace holds no name, no position, no message, no object, no content key, no salt of a name card. */
    fun checkTrace(
        sim: Simulation,
        owner: SimPhone,
        trace: DissolutionTrace,
    ) {
        val bytes = trace.encode()
        val notice = trace.notice().encode()
        if (notice.size >
            OperationFrames.MAX_BODY
        ) {
            fail(sim, "r1:too-large", "${owner.label}: notice of ${notice.size} bytes")
        }
        for (name in sim.names) {
            if (contains(
                    bytes,
                    name.toByteArray(Charsets.UTF_8),
                )
            ) {
                fail(sim, "r1:private", "${owner.label}'s trace holds a name")
            }
        }
        for (text in sim.texts) {
            if (contains(
                    bytes,
                    text.toByteArray(Charsets.UTF_8),
                )
            ) {
                fail(sim, "r1:private", "${owner.label}'s trace holds a text")
            }
        }
        for (payload in sim.payloads) {
            if (contains(
                    bytes,
                    payload,
                )
            ) {
                fail(sim, "r1:private", "${owner.label}'s trace holds a position or an object")
            }
        }
        for (salt in sim.salts) {
            if (contains(
                    bytes,
                    salt,
                )
            ) {
                fail(sim, "r1:private", "${owner.label}'s trace holds the salt of a name")
            }
        }
        for (p in sim.phones) {
            for (k in p.hoard.contentKeys.values) {
                if (contains(bytes, k.secret)) fail(sim, "r1:private", "${owner.label}'s trace holds a content key")
            }
        }
        if (trace.dissolvedAtMs > owner.clock() + 1) fail(sim, "r1:date", "${owner.label}: dissolved in its future")
    }

    private fun KeyRing.heldChannels(): Set<Channel> = synchronized(this) { held.keys.toSet() }

    private fun KeyRing.heldMap(): Map<Channel, Int> = synchronized(this) { held.mapValues { it.value.size } }

    private fun KeyRing.checkedCount(): Int = synchronized(this) { checked.size }

    private fun KeyRing.pendingCount(): Int = synchronized(this) { pending.size }

    private fun SharedObjects.heldChannels(): Set<Channel> = synchronized(this) { held.keys.toSet() }

    private fun fr.toolmap.core.hierarchy.NameBook.cardsKnown(): Set<fr.toolmap.core.hierarchy.HiddenName> =
        synchronized(this) {
            cards.keys.toSet()
        }
}
