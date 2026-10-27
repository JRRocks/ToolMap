package fr.toolmap.debugkit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The commands of the live-test scripts (`tools/livetest`), sent from the PC with
 * `adb shell am broadcast -n fr.toolmap/fr.toolmap.debugkit.DebugCommands -a <action> [extras]`; the answer
 * (`data=` in the output of `am`) is the status line of [DebugKit]. Only the shell may send them (DUMP permission).
 *
 * - [COLLECT] `--ez on true|false`: this device is the collector (the emulator).
 * - [TEST] `--ei minutes 30` (0: off): test mode without a collector.
 * - [AWAKE] `--ei minutes 30` (0: off), on the collector: the phones of the group keep their screen on (P21.6);
 *   `--ez here true`, or on another device: its own screen.
 * - [MARK] `--es text "step 3: GPS off"`: a `test.mark` line in the log.
 * - [SNAPSHOT]: a snapshot now. [STATUS]: the status only.
 */
class DebugCommands : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val kit = DebugKit.instance
        if (kit == null) {
            resultData = "debugkit not started"
            return
        }
        when (intent.action) {
            COLLECT -> {
                kit.setCollector(intent.getBooleanExtra("on", true))
            }

            TEST -> {
                kit.setTest(intent.getIntExtra("minutes", DEFAULT_TEST_MINUTES))
            }

            AWAKE -> {
                kit.setAwake(
                    intent.getIntExtra("minutes", DEFAULT_TEST_MINUTES),
                    intent.getBooleanExtra("here", false),
                )
            }

            MARK -> {
                kit.mark(intent.getStringExtra("text").orEmpty())
            }

            SNAPSHOT -> {
                kit.snapshotNow()
            }
        }
        resultData = kit.status()
    }

    companion object {
        const val COLLECT = "fr.toolmap.debug.COLLECT"
        const val TEST = "fr.toolmap.debug.TEST"
        const val AWAKE = "fr.toolmap.debug.AWAKE"
        const val MARK = "fr.toolmap.debug.MARK"
        const val SNAPSHOT = "fr.toolmap.debug.SNAPSHOT"
        const val STATUS = "fr.toolmap.debug.STATUS"
        private const val DEFAULT_TEST_MINUTES = 30
    }
}
