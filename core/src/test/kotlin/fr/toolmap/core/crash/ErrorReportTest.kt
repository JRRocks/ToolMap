package fr.toolmap.core.crash

import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErrorReportTest {
    private val device =
        CrashDevice(
            appVersion = "0.4.0 (1)",
            commit = "local",
            device = "Google Pixel 9",
            androidRelease = "16",
            apiLevel = 36,
            abis = listOf("arm64-v8a"),
            glEsVersion = "3.2",
            vulkanVersion = null,
        )

    @Test
    fun `the report gives the build and the phone first`() {
        val lines = ErrorReport.text(device, 1_790_000_000_000L, null, null, ZoneOffset.UTC).lines()
        assertEquals("ToolMap 0.4.0 (1) (local)", lines[0])
        assertEquals("when: 2026-09-21 14:13:20 +00:00", lines[1])
        assertTrue("device: Google Pixel 9" in lines)
        assertTrue("vulkan: none" in lines)
    }

    @Test
    fun `the last crash and the log are added only when there are some`() {
        val bare = ErrorReport.text(device, 0L, "  ", "", ZoneOffset.UTC)
        assertFalse("last crash report" in bare)
        assertFalse("--- log ---" in bare)
        val full = ErrorReport.text(device, 0L, "boom\n", "12:00 I ToolMap: start\n", ZoneOffset.UTC)
        assertTrue("--- last crash report ---\nboom\n" in full)
        assertTrue(full.trimEnd().endsWith("--- log ---\n12:00 I ToolMap: start"))
    }
}
