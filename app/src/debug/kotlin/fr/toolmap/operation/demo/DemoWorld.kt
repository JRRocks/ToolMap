package fr.toolmap.operation.demo

import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.p2p.LinkListener
import fr.toolmap.core.p2p.MeshLink
import fr.toolmap.core.p2p.PairingPayload
import fr.toolmap.core.p2p.RelayConfig
import fr.toolmap.core.p2p.RelayPaths
import fr.toolmap.core.p2p.RelayTransport
import fr.toolmap.core.p2p.RelayTransportFactory
import fr.toolmap.core.p2p.ServerState
import fr.toolmap.core.p2p.TransportIdentity
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.data.hierarchy.KeyVault
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationPhase
import fr.toolmap.data.hierarchy.OperationStorage
import fr.toolmap.data.hierarchy.StartResult
import fr.toolmap.data.hierarchy.VaultAgreementKey
import fr.toolmap.data.hierarchy.VaultSealingKey
import fr.toolmap.data.hierarchy.VaultSigningKey
import kotlinx.coroutines.delay
import java.io.File
import java.security.KeyPair
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The « opération de démonstration » of the debug build type (P39.8, ADR-130): the facts of the maquettes
 * (`docs/previews/p39-groupes/tour3/REMONTEE.md`, « Les mêmes faits sur toutes les images ») played by 22 phones simulated
 * in this app. Each is a real [OperationController], with its own storage under [root] and a key store in memory, in a
 * room of a coordination server simulated in memory, as in `OperationControllerTest`: nothing goes to the server, nothing
 * touches the Keystore or the operations of this phone.
 *
 * « Exercice Vallon »: Nord (Membre 4 chef, 5 adjoint and administrateur, 2, 7, 8), Sud (9 chef and administrateur, 10
 * adjoint, 3, 11, 12, 13), Est (14 chef, 1, 15, 16, 17), Ouest (6, 18, 19, 20; no chief); Membre 1 is the creator,
 * Membre 2 the chief of the operation and Membre 3 its deputy; Membre 21 and Membre 22 wait for a group. Membres 8,
 * 12 and 20 are not in the room (their phones are off), as the maquettes draw them.
 */
