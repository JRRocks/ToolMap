package fr.toolmap.core.hierarchy

import fr.toolmap.core.p2p.ByteReader
import fr.toolmap.core.p2p.ByteWriter
import fr.toolmap.core.p2p.WireException

/**
 * What a phone tells another about its copy of the operations: for each author, how many operations it holds without
 * a gap, and a fingerprint of them. The other answers with what is missing ([HierarchyReplica.missingFor]).
 */
data class Summary(
    val ensembleId: Long,
    val counts: Map<Long, Int>,
    val digest: Long,
)

/**
 * The binary format of the hierarchical groups (`docs/specs/group-hierarchy.md` § 7), big-endian like [Wire]. Every
 * block starts with `version (1) | kind (1)`. Not tied to a transport: these bytes are the payload a later message
 * type of the mesh will carry, sealed with the key of the group like the others.
 *
 * Decoding refuses with a [WireException], and nothing else, whatever the bytes: truncated, too long, unknown codes,
 * a name [Names] refuses, or an operation not written the one way this version writes it (the proof of an operation
 * signs its exact bytes).
 */
object HierarchyWire {
    const val VERSION = 1

    /** The most operations in one block; a longer catch-up takes several exchanges. */
    const val MAX_BATCH = 512

    /** The most authors in a [Summary]. */
    const val MAX_AUTHORS = 4096
    private const val MAX_KEY_BYTES = 160
    private const val MAX_SIGNATURE_BYTES = 96
    private const val SIGNED_OPS = 1

    enum class Kind(
        val code: Int,
    ) {
        OP(1),
        OPS(2),
        SUMMARY(3),
        STATE(4),
    }

    /** The kind of [bytes], or `null` when they are not a block of this version. */
    fun kindOf(bytes: ByteArray): Kind? =
        if (bytes.size < 2 ||
            bytes[0].toInt() != VERSION
        ) {
            null
        } else {
            Kind.entries.firstOrNull { it.code == bytes[1].toInt() }
        }

    fun encodeOp(op: Op): ByteArray = header(Kind.OP).bytes(opBytes(op)).toByteArray()

    fun decodeOp(bytes: ByteArray): Op = readOp(open(bytes, Kind.OP).rest())

    fun encodeOps(ops: List<Op>): ByteArray {
        require(ops.size <= MAX_BATCH) { "too many operations for one block" }
        val w = header(Kind.OPS).u16(ops.size)
        for (op in ops) {
            val one = opBytes(op)
            w.u16(one.size).bytes(one)
        }
        return w.toByteArray()
    }

    /** [ops] as blocks of at most [MAX_BATCH] operations each. */
    fun encodeOpBlocks(ops: List<Op>): List<ByteArray> = ops.chunked(MAX_BATCH).map(::encodeOps)

    fun decodeOps(bytes: ByteArray): List<Op> {
        val r = open(bytes, Kind.OPS)
        val count = r.u16()
        if (count > MAX_BATCH) throw WireException("too many operations")
        val ops = List(count) { readOp(r.bytes(r.u16())) }
        if (r.remaining != 0) throw WireException("trailing data")
        return ops
    }

    fun encodeSummary(summary: Summary): ByteArray {
        require(summary.counts.size <= MAX_AUTHORS) { "too many authors for one summary" }
        val w = header(Kind.SUMMARY).u64(summary.ensembleId).u64(summary.digest).u16(summary.counts.size)
        for ((author, count) in summary.counts.entries.sortedWith(BY_KEY)) w.u64(author).u32(count.toLong())
        return w.toByteArray()
    }

    fun decodeSummary(bytes: ByteArray): Summary {
        val r = open(bytes, Kind.SUMMARY)
        val ensemble = r.u64()
        val digest = r.u64()
        val size = r.u16()
        if (size > MAX_AUTHORS) throw WireException("too many authors")
        val counts = LinkedHashMap<Long, Int>()
        repeat(size) {
            val author = r.u64()
            val count = r.u32()
            if (author == 0L || count !in 1..Int.MAX_VALUE || counts.put(author, count.toInt()) != null) {
                throw WireException("bad summary entry")
            }
        }
        if (r.remaining != 0) throw WireException("trailing data")
        return Summary(ensemble, counts, digest)
    }

