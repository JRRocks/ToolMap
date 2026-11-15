package fr.toolmap.data.hierarchy

import android.content.Context
import android.os.Build
import android.util.Log
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.Post
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
 * The check of the « Opération » controller on an emulator or a phone (P39.7, ADR-129): one device, no server, no
 * screen. Asked by `adb shell dumpsys activity fr.toolmap/.MainActivity hierarchy-operation` (builds with the testing
 * tools only), twice, with the process killed in between (`tools/emulator/hierarchy-operation.sh`):
 *
 * 1. **create**: an [OperationController] over the real Keystore creates an operation, makes a group, places this
 *    phone in it as its leader, makes this phone the leader of the operation and shares a waypoint; all is stored;
 * 2. **reload**, in a new process: a new controller opens it again from the storage and compares (same operation,
 *    same name, same group and posts, same waypoint, same operations), then dissolves it and checks that nothing is
 *    left: no folder, no key in the Keystore.
 *
 * Lines in logcat, tag [HierarchyKeysCheck.TAG]: `hierarchy-operation …`, one `PASS phase=…` or `FAIL …` per phase,
 * then `done`. Lives in a folder and under key aliases of its own: it never touches a real operation.
 */
object HierarchyOperationCheck {
    const val DUMP_ARGUMENT = "hierarchy-operation"
    private const val FOLDER = "hierarchy/check-operation"
    internal const val ALIAS_PREFIX = "toolmap.hierarchy.checkop."
    private const val EXPECTED = "expected.txt"
    private const val OPERATION = "Contrôle"
    private const val GROUP = "Groupe A"
    private const val ME = "Téléphone"
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
        val phase = if (expected.isFile) "reload" else "create"
        val problems = ArrayList<String>()
        say("hierarchy-operation phase=$phase api=$api")
        val passed =
            catchExpected("hierarchy.operation.check.run", KeyVaultException::class, IOException::class) {
                runBlocking {
                    if (phase ==
                        "create"
                    ) {
                        create(root, vault, say, problems)
                    } else {
                        reload(root, vault, say, problems)
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

    private fun controller(
        root: File,
        vault: KeyVault,
    ) = OperationController(OperationStorage(root, vault, ALIAS_PREFIX), null, { null })

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
        say(
            "hierarchy-operation reloaded in a new process in ${(System.nanoTime() - started) / 1_000_000} ms: same operation",
        )
        c.dissolve()?.let { problems += "not dissolved: $it" }
        for (i in 0 until WAIT_ROUNDS) {
            if (c.view.value.phase == OperationPhase.ENDED && c.ids().isEmpty()) break
            Thread.sleep(WAIT_MS)
        }
        if (c.view.value.ending != Ending.DISSOLVED) problems += "not ended: ${c.view.value.phase}"
        if (c.ids().isNotEmpty() || File(root, id).exists()) problems += "folder left after the dissolution"
        if (vault.aliases(ALIAS_PREFIX).isNotEmpty()) problems += "keys left after the dissolution"
        say("hierarchy-operation dissolved: no key left in the Keystore, no folder left")
        return true
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