class DemoWorld private constructor(
    private val root: File,
    /** The phones, by the number of their member (« Membre 7 » is 7). */
    val seats: Map<Int, OperationController>,
    /** The member identifier of each number. */
    val ids: Map<Int, Long>,
    private val relay: DemoRelay,
) {
    /** The number of [member], when it is one of the demonstration's. */
    fun numberOf(member: Long): Int? = ids.entries.firstOrNull { it.value == member }?.key

    /** Membre [number]'s phone loses the network: the others no longer hear it, nor it them (P39.14.5, M4 A). */
    fun cutOff(number: Int) {
        ids[number]?.let(relay::cutOff)
    }

    /** Membre [number]'s phone is back in the room. */
    fun reconnect(number: Int) {
        ids[number]?.let(relay::reconnect)
    }

    /** Closes every phone of the demonstration and deletes its folder. */
    suspend fun close() {
        for (c in seats.values) c.close()
        root.deleteRecursively()
    }

    companion object {
        const val OPERATION = "Exercice Vallon"
        const val COUNT = 22

        /** Membres 1 to 20 are placed in a group, 21 and 22 wait. */
        private const val PLACED = 20

        /** The phones that are off in the maquettes: « Synchronisation perdue » (8, 20), « Hors ligne » (12). */
        val OFF: Set<Int> = setOf(8, 12, 20)

        /** When they went silent, minutes before now (`tour3/08-inviter`: 4, 14 and 6 minutes). */
        val SILENT_MINUTES: Map<Int, Long> = mapOf(8 to 4L, 12 to 14L, 20 to 6L)

        /** When the two waiting members came, minutes before now (`tour2/03-attente-admin`). */
        val ARRIVED_MINUTES: Map<Int, Long> = mapOf(21 to 3L, 22 to 12L)

        private val GROUPS: List<Pair<String, List<Int>>> =
            listOf(
                "Nord" to listOf(4, 5, 2, 7, 8),
                "Sud" to listOf(9, 10, 3, 11, 12, 13),
                "Est" to listOf(14, 1, 15, 16, 17),
                "Ouest" to listOf(6, 18, 19, 20),
            )

        /** A server written into the invitations of the demonstration: never reached (the room is in memory). */
        private val SERVER = RelayConfig.of("https://demo.invalid", "demo")

        private const val WAIT_MS = 90_000L
        private const val POLL_MS = 100L
        private const val REPORT_MS = 5_000L

        /**
         * Builds the demonstration under [root] (emptied first), [progress] told at each step. `null` when a step
         * failed (said to whoever debugs: [EventLog] and the problems of the controllers).
         */
        suspend fun build(
            root: File,
            progress: (String) -> Unit = {},
        ): DemoWorld? {
            root.deleteRecursively()
            root.mkdirs()
            val relay = DemoRelay()
            val seats =
                (1..COUNT).associateWith { n ->
                    OperationController(OperationStorage(File(root, "m$n"), DemoKeyVault()), relay, { SERVER })
                }
            val creator = seats.getValue(1)
            if (creator.create(OPERATION, "Membre 1") != StartResult.STARTED) return failed("create", progress)
            val link = creator.invitationLink() ?: return failed("invitation", progress)
            val ids = HashMap<Int, Long>()
            ids[1] = creator.view.value.self ?: return failed("self", progress)

            // Lets in Membre [numbers] with the invitation, in the order of their numbers: one at a time, each seen
            // as a member by every phone let in before the next one asks (P39.17, 2026-11-20: the nineteen let in at
            // once were what was left of the peak of the heap, 181 MB of the 192 MB of the emulator once in three).
            suspend fun letIn(numbers: IntRange): Boolean {
                for (n in numbers) {
                    if (seats.getValue(n).join(link, "Membre $n") != StartResult.STARTED) return false

                    fun request() = creator.view.value.joinRequests.firstOrNull { it.name == "Membre $n" }
                    if (!until({ "join request $n" }, progress) { request() != null }) return false
                    val member = request()?.member ?: return false
                    ids[n] = member
                    if (creator.admit(member) != null) return false
                    val seen =
                        until({ "admitted $n" }, progress) {
                            (1..n).all { m ->
                                seats.getValue(m).view.value.hierarchy?.members?.containsKey(member) ==
                                    true
                            }
                        }
                    if (!seen) return false
                }
                return true
            }
            progress("joining")
            if (!letIn(2..PLACED)) return failed("join", progress)
            progress("groups")
            val groups = HashMap<String, Long>()

            // One change at a time, seen by every phone let in before the next one (run « CI » n° 232, 2026-11-16):
            // twenty phones in one process, answering one another's crossing summaries with their key batches, may
            // fill the heap of the emulator of the CI.
            fun everyone(seen: (HierarchyState) -> Boolean) =
                (1..PLACED).all { n -> seats.getValue(n).view.value.hierarchy?.let(seen) == true }
            for ((name, members) in GROUPS) {
                val group = creator.createGroup(name).first ?: return failed("group $name", progress)
                groups[name] = group
                if (!until({ "group $name" }, progress) { everyone { group in it.groups } }) {
                    return failed("group $name seen", progress)
                }
                for (n in members) {
                    val member = ids.getValue(n)
                    val refused = creator.place(member, group)
                    if (refused != null) return failed("place $n", progress)
                    if (!until({ "place $n" }, progress) { everyone { it.members[member]?.group == group } }) {
                        return failed("place $n seen", progress)
                    }
                }
            }
            progress("posts")
            val posts =
                listOf(
                    Triple(HierarchyState.ROOT, Post.LEADER, 2),
                    Triple(HierarchyState.ROOT, Post.DEPUTY, 3),
                    Triple(groups.getValue("Nord"), Post.LEADER, 4),
                    Triple(groups.getValue("Nord"), Post.DEPUTY, 5),
                    Triple(groups.getValue("Sud"), Post.LEADER, 9),
                    Triple(groups.getValue("Sud"), Post.DEPUTY, 10),
                    Triple(groups.getValue("Est"), Post.LEADER, 14),
                )
            // As the groups: one post, one administrator at a time, seen by all before the next (P39.17, 2026-11-20:
            // with the summaries answered by their addressee only, the posts given at once were what was left of the
            // peak of the heap, 165 MB of the 192 MB of the emulator once in three).
            for ((unit, post, n) in posts) {
                val member = ids.getValue(n)
                val refused = creator.setPost(unit, post, member)
                if (refused != null) return failed("post $n", progress)
                val seen =
                    until({ "post $n" }, progress) {
                        everyone { (if (post == Post.LEADER) it.leaderOf(unit) else it.deputyOf(unit)) == member }
                    }
                if (!seen) return failed("post $n seen", progress)
            }
            for (n in listOf(5, 9)) {
                val member = ids.getValue(n)
                if (creator.addAdmin(member) != null) return failed("admin $n", progress)
                if (!until({ "admin $n" }, progress) { everyone { member in it.admins } }) {
                    return failed("admin $n seen", progress)
                }
            }
            // The two who wait come once the administrators are named: their names are sealed for them too (an
            // administrator named after an arrival does not learn its name, P39.7).
            progress("waiting")
            if (!letIn(PLACED + 1..COUNT)) return failed("join waiting", progress)
            progress("settling")

            // Every phone has the operations, its place, and the names it may read.
            fun phaseOf(n: Int) = if (n > PLACED) OperationPhase.WAITING else OperationPhase.PLACED
            val settled =
                until({ "settled ${seats.count { (n, c) -> c.view.value.phase == phaseOf(n) }}/$COUNT" }, progress) {
                    seats.all { (n, c) ->
                        val v = c.view.value
                        // Who waits reads no name; a member placed reads those of the placed members (Q2, Q10, Q11).
                        val named = v.members.all { it.waiting || it.name != null } && v.groups.all { it.name != null }
                        v.phase == phaseOf(n) && v.members.size == COUNT && (n > PLACED || named)
                    }
                }
            if (!settled) EventLog.event("operation.demo", "step" to "settle", "settled" to false)
            for (n in OFF) seats.getValue(n).close()
            // The controller publishes its view on a change, not when a link closes (P39.7): any call publishes it
            // again, with who is still in the room.
            delay(POLL_MS)
            for ((n, c) in seats) if (n !in OFF) c.invitationLink()
            progress("ready")
            return DemoWorld(root, seats, ids, relay)
        }

        /** Waits for [condition], [report] told to [progress] every few seconds; `false` after [WAIT_MS]. */
        private suspend fun until(
            report: () -> String,
            progress: (String) -> Unit,
            condition: () -> Boolean,
        ): Boolean {
            var waited = 0L
            while (!condition()) {
                if (waited >= WAIT_MS) return false
                if (waited % REPORT_MS == 0L && waited > 0) progress(report())
                delay(POLL_MS)
                waited += POLL_MS
            }
            return true
        }

        private fun failed(
            step: String,
            progress: (String) -> Unit,
        ): DemoWorld? {
            progress("failed at $step")
            EventLog.event("operation.demo", "step" to step, "built" to false)
            return null
        }
    }
}