    /** A whole state, members and groups ordered by identifier: the same state always gives the same bytes. */
    fun encodeState(state: HierarchyState): ByteArray {
        val w = header(Kind.STATE).u64(state.ensembleId).str(state.name, Names.MAX_BYTES).u64(state.leader ?: 0L)
        rules(w, state.rules)
        w.u16(state.members.size)
        for ((id, member) in state.members.entries.sortedWith(BY_KEY)) {
            w.u64(id).str(member.name, Names.MAX_BYTES).u64(member.group ?: 0L).u8(if (id in state.admins) 1 else 0)
        }
        w.u16(state.groups.size)
        for ((id, group) in state.groups.entries.sortedWith(BY_KEY)) {
            w.u64(id).u64(group.parent).str(group.name, Names.MAX_BYTES).u64(group.leader ?: 0L)
        }
        w.u16(state.deletedGroups.size)
        for (id in state.deletedGroups.sortedWith(UNSIGNED)) w.u64(id)
        return w.toByteArray()
    }

    fun decodeState(bytes: ByteArray): HierarchyState {
        val r = open(bytes, Kind.STATE)
        val ensemble = r.u64()
        val name = r.str()
        val leader = r.u64().takeIf { it != 0L }
        val rules = readRules(r)
        val members = LinkedHashMap<Long, Member>()
        val admins = LinkedHashSet<Long>()
        val memberCount = r.u16()
        if (memberCount > HierarchyState.MAX_MEMBERS) throw WireException("too many members")
        repeat(memberCount) {
            val id = r.u64()
            val member = Member(id, r.str(), r.u64().takeIf { it != 0L })
            val flags = r.u8()
            if (flags > 1 || members.put(id, member) != null) throw WireException("bad member")
            if (flags == 1) admins.add(id)
        }
        val groups = LinkedHashMap<Long, Group>()
        val groupCount = r.u16()
        if (groupCount > HierarchyState.MAX_GROUPS) throw WireException("too many groups")
        repeat(groupCount) {
            val id = r.u64()
            val group = Group(id, r.u64(), r.str(), r.u64().takeIf { it != 0L })
            if (groups.put(id, group) != null) throw WireException("bad group")
        }
        val deleted = LinkedHashSet<Long>()
        repeat(r.u16()) { if (!deleted.add(r.u64())) throw WireException("bad deleted group") }
        if (r.remaining != 0) throw WireException("trailing data")
        val state = HierarchyState(ensemble, name, leader, rules, members, admins, groups, deleted)
        state.inconsistency()?.let { throw WireException("inconsistent state: $it") }
        return state
    }

    /** `ensemble (8) | author (8) | seq (4) | clock (4) | change code (1) | change`: what the author signs. */
    internal fun opContent(op: Op): ByteArray {
        val w = ByteWriter().u64(op.ensembleId).u64(op.author).u32(op.seq.toLong()).u32(op.clock)
        when (val c = op.change) {
            is Change.CreateEnsemble -> w.u8(CREATE_ENSEMBLE).bytes(genesisBytes(c))
            is Change.AdmitMember -> w.u8(ADMIT_MEMBER).u64(c.member).str(c.name, Names.MAX_BYTES)
            is Change.RemoveMember -> w.u8(REMOVE_MEMBER).u64(c.member)
            is Change.AddAdmin -> w.u8(ADD_ADMIN).u64(c.member)
            is Change.RemoveAdmin -> w.u8(REMOVE_ADMIN).u64(c.member)
            is Change.CreateGroup -> w.u8(CREATE_GROUP).u64(c.group).u64(c.parent).str(c.name, Names.MAX_BYTES)
            is Change.RenameGroup -> w.u8(RENAME_GROUP).u64(c.group).str(c.name, Names.MAX_BYTES)
            is Change.DeleteGroup -> w.u8(DELETE_GROUP).u64(c.group)
            is Change.AssignMember -> w.u8(ASSIGN_MEMBER).u64(c.member).u64(c.group)
            is Change.UnassignMember -> w.u8(UNASSIGN_MEMBER).u64(c.member)
            is Change.SetGroupLeader -> w.u8(SET_GROUP_LEADER).u64(c.group).u64(c.member)
            is Change.ClearGroupLeader -> w.u8(CLEAR_GROUP_LEADER).u64(c.group)
            is Change.SetEnsembleLeader -> w.u8(SET_ENSEMBLE_LEADER).u64(c.member)
        }
        return w.toByteArray()
    }

