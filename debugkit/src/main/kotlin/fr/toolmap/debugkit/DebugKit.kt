package fr.toolmap.debugkit

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.toolmap.core.eventlog.AppExtension
import fr.toolmap.core.eventlog.EventLog
import fr.toolmap.core.eventlog.EventStore
import fr.toolmap.core.p2p.DiagChannel
import fr.toolmap.core.p2p.MeshNode
import fr.toolmap.core.p2p.Msg
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The live-test tools of the position sharing (ADR-070, P21, `docs/specs/event-log.md`), in the debug APK only.
 *
 * One device is the **collector** (the emulator on the PC, set with `tools/livetest`): it tells the group it is there
 * and writes what the others send in `files/eventlog/remote.log` and in logcat (tag [REMOTE_TAG]). A phone that hears
 * a collector goes into **test mode** for [TEST_MODE_MS] (kept across restarts): every event is logged (the high-rate
 * ones too), a snapshot of the phone and of the app every [SNAPSHOT_EVERY_MS], the GNSS chip watched, and its log sent
 * to the collector through the encrypted group, one payload at most every second and never while a position waits.
 */
@Singleton
class DebugKit
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val store: EventStore,
        private val channel: DiagChannel,
    ) : AppExtension {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val executor: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { Thread(it, "toolmap-debugkit").apply { isDaemon = true } }
        private val clock: () -> Long = { EventLog.clock() }
        private val sender = DiagSender(store.epoch, { store.lastSeq }, store::linesAfter, clock)
        private val collector = DiagCollector(clock).apply { restore(prefs.getString(KEY_MEMBERS, null).orEmpty()) }
        private val snapshots = Snapshots(context)
        private val gnss = GnssWatch(context)
        private val screen = ScreenAwake(PowerScreenLock(context))

        @Volatile
        private var isCollector = prefs.getBoolean(KEY_COLLECTOR, false)

        @Volatile
        private var testUntil = prefs.getLong(KEY_TEST_UNTIL, 0L)

        /** Collector: until when the phones keep their screen on (P21.6), kept if the collector restarts. */
        @Volatile
        private var awakeUntil = prefs.getLong(KEY_AWAKE_UNTIL, 0L)

        @Volatile
        private var testOn = false
        private var lastSnapshotAt = 0L
        private var lastAcksAt = 0L
        private var lastStatusAt = 0L
        private var lastSavedTestUntil = testUntil
        private var remote: BufferedWriter? = null
        private var remoteBytes = 0L
        private var payloadsIn = 0L
        private var badPayloads = 0L

        override fun start() {
            instance = this
            channel.diagReceiver = { member, body -> executor.execute { onPayload(member, body) } }
            EventLog.event(
                "debug.start",
                "collector" to isCollector,
                "testLeftS" to ((testUntil - clock()) / MS_PER_S).coerceAtLeast(0),
                "epoch" to store.epoch,
                "seq" to store.lastSeq,
            )
            executor.scheduleWithFixedDelay(::safeTick, 0, TICK_MS, TimeUnit.MILLISECONDS)
        }

        /** Makes this device the collector of the group (the emulator), or not. */
        fun setCollector(on: Boolean) {
            isCollector = on
            prefs.edit {
                putBoolean(KEY_COLLECTOR, on)
                if (!on) remove(KEY_MEMBERS)
            }
            EventLog.event("debug.collector", "on" to on)
            executor.execute(::safeTick)
        }

        /** Turns the test mode on for [minutes] (0: off), without waiting for a collector. */
        fun setTest(minutes: Int) {
            executor.execute {
                testUntil = if (minutes > 0) clock() + minutes * MS_PER_MIN else 0L
                saveTestUntil()
                EventLog.event("debug.test.set", "minutes" to minutes)
                safeTick()
            }
        }

        /**
         * The phones of the group keep their screen on for [minutes] (0: no longer), the app in the background too
         * (P21.6): sent by the collector with its announcements, every 5 s; a phone that stops hearing them lets its
         * screen go off at the time it was given. [here], or on a device that is not the collector: this device's
         * own screen.
         */
        fun setAwake(
            minutes: Int,
            here: Boolean,
        ) {
            executor.execute {
                val m = minutes.coerceIn(0, MAX_AWAKE_MIN)
                EventLog.event("debug.awake.set", "minutes" to m, "collector" to isCollector, "here" to here)
                if (here || !isCollector) {
                    screen.request(m * S_PER_MIN, here = true)
                    return@execute
                }
                awakeUntil = if (m > 0) clock() + m * MS_PER_MIN else 0L
                prefs.edit { putLong(KEY_AWAKE_UNTIL, awakeUntil) }
                // Told at once, not at the next announcement.
                lastAcksAt = 0L
                safeTick()
            }
        }

        /** Seconds the phones still keep their screen on (collector), rounded up. */
        private fun awakeLeftS(now: Long): Long = ((awakeUntil - now + MS_PER_S - 1) / MS_PER_S).coerceAtLeast(0)

        /** A line of the test protocol in the log ("step 3: GPS off"), to find it again in the analysis. */
        fun mark(text: String) = EventLog.event("test.mark", "text" to text)

        fun snapshotNow() = executor.execute { snapshots.write() }

        /** One line on what the tools are doing; also logged. */
        fun status(): String {
            val fields =
                listOf(
                    "collector" to isCollector,
                    "test" to testOn,
                    "testLeftS" to ((testUntil - clock()) / MS_PER_S).coerceAtLeast(0),
                    "self" to MeshNode.hex(channel.selfId),
                    "canSend" to channel.canSend,
                    "busy" to channel.busy,
                    "seq" to store.lastSeq,
                    "in" to payloadsIn,
                    "bad" to badPayloads,
                    "awakeAskS" to if (isCollector) awakeLeftS(clock()) else null,
                    "awake" to screen.on,
                ) + sender.state()
            EventLog.event("debug.status", *fields.toTypedArray())
            val members = if (isCollector) collector.state() else emptyList()
            return (listOf(fields.joinToString(" ") { (k, v) -> "$k=$v" }) + members).joinToString(" | ")
        }

        private fun safeTick() {
            try {
                tick()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Tick failed", e)
                EventLog.event("debug.error", "where" to "tick", "error" to e.toString())
            }
        }

        private fun tick() {
            val now = clock()
            screen.tick()
            val on = isCollector || now < testUntil
            if (on != testOn) {
                testOn = on
                EventLog.verbose = on
                if (on) gnss.start() else gnss.stop()
                EventLog.event("debug.test", "on" to on, "collector" to isCollector)
                if (on) lastSnapshotAt = 0L
            }
            if (testOn && now - lastSnapshotAt >= SNAPSHOT_EVERY_MS) {
                lastSnapshotAt = now
                snapshots.write()
            }
            if (testOn && now - lastStatusAt >= STATUS_EVERY_MS) {
                lastStatusAt = now
                status()
            }
            if (!channel.canSend || channel.busy) return
            if (isCollector) {
                if (now - lastAcksAt >= ACKS_EVERY_MS) {
                    lastAcksAt = now
                    channel.sendDiag(DiagCodec.encode(collector.acks(awakeLeftS(now))))
                }
            } else if (testOn) {
                val body = sender.next(Msg.Diag.MAX_BYTES) ?: return
                if (!channel.sendDiag(body)) EventLog.event("debug.send.fail", "bytes" to body.size)
            }
        }

        private fun onPayload(
            member: Long,
            body: ByteArray,
        ) {
            payloadsIn++
            val payload = DiagCodec.decode(body)
            if (payload == null) {
                badPayloads++
                EventLog.event("debug.payload.bad", "member" to MeshNode.hex(member), "bytes" to body.size)
                return
            }
            when (payload) {
                is DiagPayload.Lines -> {
                    if (!isCollector) return
                    val hex = MeshNode.hex(member)
                    val lines = collector.accept(hex, payload)
                    if (lines.isEmpty()) {
                        EventLog.detail("debug.payload.refused", "member" to hex, "after" to payload.after)
                    }
                    for (line in lines) writeRemote("$hex $line")
                    remote?.flush()
                    // Where each member stands, kept with what is written: a restarted collector asks for no line
                    // twice.
                    if (lines.isNotEmpty()) prefs.edit { putString(KEY_MEMBERS, collector.saved()) }
                }

                is DiagPayload.Acks -> {
                    if (isCollector) return
                    // A collector is there: test mode for the next half hour, kept if the app restarts.
                    testUntil = clock() + TEST_MODE_MS
                    if (testUntil - lastSavedTestUntil >= SAVE_TEST_EVERY_MS) saveTestUntil()
                    sender.onAck(payload.acks[MeshNode.hex(channel.selfId)])
                    screen.request(payload.awakeS)
                    if (!testOn) executor.execute(::safeTick)
                }
            }
        }

        private fun writeRemote(line: String) {
            Log.i(REMOTE_TAG, line)
            try {
                val out = remote ?: openRemote()
                out.write(line)
                out.newLine()
                remoteBytes += line.length + 1
                if (remoteBytes > REMOTE_MAX_BYTES) {
                    out.close()
                    remote = null
                    val dir = File(context.filesDir, LOG_DIR)
                    File(dir, REMOTE_FILE).renameTo(File(dir, REMOTE_PREVIOUS))
                }
            } catch (e: IOException) {
                remote = null
                Log.w(TAG, "remote.log not written", e)
            }
        }

        private fun openRemote(): BufferedWriter {
            val file = File(File(context.filesDir, LOG_DIR).apply { mkdirs() }, REMOTE_FILE)
            remoteBytes = file.length()
            // Appended to: the collector restarts during a test too.
            return FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).also { remote = it }
        }

        private fun saveTestUntil() {
            lastSavedTestUntil = testUntil
            prefs.edit { putLong(KEY_TEST_UNTIL, testUntil) }
        }

        companion object {
            /** Set by [start]: what [DebugCommands] talks to. */
            @Volatile
            var instance: DebugKit? = null
                private set

            const val TAG = "TMDebug"
            const val REMOTE_TAG = "TMRemote"
            const val LOG_DIR = "eventlog"
            const val REMOTE_FILE = "remote.log"
            const val REMOTE_PREVIOUS = "remote.previous.log"
            const val TEST_MODE_MS = 30 * 60_000L
            const val SNAPSHOT_EVERY_MS = 5_000L
            const val ACKS_EVERY_MS = 5_000L
            const val STATUS_EVERY_MS = 60_000L
            private const val TICK_MS = 1_000L
            private const val SAVE_TEST_EVERY_MS = 60_000L
            private const val REMOTE_MAX_BYTES = 32L shl 20
            private const val PREFS = "debugkit"
            private const val KEY_COLLECTOR = "collector"
            private const val KEY_TEST_UNTIL = "test_until"
            private const val KEY_MEMBERS = "members"
            private const val KEY_AWAKE_UNTIL = "awake_until"

            /** Screen kept on for at most this long by one command. */
            private const val MAX_AWAKE_MIN = 240
            private const val MS_PER_S = 1000L
            private const val MS_PER_MIN = 60_000L
            private const val S_PER_MIN = 60L
        }
    }
