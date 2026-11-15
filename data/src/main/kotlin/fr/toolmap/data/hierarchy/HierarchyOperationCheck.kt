package fr.toolmap.data.hierarchy

import android.content.Context
import android.os.Build
import android.util.Log
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.DissolutionTrace
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.hierarchy.ProofVerdict
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.ShareOutcome
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.problem.catchReported
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The check of the « Opération » controller on an emulator or a phone (P39.7, ADR-129; P39.9, ADR-131): one device,
 * no server, no screen. Asked by `adb shell dumpsys activity fr.toolmap/.MainActivity hierarchy-operation` (builds
 * with the testing tools only), three times, with the process killed in between
 * (`tools/emulator/hierarchy-operation.sh`):
 *
 * 1. **create**: an [OperationController] over the real Keystore creates an operation, makes a group, places this
 *    phone in it as its leader, makes this phone the leader of the operation, shares a waypoint and writes messages
 *    in two threads; all is stored;
 * 2. **reload**, in a new process: a new controller opens it again from the storage and compares (same operation,
 *    same name, same group and posts, same waypoint, same operations, same messages: R2), then dissolves it and
 *    checks that only the trace is left (R1): no folder and no key of the operation, one trace and its key, which
 *    names nothing and holds no message;
 * 3. **trace**, in a new process: the trace reads back (its key in the Keystore outlived the process), still names
 *    nothing; then it is wiped, and nothing is left at all.
 *
 * Lines in logcat, tag [HierarchyKeysCheck.TAG]: `hierarchy-operation …`, one `PASS phase=…` or `FAIL …` per phase,
 * then `done`. Lives in a folder and under key aliases of its own: it never touches a real operation.
 */
object HierarchyOperationCheck {
    const val DUMP_ARGUMENT = "hierarchy-operation"
    private const val FOLDER = "hierarchy/check-operation"
    internal const val ALIAS_PREFIX = "toolmap.hierarchy.checkop."
    private const val EXPECTED = "expected.txt"
    private const val TRACE = "trace.txt"
    private const val OPERATION = "Contrôle"
    private const val GROUP = "Groupe A"
    private const val ME = "Téléphone"

    /** Two threads: the whole operation, and the group (R2). */
    private val TO_ALL = listOf("Départ à 7 h", "Rendez-vous au col")
    private const val TO_GROUP = "Groupe A au complet"
    private const val WAIT_ROUNDS = 50
    private const val WAIT_MS = 100L

