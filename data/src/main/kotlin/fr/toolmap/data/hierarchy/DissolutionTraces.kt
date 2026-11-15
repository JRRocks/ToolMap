package fr.toolmap.data.hierarchy

import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.p2p.WireException
import fr.toolmap.core.problem.Problems
import fr.toolmap.core.problem.catchExpected
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/**
 * The traces this phone keeps of the operations dissolved while it was a member of them (P39.9, ADR-131, the user's
 * answer R1 of 2026-11-15): one file per operation in [root], sealed by an AES key of its own in the [vault], a week
 * from the dissolution ([DissolutionTrace.KEPT_MS]); then wiped by itself, the key first, at the first look after
 * that ([list]): what is left of the file can then no longer be opened. A trace holds no position, no name, no message,
 * no content key ([DissolutionTrace]). Under the folder of the operations ([OperationStorage]), so out of every backup.
 */
class DissolutionTraces internal constructor(
    private val root: File,
    private val vault: KeyVault,
    /** Every key of a trace in the vault starts so, then the trace's identifier. */
    internal val aliasPrefix: String,
    private val random: SecureRandom,
) {
    /** The identifiers of the trace files, expired or not. */
    @Synchronized
    internal fun ids(): List<String> =
        root
            .listFiles { f -> f.isFile && FILE.matches(f.name) }
            .orEmpty()
            .map { it.name.removeSuffix(SUFFIX) }
            .sorted()

    /**
     * The traces kept now, oldest dissolution first. Those over a week old are wiped first, and so is a trace that can
     * no longer be read (said to whoever debugs) or a key whose file is gone.
     */
    @Synchronized
    fun list(nowMs: Long): List<DissolutionTrace> {
        val kept = ArrayList<DissolutionTrace>()
        val ids = ids()
        for (id in ids) {
            val trace = read(id)
            if (trace == null || trace.isExpired(nowMs)) discard(id) else kept += trace
        }
        catchExpected("hierarchy.trace.orphan_keys", KeyVaultException::class) {
            for (alias in vault.aliases(aliasPrefix)) if (alias.removePrefix(aliasPrefix) !in ids) vault.delete(alias)
        }
        return kept.sortedBy { it.dissolvedAtMs }
    }

    /**
     * Keeps [trace]; one per operation: an operation already kept keeps its trace and its date. The trace kept, or
     * `null` when it is already a week old or the disk or the key store refused (said to whoever debugs; nothing is
     * left behind).
     */
    @Synchronized
    fun keep(
        trace: DissolutionTrace,
        nowMs: Long,
    ): DissolutionTrace? {
        if (trace.isExpired(nowMs)) return null
        list(nowMs).firstOrNull { it.ensembleId == trace.ensembleId }?.let { return it }
        val id = newId()
        val written =
            catchExpected("hierarchy.trace.write", IOException::class, KeyVaultException::class) {
                if (!root.isDirectory && !root.mkdirs()) throw IOException("cannot make the folder of the traces")
                val key = vault.createSealingKey(aliasPrefix + id)
                SealedFile.writeAtomically(File(root, id + SUFFIX), key.seal(trace.encode(), aad(id)))
            }
        if (written.isFailure) {
            discard(id)
            return null
        }
        return trace
    }

    /** Wipes every trace, keys first, and the folder. */
    @Synchronized
    fun wipeAll() {
        for (id in ids()) discard(id)
        catchExpected("hierarchy.trace.wipe_keys", KeyVaultException::class) {
            for (alias in vault.aliases(aliasPrefix)) vault.delete(alias)
        }
        OperationStorage.deleteFolder(root)
    }

    private fun read(id: String): DissolutionTrace? =
        catchExpected("hierarchy.trace.read", IOException::class, KeyVaultException::class, WireException::class) {
            val key = vault.sealingKey(aliasPrefix + id) ?: throw IOException("key of a trace gone")
            val plain = key.open(File(root, id + SUFFIX).readBytes(), aad(id)) ?: throw IOException("trace damaged")
            DissolutionTrace.decode(plain)
        }.getOrNull()

    /** The key first: a file left on the flash memory then opens nowhere. */
    private fun discard(id: String) {
        catchExpected("hierarchy.trace.delete_key", KeyVaultException::class) { vault.delete(aliasPrefix + id) }
        val file = File(root, id + SUFFIX)
        if (file.exists() && !file.delete()) {
            Problems.expected("hierarchy.trace.delete", IOException("trace file not deleted"))
        }
    }

    private fun newId(): String {
        while (true) {
            val id = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            if (!File(root, id + SUFFIX).exists()) return id
        }
    }

    private fun aad(id: String) = "toolmap-hierarchy-trace-v1:$id".toByteArray(Charsets.US_ASCII)

    companion object {
        /** In the folder of the operations, under a name no operation has. */
        const val FOLDER = "traces"
        private const val SUFFIX = ".trace"
        private const val ID_BYTES = 8
        private val FILE = Regex("[0-9a-f]{16}\\.trace")
    }
}