/**
 * A room of a coordination server, in memory: every frame written reaches every member present, as the real one
 * does for a room (the « Entre amis » relay is not involved). For the demonstration only.
 */
class DemoRelay : RelayTransportFactory {
    private val rooms = HashMap<String, MutableList<Member>>()

    /** The phones cut off from the others ([cutOff]). */
    private val cut = HashSet<Long>()

    private fun members(): List<Member> = synchronized(rooms) { rooms.values.flatten() }

    private fun isCut(member: Long) = synchronized(rooms) { member in cut }

    /** [member] loses the network: its links close, on both sides, and nothing goes through them. */
    fun cutOff(member: Long) {
        if (!synchronized(rooms) { cut.add(member) }) return
        val all = members()
        val phone = all.firstOrNull { it.id == member } ?: return
        for (other in all) {
            if (other === phone || other.closed) continue
            other.listener.onLinkClosed(member)
            phone.listener.onLinkClosed(other.id)
        }
    }

    /** [member] is back: its links open again. */
    fun reconnect(member: Long) {
        if (!synchronized(rooms) { cut.remove(member) }) return
        val all = members()
        val phone = all.firstOrNull { it.id == member } ?: return
        for (other in all) {
            if (other === phone || other.closed) continue
            other.listener.onLinkOpen(member, "", 0, other.linkTo(phone))
            phone.listener.onLinkOpen(other.id, "", 0, phone.linkTo(other))
        }
    }

