package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.DissolutionProof
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchySnapshot
import fr.toolmap.core.hierarchy.KeyRing
import fr.toolmap.core.hierarchy.NameBook
import fr.toolmap.core.hierarchy.Op
import fr.toolmap.core.hierarchy.OperationLinkState
import fr.toolmap.core.hierarchy.PhoneKeys
import fr.toolmap.core.hierarchy.SharedObjects
import fr.toolmap.core.hierarchy.memberId
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.problem.catchExpected
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * What a phone holds of one operation in memory, as the engine has it: the copy of the operations, the content keys,
 * the names, the objects still shown. The transport (next step) works on it and hands it to [StoredOperation.save]
 * after each change.
 */
class OperationContents(
    val replica: HierarchyReplica,
    val ring: KeyRing,
    val names: NameBook,
    val objects: SharedObjects,
) {
    init {
        require(ring.ensembleId == replica.ensembleId && objects.ensembleId == replica.ensembleId) {
            "parts of two operations"
        }
        require(ring.self == replica.self) { "parts of two phones" }
    }

    val ensembleId: Long get() = replica.ensembleId

    companion object {
        /** Empty keys, names and objects around [replica], whose phone holds [keys]. */
        fun around(
            replica: HierarchyReplica,
            keys: PhoneKeys,
        ) = OperationContents(replica, KeyRing(replica.ensembleId, keys), NameBook(), SharedObjects(replica.ensembleId))
    }
}

/** Why an operation is over for this phone: everything it held of it is then wiped. */
enum class Ending {
    /** It left, or was taken out of the operation by its creator. */
    OUT,

    /** The operation is dissolved. */
    DISSOLVED,

    /** The app wiped it ([StoredOperation.wipe]). */
    WIPED,
}

/** The parts of a stored operation, each in a file of its own. */
enum class StoredPart(
    internal val file: String,
) {
    OPS("ops.sealed"),
    KEYS("keys.sealed"),
    NAMES("names.sealed"),
    OBJECTS("objects.sealed"),
}

sealed interface LoadResult {
    /** An identity and nothing else yet: the operation is still to create or to join. */
    data object Empty : LoadResult

    /**
     * What was stored. The parts of [refused] could not be read (damaged, cut, not ours: said to whoever debugs) and
     * are empty: the operation catches up through the network, like a phone that was away.
     */
    class Loaded(
        val contents: OperationContents,
        val refused: Set<StoredPart>,
    ) : LoadResult

    /**
     * Over for this phone; everything was wiped. Dissolved while this phone was a member: but its trace, kept until
     * [traceKeptUntilMs] (P39.9, ADR-131); `null` when no trace is kept.
     */
    class Ended(
        val ending: Ending,
        val traceKeptUntilMs: Long? = null,
    ) : LoadResult

    /** The key store refused for now (said to whoever debugs); nothing was changed, try again later. */
    data object Unavailable : LoadResult
}

sealed interface SaveResult {
    data object Saved : SaveResult

    /**
     * Over for this phone: instead of being saved, everything was wiped, but the trace of a dissolution while this
     * phone was a member, kept until [traceKeptUntilMs] ([LoadResult.Ended]).
     */
    class Ended(
        val ending: Ending,
        val traceKeptUntilMs: Long? = null,
    ) : SaveResult

    /** The disk or the key store refused (said to whoever debugs); the previous version of each file stays. */
    data object Failed : SaveResult
}

/**
 * The storage of one operation on this phone ([OperationStorage]): load it, save it after a change, wipe it. Each
 * part is a file sealed with the operation's storage key (AES-256-GCM, bound to the operation, the part and the
 * ensemble), written whole or not at all; the storage key is sealed by the vault. A part that did not change is not
 * written again.
 *
 * The state is never stored: it is computed again from the operations ([HierarchySnapshot.restoreReplica]).
 *
 * Beside the parts: what the transport keeps ([saveLink]) and the messages of the threads ([saveMessages], the
 * user's answer R2), each in a sealed file of its own. When the operation is dissolved while this phone is a member,
 * everything is wiped but a trace ([endDissolved], R1).
 */
