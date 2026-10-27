package fr.toolmap.core.crash

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashReportTest {
    private val device =
        CrashDevice(
            appVersion = "0.1.0 (12)",
            commit = "abc1234",
            device = "samsung SM-A515F",
            androidRelease = "13",
            apiLevel = 33,
            abis = listOf("arm64-v8a", "armeabi-v7a"),
            glEsVersion = "3.2",
            vulkanVersion = "1.1.0",
        )

    @Test
    fun `the report gives the build, the phone and the error`() {
        val report =
            CrashReport(
                kind = CrashKind.JAVA,
                atMillis = 1_790_000_000_000L,
                process = "fr.toolmap",
                thread = "main",
                headline = "java.lang.IllegalStateException: boom",
                details = "java.lang.IllegalStateException: boom\n$FRAME\n",
                device = device,
                log = "10-03 12:00:00.000 I ToolMap: start\n",
            )
        val text = report.toText(ZoneOffset.UTC)
        val lines = text.lines()
        assertEquals("ToolMap 0.1.0 (12) (abc1234)", lines[0])
        assertEquals("when: 2026-09-21 14:13:20 +00:00", lines[1])
        assertTrue("kind: java exception" in lines)
        assertTrue("device: samsung SM-A515F" in lines)
        assertTrue("android: 13 (API 33)" in lines)
        assertTrue("abis: arm64-v8a, armeabi-v7a" in lines)
        assertTrue("opengl es: 3.2" in lines)
        assertTrue("vulkan: 1.1.0" in lines)
        assertTrue("process: fr.toolmap, thread: main" in lines)
        assertTrue(FRAME in lines)
        assertTrue(lines.indexOf("--- log ---") > lines.indexOf(FRAME))
        assertTrue("10-03 12:00:00.000 I ToolMap: start" in lines)
    }

    @Test
    fun `a phone without vulkan and a report without log say so plainly`() {
        val text =
            CrashReport(
                kind = CrashKind.NATIVE,
                atMillis = 0L,
                process = "fr.toolmap",
                thread = null,
                headline = "signal 11 (SIGSEGV)",
                details = "",
                device = device.copy(vulkanVersion = null),
            ).toText(ZoneOffset.UTC)
        assertTrue("vulkan: none" in text.lines())
        assertTrue("kind: native crash" in text.lines())
        assertTrue("process: fr.toolmap" in text.lines())
        assertFalse("--- log ---" in text)
    }

    @Test
    fun `signals are named`() {
        assertEquals("signal 11 (SIGSEGV)", NativeCrash.signal(11))
        assertEquals("signal 6 (SIGABRT)", NativeCrash.signal(6))
        assertEquals("signal 99", NativeCrash.signal(99))
    }

    @Test
    fun `the readable parts of a tombstone are kept one per line`() {
        val bytes =
            byteArrayOf(0x0a, 0x12) + "Abort message: 'vk::createInstance failed'".toByteArray() +
                byteArrayOf(0x1a, 0x03) +
                "abc".toByteArray() +
                byteArrayOf(
                    0x00,
                ) + "/data/app/fr.toolmap/lib/arm64/libmaplibre.so".toByteArray() +
                byteArrayOf(0x12, 0x05) + "/data/app/fr.toolmap/lib/arm64/libmaplibre.so".toByteArray()
        val text = NativeCrash.printableStrings(bytes)
        assertEquals(
            listOf("Abort message: 'vk::createInstance failed'", "/data/app/fr.toolmap/lib/arm64/libmaplibre.so", ""),
            text.split('\n'),
            "short runs are noise, a repeated line is written once",
        )
    }

    @Test
    fun `a huge tombstone is cut`() {
        val bytes = (0 until 1000).joinToString("\u0001") { "frame $it ".padEnd(100, 'x') }.toByteArray()
        assertEquals(500, NativeCrash.printableStrings(bytes, maxChars = 500).length)
    }

    @Test
    fun `packed graphics versions are decoded`() {
        assertEquals("1.1.0", NativeCrash.vulkanVersion((1 shl 22) or (1 shl 12)))
        assertEquals("1.3.215", NativeCrash.vulkanVersion((1 shl 22) or (3 shl 12) or 215))
        assertEquals("3.2", NativeCrash.glEsVersion(0x00030002))
    }

    @Test
    fun `only a new native crash of the main process with this apk is reported`() {
        fun exit(
            at: Long,
            native: Boolean = true,
            process: String = "fr.toolmap",
        ) = ProcessExit(at, native, process, source = "exit@$at")
        val installed = 1_000L
        val exits =
            listOf(
                exit(500), // older APK
                exit(1_500, native = false), // a normal exit, a kill, a java crash (reported by the handler)
                exit(1_600, process = "fr.toolmap:crash"), // the report screen itself
                exit(1_700),
                exit(1_800),
            )
        assertEquals("exit@1800", ExitSelection.nativeCrashToReport(exits, "fr.toolmap", installed, 0L)?.source)
        assertEquals(1_800L, ExitSelection.checkedUpTo(exits, 0L))
        assertNull(ExitSelection.nativeCrashToReport(exits, "fr.toolmap", installed, 1_800L), "already reported")
        assertNull(ExitSelection.nativeCrashToReport(exits, "fr.toolmap", 2_000L, 0L), "all before the install")
        assertNull(ExitSelection.nativeCrashToReport(emptyList<ProcessExit<String>>(), "fr.toolmap", 0L, 0L))
        assertEquals(42L, ExitSelection.checkedUpTo(emptyList(), 42L))
    }

    private companion object {
        const val FRAME = "\tat fr.toolmap.ToolMapApp.onCreate(ToolMapApp.kt:31)"
    }
}