    /** `nonce (8) | name | creator name | rules`: with the creator, what the identifier of the ensemble is made of. */
    internal fun genesisBytes(c: Change.CreateEnsemble): ByteArray {
        val w = ByteWriter().u64(c.nonce).str(c.name, Names.MAX_BYTES).str(c.creatorName, Names.MAX_BYTES)
        rules(w, c.rules)
        return w.toByteArray()
    }

    /** `content length (2) | content | key length (1) | key | signature length (1) | signature`; 0 and 0 unsigned. */
    private fun opBytes(op: Op): ByteArray {
        val key = op.proof?.publicKey ?: ByteArray(0)
        val signature = op.proof?.signature ?: ByteArray(0)
        require(key.size <= MAX_KEY_BYTES && signature.size <= MAX_SIGNATURE_BYTES) { "proof too large" }
        val w = ByteWriter().u16(op.content.size).bytes(op.content)
        return w.u8(key.size).bytes(key).u8(signature.size).bytes(signature).toByteArray()
    }

    private fun readOp(bytes: ByteArray): Op {
        val outer = ByteReader(bytes)
        val content = outer.bytes(outer.u16())
        val key = outer.bytes(outer.u8())
        val signature = outer.bytes(outer.u8())
        if (outer.remaining != 0) throw WireException("trailing data after an operation")
        if (key.size > MAX_KEY_BYTES || signature.size > MAX_SIGNATURE_BYTES) throw WireException("proof too large")
        if (key.isEmpty() != signature.isEmpty()) throw WireException("half a proof")
        val r = ByteReader(content)
        val ensemble = r.u64()
        val author = r.u64()
        val seq = r.u32()
        val clock = r.u32()
        if (seq !in 1..Int.MAX_VALUE) throw WireException("bad operation number")
        val change = readChange(r)
        if (r.remaining != 0) throw WireException("trailing data in an operation")
        val op = Op(ensemble, author, seq.toInt(), clock, change, if (key.isEmpty()) null else OpProof(key, signature))
        if (op.isMalformed) throw WireException("malformed operation")
        // The proof signs these exact bytes: a second way to write the same operation is not this operation.
        if (!op.content.contentEquals(content)) throw WireException("operation not in its one form")
        return op
    }

    private fun readChange(r: ByteReader): Change =
        when (r.u8()) {
            CREATE_ENSEMBLE -> {
                Change.CreateEnsemble(r.u64(), r.str(), r.str(), readRules(r))
            }

            ADMIT_MEMBER -> {
                Change.AdmitMember(r.u64(), r.str())
            }

            REMOVE_MEMBER -> {
                Change.RemoveMember(r.u64())
            }

            ADD_ADMIN -> {
                Change.AddAdmin(r.u64())
            }

            REMOVE_ADMIN -> {
                Change.RemoveAdmin(r.u64())
            }

            CREATE_GROUP -> {
                r.u64().let { group ->
                    r.u64().let { parent -> Change.CreateGroup(group, r.str(), parent) }
                }
            }

            RENAME_GROUP -> {
                Change.RenameGroup(r.u64(), r.str())
            }

            DELETE_GROUP -> {
                Change.DeleteGroup(r.u64())
            }

            ASSIGN_MEMBER -> {
                Change.AssignMember(r.u64(), r.u64())
            }

            UNASSIGN_MEMBER -> {
                Change.UnassignMember(r.u64())
            }

            SET_GROUP_LEADER -> {
                Change.SetGroupLeader(r.u64(), r.u64())
            }

            CLEAR_GROUP_LEADER -> {
                Change.ClearGroupLeader(r.u64())
            }

            SET_ENSEMBLE_LEADER -> {
                Change.SetEnsembleLeader(r.u64())
            }

            else -> {
                throw WireException("unknown change")
            }
        }

