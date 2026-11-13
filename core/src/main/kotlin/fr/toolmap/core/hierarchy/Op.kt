package fr.toolmap.core.hierarchy

/** What an operation changes. Identifiers are 64-bit values, never 0; names follow [Names]. */
sealed interface Change {
    /** The line of the [RightsTable] this change is checked against; `null` for the creation of the ensemble. */
    val operation: Operation?

    /**
     * The first operation of an ensemble. Its author becomes a member named [creatorName], the first administrator
     * and the leader of the ensemble. The identifier of the ensemble is [OpAuth.ensembleId] of the author and of
     * this change ([nonce] makes it unique): nobody can write another first operation for it.
     */
    data class CreateEnsemble(
        val nonce: Long,
        val name: String,
        val creatorName: String,
        val rules: HierarchyRules = HierarchyRules.DEFAULT,
    ) : Change {
        override val operation: Operation? get() = null
    }

    data class AdmitMember(
        val member: Long,
        val name: String,
    ) : Change {
        override val operation get() = Operation.ADMIT_MEMBER
    }

    /** Takes [member] out of the ensemble: out of its group, of its posts and of the administrators. */
    data class RemoveMember(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.REMOVE_MEMBER
    }

    data class AddAdmin(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.ADD_ADMIN
    }

    data class RemoveAdmin(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.REMOVE_ADMIN
    }

    /** A new group [group] under [parent] ([HierarchyState.ROOT]: directly under the ensemble). */
    data class CreateGroup(
        val group: Long,
        val name: String,
        val parent: Long = HierarchyState.ROOT,
    ) : Change {
        override val operation get() = Operation.CREATE_GROUP
    }

    data class RenameGroup(
        val group: Long,
        val name: String,
    ) : Change {
        override val operation get() = Operation.RENAME_GROUP
    }

    /** Deletes [group]; its members stay in the ensemble, without a group. */
    data class DeleteGroup(
        val group: Long,
    ) : Change {
        override val operation get() = Operation.DELETE_GROUP
    }

    /** Puts [member] in [group], out of the one it was in (it stops leading it). */
    data class AssignMember(
        val member: Long,
        val group: Long,
    ) : Change {
        override val operation get() = Operation.ASSIGN_MEMBER
    }

    data class UnassignMember(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.UNASSIGN_MEMBER
    }

    /** [member], who must be in [group], leads it, in place of the leader it had. */
    data class SetGroupLeader(
        val group: Long,
        val member: Long,
    ) : Change {
        override val operation get() = Operation.SET_GROUP_LEADER
    }

    data class ClearGroupLeader(
        val group: Long,
    ) : Change {
        override val operation get() = Operation.CLEAR_GROUP_LEADER
    }

    data class SetEnsembleLeader(
        val member: Long,
    ) : Change {
        override val operation get() = Operation.SET_ENSEMBLE_LEADER
    }
}

/** `true` when no phone would accept this change whatever the state: an identifier 0, a name [Names] refuses. */
fun Change.isMalformed(): Boolean =
    when (this) {
        is Change.CreateEnsemble -> !Names.valid(name) || !Names.valid(creatorName)
        is Change.AdmitMember -> member == 0L || !Names.valid(name)
        is Change.RemoveMember -> member == 0L
        is Change.AddAdmin -> member == 0L
        is Change.RemoveAdmin -> member == 0L
        is Change.CreateGroup -> group == 0L || !Names.valid(name)
        is Change.RenameGroup -> group == 0L || !Names.valid(name)
        is Change.DeleteGroup -> group == 0L
        is Change.AssignMember -> member == 0L || group == 0L
        is Change.UnassignMember -> member == 0L
        is Change.SetGroupLeader -> member == 0L || group == 0L
        is Change.ClearGroupLeader -> group == 0L
        is Change.SetEnsembleLeader -> member == 0L
    }

/** The proof that an operation comes from its author: its public key and its signature of the operation. */
class OpProof(
    val publicKey: ByteArray,
    val signature: ByteArray,
)

/**
 * One change, as it travels and as it is kept: who asked for it ([author]), its number among the operations of this
 * author ([seq], from 1, without a gap) and the logical clock of the author when it asked ([clock], one more than
 * every operation it knew). The phones of an ensemble apply the operations in the order of ([clock], [author],
 * [seq]), whatever the order they received them in.
 *
 * Two operations are equal when they say the same thing; the [proof] does not count.
 */
class Op(
    val ensembleId: Long,
    val author: Long,
    val seq: Int,
    val clock: Long,
    val change: Change,
    val proof: OpProof? = null,
) {
    /** The bytes that are signed and compared: everything but the proof. */
    internal val content: ByteArray by lazy { HierarchyWire.opContent(this) }

    /** Whether the [proof] is there and is the author's ([OpAuth.verify]); checked once. */
    val authentic: Boolean by lazy { OpAuth.verify(this) }

    internal val isMalformed: Boolean
        get() = author == 0L || seq < 1 || clock !in 1..MAX_CLOCK || change.isMalformed()

    override fun equals(other: Any?): Boolean =
        other is Op &&
            ensembleId == other.ensembleId &&
            author == other.author &&
            seq == other.seq &&
            clock == other.clock &&
            change == other.change

    override fun hashCode(): Int = 31 * (31 * author.hashCode() + seq) + clock.hashCode()

    override fun toString(): String = "Op(${java.lang.Long.toUnsignedString(author)}#$seq @$clock $change)"

    companion object {
        /** The clock travels on 4 bytes. */
        const val MAX_CLOCK = 0xFFFFFFFFL

        /** The order every phone applies the operations in. */
        val ORDER: Comparator<Op> =
            Comparator { a, b ->
                when {
                    a.clock != b.clock -> a.clock.compareTo(b.clock)
                    a.author != b.author -> java.lang.Long.compareUnsigned(a.author, b.author)
                    else -> a.seq.compareTo(b.seq)
                }
            }
    }
}