class StoredOperation internal constructor(
    private val dir: File,
    /** The identifier of this storage on this phone: not the ensemble's, which is only known once created or joined. */
    val id: String,
    /** The keys of this phone for this operation. */
    val keys: PhoneKeys,
    private var identity: Identity,
    private val vault: KeyVault,
    private val aliasPrefix: String,
    private val sealing: VaultSealingKey,
    private val random: SecureRandom,
    private val traces: DissolutionTraces,
    private val clock: () -> Long,
) {
    val self: Long = keys.memberId

    /** The ensemble, once created or joined (first [save]); `null` before. */
    val ensembleId: Long? get() = identity.ensembleId.takeIf { it != 0L }

    /** Whether the agreement key is in the vault (`false`: in a file sealed by the vault, below API 31). */
    val agreesInVault: Boolean get() = (keys as? VaultPhoneKeys)?.agreesInVault == true

    private var storageKey: ByteArray? = null
    private var wiped = false
    private var savedOps: List<Op>? = null
    private val savedDigests = HashMap<StoredPart, List<Byte>>()
    private var savedMessages: List<Byte>? = null

    /** Until when the trace this storage left at its end is kept; `null` when it left none. */
    private var traceKeptUntilMs: Long? = null

    /** The contents of a new copy of [ensembleId], to join it (the identifier comes with the invitation). */
    fun join(ensembleId: Long): OperationContents =
        OperationContents.around(HierarchyReplica(ensembleId, self, keys), keys)

    /** Reads back what [save] wrote. */
    @Synchronized
    fun load(): LoadResult {
        if (wiped) return LoadResult.Ended(Ending.WIPED)
        val ensembleId = ensembleId ?: return LoadResult.Empty
        val key = storageKey() ?: return LoadResult.Unavailable
        val refused = HashSet<StoredPart>()

        fun read(part: StoredPart): ByteArray? =
            catchExpected("hierarchy.store.read", IOException::class) {
                SealedFile.open(key, File(dir, part.file).readBytes(), aad(part, ensembleId))
            }.getOrElse {
                refused += part
                null
            }

        fun <T> decode(
            part: StoredPart,
            block: () -> T,
        ): T? =
            catchExpected("hierarchy.store.decode", WireException::class) { block() }.getOrElse {
                refused += part
                null
            }

        val ops = read(StoredPart.OPS)?.let { decode(StoredPart.OPS) { HierarchySnapshot.decodeOps(it, ensembleId) } }
        val replica = HierarchySnapshot.restoreReplica(ensembleId, self, ops.orEmpty(), keys)
        val kept = replica.ops().size
        if (ops != null && kept < ops.size) {
            // Sealed by this phone, so authentic when written: an operation now refused is damage or a bug.
            Problems.unexpected(
                "hierarchy.store.ops_refused",
                "stored operations not taken back",
                "lost" to ops.size - kept,
            )
        }
        val contents = OperationContents.around(replica, keys)
        read(StoredPart.KEYS)?.let { decode(StoredPart.KEYS) { HierarchySnapshot.restoreKeys(contents.ring, it) } }
        read(StoredPart.NAMES)?.let { decode(StoredPart.NAMES) { HierarchySnapshot.restoreNames(contents.names, it) } }
        read(StoredPart.OBJECTS)?.let {
            decode(StoredPart.OBJECTS) { HierarchySnapshot.restoreObjects(contents.objects, it) }
        }
        endingOf(replica)?.let { ending ->
            end(ending, replica)
            return LoadResult.Ended(ending, traceKeptUntilMs)
        }
        // A refused part is written again at the next save, over the damaged file.
        savedOps = if (StoredPart.OPS in refused) null else replica.ops()
        savedDigests.clear()
        for (part in StoredPart.entries) {
            if (part != StoredPart.OPS && part !in refused) savedDigests[part] = digest(encode(part, contents))
        }
        return LoadResult.Loaded(contents, refused)
    }

    /**
     * Writes what changed in [contents] since the last load or save. First, the keys and objects of the channels this
     * phone no longer reads are forgotten in [contents] itself ([HierarchySnapshot.forgetUnread]); when the operation
     * is over for this phone (it left or was taken out, or the operation is dissolved), everything is wiped instead.
     */
    @Synchronized
    fun save(contents: OperationContents): SaveResult {
        if (wiped) return SaveResult.Ended(Ending.WIPED)
        require(contents.replica.self == self) { "contents of another phone" }
        val bound = ensembleId
        require(bound == null || bound == contents.ensembleId) { "contents of another operation" }
        endingOf(contents.replica)?.let { ending ->
            end(ending, contents.replica)
            return SaveResult.Ended(ending, traceKeptUntilMs)
        }
        val state = contents.replica.state
        // Only once this phone knows where it stands: before it is let in, or while its operations are being caught
        // up after a damaged file, it reads no channel and would forget every key and object it holds.
        if (self in state.members) HierarchySnapshot.forgetUnread(state, contents.ring, contents.objects)
        val key = storageKey() ?: return SaveResult.Failed
        val ok =
            catchExpected("hierarchy.store.write", IOException::class) {
                if (bound == null) {
                    // Every part is written once before the header names the ensemble: a header that names it
                    // always has its parts, and a missing part is damage.
                    for (part in StoredPart.entries) write(part, contents, key)
                    val next = identity.bound(contents.ensembleId)
                    SealedFile.writeAtomically(File(dir, OperationStorage.IDENTITY_FILE), next.encode())
                    identity = next
                } else {
                    for (part in StoredPart.entries) write(part, contents, key)
                }
            }
        return if (ok.isSuccess) SaveResult.Saved else SaveResult.Failed
    }

    /**
     * Writes what the transport keeps beside the engine's parts (the room, the server, the invitation keys, the cards
     * of the invitation: [fr.toolmap.core.hierarchy.OperationLinkState]), sealed like them; only once the operation
     * is bound ([save] done once). `false` when the disk or the key store refused (said to whoever debugs).
     */
    @Synchronized
    fun saveLink(bytes: ByteArray): Boolean {
        if (wiped) return false
        val ensembleId = ensembleId ?: return false
        val key = storageKey() ?: return false
        return catchExpected("hierarchy.store.link.write", IOException::class) {
            SealedFile.writeAtomically(File(dir, LINK_FILE), SealedFile.seal(key, bytes, linkAad(ensembleId), random))
        }.isSuccess
    }

    /** What [saveLink] wrote; `null` when there is none, or it is damaged (said to whoever debugs). */
    @Synchronized
    fun loadLink(): ByteArray? {
        if (wiped) return null
        val ensembleId = ensembleId ?: return null
        val file = File(dir, LINK_FILE)
        if (!file.isFile) return null
        val key = storageKey() ?: return null
        return catchExpected("hierarchy.store.link.read", IOException::class) {
            SealedFile.open(key, file.readBytes(), linkAad(ensembleId))
        }.getOrNull()
    }

    private fun linkAad(ensembleId: Long): ByteArray =
        "toolmap-hierarchy-link-v1:$id:${java.lang.Long.toUnsignedString(
            ensembleId,
            16,
        )}".toByteArray(Charsets.US_ASCII)

    /**
     * Writes the messages of the threads ([HierarchySnapshot.encodeMessages], the user's answer R2), sealed like the
     * parts, when they changed; only once the operation is bound. `false` when the disk or the key store refused
     * (said to whoever debugs).
     */
    @Synchronized
    fun saveMessages(bytes: ByteArray): Boolean {
        if (wiped) return false
        val ensembleId = ensembleId ?: return false
        val digest = digest(bytes)
        if (digest == savedMessages) return true
        val key = storageKey() ?: return false
        val file = File(dir, MESSAGES_FILE)
        return catchExpected("hierarchy.store.messages.write", IOException::class) {
            SealedFile.writeAtomically(file, SealedFile.seal(key, bytes, messagesAad(ensembleId), random))
            savedMessages = digest
        }.isSuccess
    }

    /**
     * What [saveMessages] wrote; `null` when there is none (an operation stored before the messages were kept, by the
     * version 0.4.250: nothing to say), or it is damaged (said to whoever debugs; the next save writes it again).
     */
    @Synchronized
    fun loadMessages(): ByteArray? {
        if (wiped) return null
        val ensembleId = ensembleId ?: return null
        val file = File(dir, MESSAGES_FILE)
        if (!file.isFile) return null
        val key = storageKey() ?: return null
        return catchExpected("hierarchy.store.messages.read", IOException::class) {
            SealedFile.open(key, file.readBytes(), messagesAad(ensembleId))
        }.getOrNull()?.also { savedMessages = digest(it) }
    }

    private fun messagesAad(ensembleId: Long): ByteArray =
        "toolmap-hierarchy-messages-v1:$id:${java.lang.Long.toUnsignedString(ensembleId, 16)}"
            .toByteArray(Charsets.US_ASCII)

    /**
     * The operation is dissolved: when [proof] is given (this phone was a member when it was dissolved), its trace is
     * kept a week from [dissolvedAtMs] ([DissolutionTraces]) with what the transport keeps ([link], or what
     * [saveLink] wrote); then everything else is wiped, as [wipe] does. Until when the trace is kept, or `null` when
     * none is (no proof, nothing to reach the room with, or the key store refused: said to whoever debugs).
     */
    @Synchronized
    fun endDissolved(
        proof: DissolutionProof?,
        dissolvedAtMs: Long,
        link: OperationLinkState? = null,
    ): Long? {
        if (wiped) return traceKeptUntilMs
        if (proof != null) {
            val reach = link ?: readLink()
            if (reach != null && reach.ensembleId == proof.ensembleId) {
                val now = clock()
                val trace = DissolutionTrace.of(proof, reach, minOf(dissolvedAtMs, now), random)
                traceKeptUntilMs = traces.keep(trace, now)?.expiresAtMs
            }
        }
        wipe()
        return traceKeptUntilMs
    }

    private fun readLink(): OperationLinkState? {
        val bytes = loadLink() ?: return null
        return catchExpected("hierarchy.store.link.decode", WireException::class) {
            OperationLinkState.decode(bytes)
        }.getOrNull()
    }

    /** Over for this phone: a trace when dissolved while it was a member (R1), nothing left otherwise. */
    private fun end(
        ending: Ending,
        replica: HierarchyReplica,
    ) {
        if (ending == Ending.DISSOLVED) {
            // Taken out before the dissolution, this phone is no member of the dissolved state: no trace.
            val proof = if (self in replica.state.members) DissolutionProof.of(replica) else null
            endDissolved(proof, clock())
        } else {
            wipe()
        }
    }

    /**
     * Forgets everything of this operation on this phone: the keys in the vault first (what may be left of the files
     * on the flash memory can then no longer be opened), then the folder. Nothing works on this storage after it.
     */
    @Synchronized
    fun wipe() {
        wiped = true
        storageKey?.fill(0)
        storageKey = null
        OperationStorage.deleteKeys(vault, aliasPrefix, id)
        OperationStorage.deleteFolder(dir)
    }

    private fun write(
        part: StoredPart,
        contents: OperationContents,
        key: ByteArray,
    ) {
        if (part == StoredPart.OPS) {
            val ops = contents.replica.ops()
            if (ops == savedOps) return
            writeSealed(part, HierarchySnapshot.encodeOps(ops), contents.ensembleId, key)
            savedOps = ops
            return
        }
        val plain = encode(part, contents)
        val digest = digest(plain)
        if (savedDigests[part] == digest) return
        writeSealed(part, plain, contents.ensembleId, key)
        savedDigests[part] = digest
    }

    private fun writeSealed(
        part: StoredPart,
        plain: ByteArray,
        ensembleId: Long,
        key: ByteArray,
    ) = SealedFile.writeAtomically(File(dir, part.file), SealedFile.seal(key, plain, aad(part, ensembleId), random))

    private fun encode(
        part: StoredPart,
        contents: OperationContents,
    ): ByteArray =
        when (part) {
            StoredPart.OPS -> HierarchySnapshot.encodeOps(contents.replica.ops())
            StoredPart.KEYS -> HierarchySnapshot.encodeKeys(contents.ring)
            StoredPart.NAMES -> HierarchySnapshot.encodeNames(contents.names)
            StoredPart.OBJECTS -> HierarchySnapshot.encodeObjects(contents.objects)
        }

    /**
     * The key that seals the parts, opened by the vault once per process; a new one when its file is missing or
     * damaged (said: the parts sealed with the old one are then refused, and rewritten at the next save). `null` when
     * the vault refuses for now.
     */
    private fun storageKey(): ByteArray? {
        storageKey?.let { return it }
        val file = File(dir, STORAGE_KEY_FILE)
        val aad = "toolmap-hierarchy-storage-key-v1:$id".toByteArray(Charsets.US_ASCII)
        return catchExpected("hierarchy.store.storage_key", KeyVaultException::class, IOException::class) {
            val opened =
                if (file.isFile) {
                    sealing.open(file.readBytes(), aad)?.takeIf { it.size == SealedFile.KEY_BYTES }
                } else {
                    null
                }
            opened ?: run {
                if (file.isFile || ensembleId != null) {
                    Problems.expected("hierarchy.store.storage_key", IOException("storage key damaged or missing"))
                }
                val fresh = ByteArray(SealedFile.KEY_BYTES).also(random::nextBytes)
                SealedFile.writeAtomically(file, sealing.seal(fresh, aad))
                savedOps = null
                savedDigests.clear()
                fresh
            }
        }.getOrNull()?.also { storageKey = it }
    }

    private fun aad(
        part: StoredPart,
        ensembleId: Long,
    ): ByteArray =
        "toolmap-hierarchy-part-v1:$id:${part.name}:${java.lang.Long.toUnsignedString(ensembleId, 16)}"
            .toByteArray(Charsets.US_ASCII)

    /**
     * Over for this phone: the operation is dissolved, or this phone was let in once (the operations held say so)
     * and is no longer a member. A phone that waits to be let in is not.
     */
    private fun endingOf(replica: HierarchyReplica): Ending? {
        val state = replica.state
        return when {
            state.dissolved -> Ending.DISSOLVED
            replica.signingKeyOf(self) != null && self !in state.members -> Ending.OUT
            else -> null
        }
    }

    private fun digest(bytes: ByteArray): List<Byte> = MessageDigest.getInstance("SHA-256").digest(bytes).asList()

    companion object {
        internal const val STORAGE_KEY_FILE = "storage.key"
        internal const val LINK_FILE = "link.sealed"
        internal const val MESSAGES_FILE = "messages.sealed"
    }
}