    private val running = AtomicBoolean(false)

    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val files = context.applicationContext.filesDir
        Thread({
            try {
                catchReported("hierarchy.operation.check") {
                    run(File(files, FOLDER), AndroidKeyVault(), Build.VERSION.SDK_INT) {
                        Log.i(HierarchyKeysCheck.TAG, it)
                    }
                }
            } finally {
                running.set(false)
            }
        }, "hierarchy-operation").start()
        return true
    }

    /** One phase: the second if the first left what it stored in [root], the first otherwise. True when it passed. */
    internal fun run(
        root: File,
        vault: KeyVault,
        api: Int,
        say: (String) -> Unit,
    ): Boolean {
        val expected = File(root, EXPECTED)
        val phase =
            when {
                File(root, TRACE).isFile -> "trace"
                expected.isFile -> "reload"
                else -> "create"
            }
        val problems = ArrayList<String>()
        say("hierarchy-operation phase=$phase api=$api")
        val passed =
            catchExpected("hierarchy.operation.check.run", KeyVaultException::class, IOException::class) {
                runBlocking {
                    when (phase) {
                        "create" -> create(root, vault, say, problems)
                        "reload" -> reload(root, vault, say, problems)
                        else -> trace(root, vault, say, problems)
                    }
                }
            }.getOrElse {
                problems += "${it.javaClass.simpleName}: ${it.message}"
                false
            } && problems.isEmpty()
        val verdict = if (passed) "PASS phase=$phase" else "FAIL phase=$phase ${problems.joinToString("; ")}"
        say("hierarchy-operation $verdict")
        say("hierarchy-operation done")
        return passed
    }

    private fun storage(
        root: File,
        vault: KeyVault,
    ) = OperationStorage(root, vault, ALIAS_PREFIX)

    private fun controller(
        root: File,
        vault: KeyVault,
    ) = OperationController(storage(root, vault), null, { null })

    /** The prefix of the keys of the traces of this check's storage. */
    internal val TRACE_ALIAS_PREFIX: String = OperationStorage.traceAliasPrefix(ALIAS_PREFIX)

    private suspend fun create(
        root: File,
        vault: KeyVault,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ): Boolean {
        OperationStorage(root, vault, ALIAS_PREFIX).wipeAll()
        root.mkdirs()
        val started = System.nanoTime()
        val c = controller(root, vault)
        val result = c.create(OPERATION, ME)
        if (result != StartResult.STARTED) return false.also { problems += "not created: $result" }
        val self = c.view.value.self ?: return false.also { problems += "no member" }
        val (group, refused) = c.createGroup(GROUP)
        if (group == null) return false.also { problems += "group refused: $refused" }
        c.place(self, group)?.let { problems += "not placed: $it" }
        c.setPost(group, Post.LEADER, self)?.let { problems += "not leader of the group: $it" }
        c.setPost(fr.toolmap.core.hierarchy.HierarchyState.ROOT, Post.LEADER, self)?.let {
            problems += "not leader: $it"
        }
        val (put, objectId) = c.putObject(ShareKind.WAYPOINT, Channel.ENSEMBLE, byteArrayOf(1, 2, 3))
        if (put != ShareOutcome.QUEUED && put != ShareOutcome.SENT) problems += "waypoint not kept: $put"
        // No server: the messages wait for a link, and are kept in their threads all the same (R2).
        for (text in TO_ALL) {
            val sent = c.sendMessage(Channel.ENSEMBLE, text)
            if (sent != ShareOutcome.QUEUED && sent != ShareOutcome.SENT) problems += "message not kept: $sent"
        }
        val toGroup = c.sendMessage(Channel.unit(group), TO_GROUP)
        if (toGroup != ShareOutcome.QUEUED && toGroup != ShareOutcome.SENT) problems += "message not kept: $toGroup"
        val digest = c.withNode { fingerprint(HierarchyWire.encodeState(it.state)) }
        c.close()
        val id = c.ids().singleOrNull() ?: return false.also { problems += "not one stored operation: ${c.ids().size}" }
        say("hierarchy-operation created and stored in ${(System.nanoTime() - started) / 1_000_000} ms")
        SealedFile.writeAtomically(File(root, EXPECTED), "$id $digest $objectId $group".toByteArray(Charsets.US_ASCII))
        return true
    }

    private suspend fun reload(
        root: File,
        vault: KeyVault,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ): Boolean {
        val (id, digest, objectId, group) = File(root, EXPECTED).readText(Charsets.US_ASCII).trim().split(" ")
        File(root, EXPECTED).delete()
        val started = System.nanoTime()
        val c = controller(root, vault)
        val result = c.open(id)
        if (result != StartResult.STARTED) return false.also { problems += "not opened again: $result" }
        val v = c.view.value
        if (v.phase != OperationPhase.PLACED) problems += "phase ${v.phase}"
        if (v.operationName != OPERATION) problems += "another name"
        val g = v.groups.singleOrNull()
        if (g == null || g.id.toString() != group || g.name != GROUP || g.leader != v.self) problems += "another group"
        if (v.hierarchy?.leader != v.self) problems += "not the leader of the operation any more"
        if (v.objects.map { it.objectId.toString() } != listOf(objectId)) problems += "other objects"
        if (c.withNode { fingerprint(HierarchyWire.encodeState(it.state)) } != digest) problems += "another state"
        val threads = v.threads.mapValues { (_, list) -> list.map { it.text } }
        if (threads[Channel.ENSEMBLE] != TO_ALL) problems += "other messages to all: ${threads[Channel.ENSEMBLE]?.size}"
        if (threads[Channel.unit(group.toLong())] != listOf(TO_GROUP)) problems += "other messages to the group"
        say(
            "hierarchy-operation reloaded in a new process in ${(System.nanoTime() - started) / 1_000_000} ms: same " +
                "operation, ${threads.values.sumOf { it.size }} messages in ${threads.size} threads",
        )
        val ensembleId = v.ensembleId ?: return false.also { problems += "no operation" }
        c.dissolve()?.let { problems += "not dissolved: $it" }
        for (i in 0 until WAIT_ROUNDS) {
            if (c.view.value.phase == OperationPhase.ENDED && c.ids().isEmpty()) break
            Thread.sleep(WAIT_MS)
        }
        val ended = c.view.value
        if (ended.ending != Ending.DISSOLVED) problems += "not ended: ${ended.phase}"
        if (ended.traceKeptUntilMs == null) problems += "no trace kept"
        if (c.ids().isNotEmpty() || File(root, id).exists()) problems += "folder left after the dissolution"
        if (vault.aliases(ALIAS_PREFIX).isNotEmpty()) problems += "keys of the operation left after the dissolution"
        val traces = storage(root, vault).traces
        if (traces.ids().size != 1 || vault.aliases(TRACE_ALIAS_PREFIX).size != 1) {
            problems += "not one trace and its key: ${traces.ids().size} ${vault.aliases(TRACE_ALIAS_PREFIX).size}"
        }
        val trace = traces.list(System.currentTimeMillis()).singleOrNull()
        if (trace == null) return false.also { problems += "trace not read back" }
        checkTrace(trace, ensembleId, problems)
        SealedFile.writeAtomically(File(root, TRACE), java.lang.Long.toUnsignedString(ensembleId).toByteArray())
        say(
            "hierarchy-operation dissolved: no folder and no key of the operation left; one trace of " +
                "${trace.encode().size} bytes, kept ${(trace.expiresAtMs - trace.dissolvedAtMs) / DAY_MS} days, " +
                "names nothing, holds no message",
        )
        return true
    }

    private fun trace(
        root: File,
        vault: KeyVault,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ): Boolean {
        val ensembleId = java.lang.Long.parseUnsignedLong(File(root, TRACE).readText(Charsets.US_ASCII).trim())
        File(root, TRACE).delete()
        val storage = storage(root, vault)
        val trace = storage.traces.list(System.currentTimeMillis()).singleOrNull()
        if (trace == null) return false.also { problems += "the trace did not outlive the process" }
        checkTrace(trace, ensembleId, problems)
        say("hierarchy-operation trace read back in a new process: same operation, its key in the Keystore")
        storage.wipeAll()
        if (vault.aliases(TRACE_ALIAS_PREFIX).isNotEmpty() || vault.aliases(ALIAS_PREFIX).isNotEmpty()) {
            problems += "keys left after wiping the trace"
        }
        if (storage.traces.ids().isNotEmpty() || storage.ids().isNotEmpty()) problems += "files left after wiping"
        say("hierarchy-operation trace wiped: no key left in the Keystore, no folder left")
        return true
    }

    /** The trace of [ensembleId], its proof the creator's, a week long, and nothing in it of a name or a message. */
    private fun checkTrace(
        trace: DissolutionTrace,
        ensembleId: Long,
        problems: MutableList<String>,
    ) {
        if (trace.ensembleId != ensembleId) problems += "trace of another operation"
        if (trace.expiresAtMs - trace.dissolvedAtMs != DissolutionTrace.KEPT_MS) problems += "not kept a week"
        if (trace.proof.check(ensembleId) != ProofVerdict.VALID) problems += "proof refused"
        val bytes = trace.encode()
        for (text in listOf(OPERATION, GROUP, ME, TO_GROUP) + TO_ALL) {
            if (contains(bytes, text.toByteArray(Charsets.UTF_8))) problems += "the trace holds a name or a message"
        }
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean = (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }

    private const val DAY_MS = 24 * 3600 * 1000L

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