    inner class Member(
        private val room: String,
        val listener: LinkListener,
    ) : RelayTransport {
        override var identity: TransportIdentity? = null
        override var onState: ((ServerState) -> Unit)? = null
        override var state: ServerState = ServerState.CONNECTING

        @Volatile
        var closed = false
            private set

        val id: Long get() = identity?.memberId ?: 0L

        private fun others(): List<Member> =
            synchronized(rooms) {
                rooms[room].orEmpty().filter { it !== this && !it.closed }
            }

        fun linkTo(peer: Member): MeshLink =
            object : MeshLink {
                override val isBroadcastMedium get() = true

                override fun send(
                    frame: ByteArray,
                    onPositionChannel: Boolean,
                ): Boolean {
                    if (closed || peer.closed || isCut(id) || isCut(peer.id)) return false
                    peer.listener.onFrame(id, frame)
                    return true
                }

                override fun close() = Unit
            }

        override fun start() {
            synchronized(rooms) { rooms.getOrPut(room) { ArrayList() } += this }
            state = ServerState.ONLINE
            onState?.invoke(state)
            for (other in others()) {
                other.listener.onLinkOpen(id, "", 0, other.linkTo(this))
                listener.onLinkOpen(other.id, "", 0, linkTo(other))
            }
        }

        override fun ban(peer: Long) = Unit

        override fun close() {
            if (closed) return
            closed = true
            synchronized(rooms) { rooms[room]?.remove(this) }
            for (other in others()) other.listener.onLinkClosed(id)
        }

        override fun offer(
            peer: Long,
            done: (PairingPayload?) -> Unit,
        ) = done(null)

        override fun answer(
            offer: PairingPayload,
            done: (PairingPayload?) -> Unit,
        ) = done(null)

        override fun accept(answer: PairingPayload) = Unit

        override fun drop(peer: Long) = Unit
    }

    override fun create(
        config: RelayConfig,
        groupId: ByteArray,
        listener: LinkListener,
    ): RelayTransport = Member(RelayPaths.room(groupId), listener)
}

/**
 * A key store in memory, for the simulated phones of the demonstration: what the Android Keystore does, without the
 * hardware (as the software vault of the JVM tests). Its keys go with the demonstration.
 */
class DemoKeyVault : KeyVault {
    override val agreesInHardware: Boolean = true

    private val signing = HashMap<String, KeyPair>()
    private val agreement = HashMap<String, KeyPair>()
    private val sealing = HashMap<String, ByteArray>()
    private val random = SecureRandom()

    @Synchronized
    override fun createSigningKey(alias: String): VaultSigningKey {
        signing[alias] = P256.generate()
        return requireNotNull(signingKey(alias))
    }

    @Synchronized
    override fun signingKey(alias: String): VaultSigningKey? {
        val pair = signing[alias] ?: return null
        return object : VaultSigningKey {
            override val public = P256.encode(pair.public)

            override fun sign(message: ByteArray): ByteArray = P256.sign(pair.private, message)
        }
    }

    @Synchronized
    override fun createAgreementKey(alias: String): VaultAgreementKey {
        agreement[alias] = P256.generate()
        return requireNotNull(agreementKey(alias))
    }

    @Synchronized
    override fun agreementKey(alias: String): VaultAgreementKey? {
        val pair = agreement[alias] ?: return null
        return object : VaultAgreementKey {
            override val public = P256.encode(pair.public)

            override fun agree(peer: ByteArray): ByteArray? = P256.agree(pair.private, peer)
        }
    }

    @Synchronized
    override fun createSealingKey(alias: String): VaultSealingKey {
        sealing[alias] = ByteArray(KEY_BYTES).also(random::nextBytes)
        return requireNotNull(sealingKey(alias))
    }

    @Synchronized
    override fun sealingKey(alias: String): VaultSealingKey? {
        val key = sealing[alias] ?: return null
        return object : VaultSealingKey {
            override fun seal(
                plain: ByteArray,
                aad: ByteArray,
            ): ByteArray {
                val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad)
                return nonce + cipher.doFinal(plain)
            }

            override fun open(
                sealed: ByteArray,
                aad: ByteArray,
            ): ByteArray? {
                if (sealed.size < NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS) return null
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES),
                )
                cipher.updateAAD(aad)
                // As the real vault: not sealed by this key, or damaged, reads as nothing.
                return catchExpected("operation.demo.vault", AEADBadTagException::class) {
                    cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
                }.getOrNull()
            }
        }
    }

    @Synchronized
    override fun delete(alias: String) {
        signing.remove(alias)
        agreement.remove(alias)
        sealing.remove(alias)
    }

    @Synchronized
    override fun aliases(prefix: String): List<String> =
        (signing.keys + agreement.keys + sealing.keys).filter { it.startsWith(prefix) }.distinct()

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val AES_GCM = "AES/GCM/NoPadding"
    }
}
