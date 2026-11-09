package fr.toolmap.core.problem

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProblemReportTest {
    private val device = ProblemDevice("0.4.210 (210)", "abc1234", "15", 35, "Google Pixel 7", "arm64-v8a")

    @Test
    fun `nothing private reaches the public repository`() {
        val scrub = ProblemScrubber::message
        assertEquals(
            "open failed: <chemin>: ENOENT",
            scrub("open failed: /storage/emulated/0/Download/Mont Blanc.gpx: ENOENT"),
        )
        assertEquals("GET <url> failed", scrub("GET https://data.geopf.fr/wmts?apikey=ign_scan_ws&x=1 failed"))
        assertEquals("bad point <n>, <n>", scrub("bad point 45.83261, 6.86513"))
        assertEquals("unknown track \"…\"", scrub("unknown track \"Tour du Mont Blanc\""))
        assertEquals("mail <e-mail>", scrub("mail someone@example.com"))
        assertEquals("room <id>", scrub("room 0123456789abcdef0123456789abcdef"))
        assertEquals("token <id>", scrub("token AIzaSyD3x4mpl3K3yW1thD1g1ts"))
        assertEquals("Unexpected end of stream", scrub("Unexpected end of stream"))
        assertEquals(
            "Unexpected JSON token at offset <n>: Expected end of the object JSON input: …",
            scrub(
                "Unexpected JSON token at offset 81234: Expected end of the object\nJSON input: …ur du Mont Blanc\",\"lat\":45.8",
            ),
        )
        assertEquals(
            "lateinit property tileHttpClientProvider has not been initialized",
            scrub("lateinit property tileHttpClientProvider has not been initialized"),
        )
        assertTrue(scrub("x".repeat(1000)).length <= ProblemScrubber.MAX_MESSAGE)
    }

    @Test
    fun `the local logs keep the message but not the secrets libraries quote`() {
        val json = "Unexpected JSON token at offset 3: expected '}'\nJSON input: {\"key\":\"00112233445566778899\"}"
        assertEquals("Unexpected JSON token at offset 3: expected '}'\nJSON input: …", LocalRedaction.message(json))
        assertEquals(
            "Unexpected char 0x0a at 3 in X-Api-Key value: …",
            LocalRedaction.message("Unexpected char 0x0a at 3 in X-Api-Key value: s3cr3t\nkey"),
        )
        assertEquals(
            "GET https://wxs.ign.fr/x/wmts?… failed",
            LocalRedaction.message("GET https://wxs.ign.fr/x/wmts?apikey=k failed"),
        )
        val stack = LocalRedaction.stackText(IllegalStateException("wrap", IllegalArgumentException(json)))
        assertTrue(stack.contains("Caused by: java.lang.IllegalArgumentException: Unexpected JSON token"))
        assertFalse(stack.contains("00112233445566778899"))
    }

    @Test
    fun `the same bug has the same fingerprint, another bug another one`() {
        fun fail(message: String): Throwable = IllegalStateException(message)
        val a = ProblemFingerprint.of(ProblemKind.ERROR, "tiles.decode", fail("tile 1"))
        val b = ProblemFingerprint.of(ProblemKind.ERROR, "tiles.decode", fail("tile 2"))
        val c = ProblemFingerprint.of(ProblemKind.ERROR, "tiles.store", fail("tile 1"))
        val crash = ProblemFingerprint.of(ProblemKind.CRASH, "tiles.decode", fail("tile 3"))
        assertEquals(a, b, "the message does not count")
        assertNotEquals(a, c, "another place")
        assertEquals(a, crash, "caught here, uncaught there: one bug")
        assertTrue(Regex("^[0-9a-f]{16}$").matches(a))
    }

    @Test
    fun `a report is scrubbed, bounded and read back`() {
        val error = RuntimeException("wrapper", IllegalArgumentException("bad file /sdcard/a/b.gpx at 45.1, 6.2"))
        val report =
            ProblemReport.of(
                ProblemKind.ERROR,
                "gpx.import",
                error,
                "main",
                device,
                1_000L,
                listOf("points" to 12, "name" to "Mont Blanc", "ok" to true),
            )
        assertEquals("java.lang.IllegalArgumentException", report.error)
        assertEquals("bad file <chemin>", report.message)
        assertEquals(mapOf("points" to "12", "ok" to "true"), report.context, "strings never leave the phone")
        assertFalse(report.stack.contains("sdcard"))
        assertTrue(report.stack.contains("Caused by: java.lang.IllegalArgumentException"))
        assertTrue(report.stack.length <= ProblemReport.MAX_STACK_CHARS)
        assertEquals(report, ProblemReport.fromJson(report.toJson()))
    }

    @Test
    fun `a native crash keeps only libraries and functions`() {
        val tombstone =
            """
            Abort message: 'secret text of the user'
            /data/app/~~Xy12==/fr.toolmap-AbC==/lib/arm64/libmaplibre.so
            _ZN4mbgl8Renderer6renderEv
            some user string in memory
            /system/lib64/libc.so
            """.trimIndent()
        val report = ProblemReport.nativeCrash("signal 11 (SIGSEGV) - crash", tombstone, "fr.toolmap", device, 5L)
        assertEquals(
            "/data/app/…/lib/arm64/libmaplibre.so\n_ZN4mbgl8Renderer6renderEv\n/system/lib64/libc.so",
            report.stack,
        )
        assertEquals("signal 11 (SIGSEGV)", report.error)
        assertFalse(report.toJson().contains("secret"))
    }

    @Test
    fun `an ANR keeps the frames of the main thread`() {
        val trace =
            """
            "Signal Catcher" daemon prio=10 tid=6 Runnable
              at a.B.c(B.java:1)

            "main" prio=5 tid=1 Blocked
              at fr.toolmap.map.MapController.update(MapController.kt:120)
              - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 12
              at android.os.Handler.dispatchMessage(Handler.java:99)

            "other" prio=5 tid=2 Native
              at x.Y.z(Y.java:2)
            """.trimIndent()
        val report = ProblemReport.anr(trace, "Input dispatching timed out", device, 9L)
        assertEquals(3, report.stack.lines().size)
        assertTrue(report.stack.startsWith("at fr.toolmap.map.MapController.update"))
        val again = ProblemReport.anr(trace.replace("120", "121"), null, device, 10L)
        assertEquals(report.fp, again.fp, "a line number does not change the fingerprint")
    }
}
