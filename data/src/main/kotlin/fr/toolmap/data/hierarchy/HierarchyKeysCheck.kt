package fr.toolmap.data.hierarchy

import android.content.Context
import android.os.Build
import android.util.Log
import fr.toolmap.core.hierarchy.Change
import fr.toolmap.core.hierarchy.Channel
import fr.toolmap.core.hierarchy.HierarchyReplica
import fr.toolmap.core.hierarchy.HierarchyWire
import fr.toolmap.core.hierarchy.IssueResult
import fr.toolmap.core.hierarchy.Names
import fr.toolmap.core.hierarchy.ObjectVerdict
import fr.toolmap.core.hierarchy.OpAuth
import fr.toolmap.core.hierarchy.P256
import fr.toolmap.core.hierarchy.ShareKind
import fr.toolmap.core.hierarchy.SharedRecords
import fr.toolmap.core.hierarchy.memberId
import fr.toolmap.core.problem.catchExpected
import fr.toolmap.core.problem.catchReported
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The check of the real key store on an emulator or a phone (P39.6, ADR-128): what the JVM tests cannot run.
 * Asked by `adb shell dumpsys activity fr.toolmap/.MainActivity hierarchy-keys` (debug builds only), twice, with the
 * process killed in between (`tools/emulator/hierarchy-keys.sh`):
 *
 * 1. **create**: makes one identity per way of keeping the agreement key (in the Keystore where it can, from API 31;
 *    in a file sealed by the Keystore, the way of API 26 to 30, everywhere), times a signature, a verification and
 *    an agreement, builds a small operation with the engine (two members, a group, two content keys, names, one
 *    waypoint) and stores it;
 * 2. **reload**, in a new process: opens each identity again, loads the operation, compares it with what was stored
 *    (same member, same state, same keys, same objects), signs and agrees again, then wipes it and checks that no key
 *    is left in the Keystore and no folder on the disk.
 *
 * Lines in logcat, tag [TAG]: `hierarchy-keys …`, one `PASS phase=…` or `FAIL …` per phase, then `done`. Lives in a
 * folder of its own (`hierarchy/check`): it never touches a real operation. Nothing of it runs unless asked.
 */
object HierarchyKeysCheck {
    const val TAG = "ToolMapHierarchy"

    /** The word after the activity's name in the `dumpsys` command. */
    const val DUMP_ARGUMENT = "hierarchy-keys"
    private const val FOLDER = "hierarchy/check"

    /** Apart from the aliases of real operations ([OperationStorage.ALIAS_PREFIX]): a wipe here never reaches them. */
    internal const val ALIAS_PREFIX = "toolmap.hierarchy.check."
    private const val EXPECTED = "expected.txt"
    private const val WARMUP = 5
    private const val ROUNDS = 30
    private const val AGREE_ROUNDS = 10
    private const val GROUP = 0x51L
    private const val OBJECT = 0x77L

    private val running = AtomicBoolean(false)