    /**
     * `flags (1) | n (1) | n × (operation, role, reach bits) | m (1) | m × (role, audience bits)`: only the cells
     * that are not empty, in the order of the codes.
     */
    private fun rules(
        w: ByteWriter,
        rules: HierarchyRules,
    ) {
        w.u8(if (rules.signedOps) SIGNED_OPS else 0)
        val rights =
            Operation.entries.flatMap { op ->
                Role.entries.map { role -> Triple(op, role, rules.rights.mask(op, role)) }
            }
        val granted = rights.filter { it.third != 0 }
        w.u8(granted.size)
        for ((op, role, mask) in granted) w.u8(op.code).u8(role.code).u8(mask)
        val seen = Role.entries.map { it to rules.visibility.mask(it) }.filter { it.second != 0 }
        w.u8(seen.size)
        for ((role, mask) in seen) w.u8(role.code).u8(mask)
    }

    private fun readRules(r: ByteReader): HierarchyRules {
        val flags = r.u8()
        // A rule this version does not know cannot be applied the same way as on the phone that wrote it.
        if (flags and SIGNED_OPS.inv() != 0) throw WireException("unknown rule")
        var rights = RightsTable.NONE
        repeat(r.u8()) {
            val op = Operation.of(r.u8()) ?: throw WireException("unknown operation in the rights")
            val role = Role.of(r.u8()) ?: throw WireException("unknown role in the rights")
            val mask = r.u8()
            if (mask == 0 || mask and Reach.ALL_BITS.inv() != 0) throw WireException("unknown reach")
            rights = rights.withMask(op, role, mask)
        }
        var visibility = VisibilityTable.NONE
        repeat(r.u8()) {
            val role = Role.of(r.u8()) ?: throw WireException("unknown role in the visibility")
            val mask = r.u8()
            if (mask == 0 || mask and Audience.ALL_BITS.inv() != 0) throw WireException("unknown audience")
            visibility = visibility.withMask(role, mask)
        }
        return HierarchyRules(rights, visibility, flags and SIGNED_OPS != 0)
    }

    private fun header(kind: Kind) = ByteWriter().u8(VERSION).u8(kind.code)

    private fun open(
        bytes: ByteArray,
        kind: Kind,
    ): ByteReader {
        if (bytes.size > MAX_BLOCK) throw WireException("block too large")
        val r = ByteReader(bytes)
        if (r.u8() != VERSION) throw WireException("unknown version")
        if (r.u8() != kind.code) throw WireException("not a ${kind.name.lowercase()} block")
        return r
    }

    internal val UNSIGNED: Comparator<Long> = Comparator { a, b -> java.lang.Long.compareUnsigned(a, b) }
    private val BY_KEY: Comparator<Map.Entry<Long, *>> = Comparator { a, b -> UNSIGNED.compare(a.key, b.key) }

    private const val MAX_BLOCK = 512 * 1024
    private const val CREATE_ENSEMBLE = 1
    private const val ADMIT_MEMBER = 2
    private const val REMOVE_MEMBER = 3
    private const val ADD_ADMIN = 4
    private const val REMOVE_ADMIN = 5
    private const val CREATE_GROUP = 6
    private const val RENAME_GROUP = 7
    private const val DELETE_GROUP = 8
    private const val ASSIGN_MEMBER = 9
    private const val UNASSIGN_MEMBER = 10
    private const val SET_GROUP_LEADER = 11
    private const val CLEAR_GROUP_LEADER = 12
    private const val SET_ENSEMBLE_LEADER = 13
}