    /** Starts the check on a thread of its own; false when one is already running. */
    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val files = context.applicationContext.filesDir
        Thread({
            try {
                // A failure of the check is a bug to report, never a crash of the app it checks.
                catchReported("hierarchy.check") {
                    run(File(files, FOLDER), AndroidKeyVault(), Build.VERSION.SDK_INT) { Log.i(TAG, it) }
                }
            } finally {
                running.set(false)
            }
        }, "hierarchy-keys").start()
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
        say("hierarchy-keys phase=$phase api=$api agreement-in-keystore=${vault.agreesInHardware}")
        val passed =
            catchExpected("hierarchy.check.run", KeyVaultException::class, IOException::class) {
                if (phase == "create") create(root, vault, say, problems) else reload(root, vault, say, problems)
            }.getOrElse {
                problems += "${it.javaClass.simpleName}: ${it.message}"
                false
            } && problems.isEmpty()
        say(
            if (passed) {
                "hierarchy-keys PASS phase=$phase"
            } else {
                "hierarchy-keys FAIL phase=$phase ${problems.joinToString("; ")}"
            },
        )
        say("hierarchy-keys done")
        return passed
    }

    private fun create(
        root: File,
        vault: KeyVault,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ): Boolean {
        val storage = OperationStorage(root, vault, ALIAS_PREFIX)
        storage.wipeAll()
        root.mkdirs()
        File(root, EXPECTED).delete()
        val lines = ArrayList<String>()
        val modes = if (vault.agreesInHardware) listOf(false, true) else listOf(true)
        for (fileAgreement in modes) {
            val started = System.nanoTime()
            val stored = storage.create(fileAgreement) ?: return false.also { problems += "identity not made" }
            say("hierarchy-keys ${label(stored)} identity made in ${(System.nanoTime() - started) / 1_000_000} ms")
            if (stored.agreesInVault == fileAgreement) problems += "agreement kept in the wrong place"
            time(stored, say, problems)
            val contents = build(stored, problems) ?: return false
            val result = stored.save(contents)
            if (result != SaveResult.Saved) problems += "not saved: $result"
            val state = contents.replica.state
            val sealing = contents.ring.sealingKey(state, Channel.unit(GROUP))?.id
            lines +=
                listOf(
                    stored.id,
                    stored.self.toString(),
                    fingerprint(HierarchyWire.encodeState(state)),
                    sealing.toString(),
                    contents.objects.shown(state, stored.self).size.toString(),
                ).joinToString(" ")
        }
        SealedFile.writeAtomically(File(root, EXPECTED), lines.joinToString("\n").toByteArray(Charsets.US_ASCII))
        return true
    }

    private fun reload(
        root: File,
        vault: KeyVault,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ): Boolean {
        val storage = OperationStorage(root, vault, ALIAS_PREFIX)
        val lines = File(root, EXPECTED).readText(Charsets.US_ASCII).lines().filter { it.isNotBlank() }
        File(root, EXPECTED).delete()
        for (line in lines) {
            val (id, self, state, sealing, shown) = line.split(" ")
            val stored = storage.open(id) ?: return false.also { problems += "identity not opened again" }
            if (stored.self.toString() != self) problems += "another member after the restart"
            when (val loaded = stored.load()) {
                is LoadResult.Loaded -> {
                    val contents = loaded.contents
                    val now = contents.replica.state
                    if (loaded.refused.isNotEmpty()) problems += "parts refused: ${loaded.refused}"
                    if (fingerprint(HierarchyWire.encodeState(now)) != state) problems += "another state"
                    if (contents.ring.sealingKey(now, Channel.unit(GROUP))?.id.toString() != sealing) {
                        problems += "another content key"
                    }
                    if (contents.objects.shown(now, stored.self).size.toString() != shown) problems += "other objects"
                    say("hierarchy-keys ${label(stored)} reloaded in a new process: same member, state, keys, objects")
                }

                else -> {
                    problems += "not loaded: $loaded"
                }
            }
            time(stored, say, problems)
            stored.wipe()
            if (vault.aliases(ALIAS_PREFIX + id).isNotEmpty()) problems += "keys left after a wipe"
            if (File(root, id).exists()) problems += "folder left after a wipe"
            if (storage.open(id) != null) problems += "opened after a wipe"
        }
        say("hierarchy-keys wiped: no key left in the Keystore, no folder left")
        return true
    }

    /** A signature by the Keystore, its verification, and an agreement, timed: medians after a warm-up. */
    private fun time(
        stored: StoredOperation,
        say: (String) -> Unit,
        problems: MutableList<String>,
    ) {
        val keys = stored.keys
        val message = ByteArray(96).also(SecureRandom()::nextBytes)
        var signature = ByteArray(0)
        val sign = median(ROUNDS) { signature = keys.sign(message) }
        var verified = true
        val verify = median(ROUNDS) { verified = verified && P256.verify(keys.public.signing, message, signature) }
        if (!verified) problems += "signature of the Keystore not verified"
        if (OpAuth.memberId(keys.public.signing) != keys.memberId) problems += "member is not its key"
        val peer = P256.generate()
        val peerPublic = P256.encode(peer.public)
        var shared: ByteArray? = null
        val agree = median(AGREE_ROUNDS) { shared = keys.agree(peerPublic) }
        if (shared == null || !shared.contentEquals(P256.agree(peer.private, keys.public.agreement))) {
            problems += "agreement differs from the other side"
        }
        say(
            "hierarchy-keys ${label(stored)} sign median=${sign}us verify median=${verify}us agree median=${agree}us " +
                "($ROUNDS signatures, $AGREE_ROUNDS agreements)",
        )
    }

    /** A small operation, written by the engine: this phone creates it, lets a second member in, both in one group. */
    private fun build(
        stored: StoredOperation,
        problems: MutableList<String>,
    ): OperationContents? {
        val me = Names.hide("Check")
        val title = Names.hide("Check operation")
        val replica = HierarchyReplica.create(stored.keys, me.hidden, title.hidden, SecureRandom().nextLong())
        val other = OpAuth.memoryKeys()
        val otherName = Names.hide("Other")
        val group = Names.hide("Group")
        val changes =
            listOf(
                Change.AdmitMember(other.memberId, otherName.hidden, other.public),
                Change.CreateGroup(GROUP, group.hidden),
                Change.AssignMember(stored.self, GROUP),
                Change.AssignMember(other.memberId, GROUP),
            )
        for (change in changes) {
            if (replica.issue(change) !is IssueResult.Issued) return null.also { problems += "engine refused $change" }
        }
        val contents = OperationContents.around(replica, stored.keys)
        val state = replica.state
        contents.ring.issue(state, Channel.ENSEMBLE) ?: return null.also { problems += "no key for the ensemble" }
        contents.ring.issue(state, Channel.unit(GROUP)) ?: return null.also { problems += "no key for the group" }
        listOf(me, title, otherName, group).forEach(contents.names::learn)
        val waypoint =
            SharedRecords.write(
                stored.keys,
                replica.ensembleId,
                ShareKind.WAYPOINT,
                Channel.unit(GROUP),
                OBJECT,
                "check".toByteArray(),
            )
        if (contents.objects.accept(waypoint, replica::signingKeyOf) != ObjectVerdict.STORED) {
            problems += "waypoint not kept"
        }
        return contents
    }

    private fun label(stored: StoredOperation) = if (stored.agreesInVault) "agreement=keystore" else "agreement=file"

    private inline fun median(
        rounds: Int,
        work: () -> Unit,
    ): Long {
        repeat(WARMUP) { work() }
        val times =
            LongArray(rounds) {
                val start = System.nanoTime()
                work()
                (System.nanoTime() - start) / 1_000
            }
        times.sort()
        return times[rounds / 2]
    }

    private fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
