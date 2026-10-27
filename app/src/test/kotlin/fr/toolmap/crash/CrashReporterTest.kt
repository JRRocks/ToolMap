package fr.toolmap.crash

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Intent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashReporterTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        shadowOf(app.packageManager).getInternalMutablePackageInfo(app.packageName).lastUpdateTime = INSTALLED
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        File(app.filesDir, "crash").deleteRecursively()
    }

    @Test
    fun `an uncaught exception is written down and its report opened before the process ends`() {
        var exited = false
        CrashReporter.install(app) { exited = true }
        val error = ExceptionInInitializerError(IllegalStateException("MapLibre is not initialized"))
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread("main"), error)

        assertTrue(exited)
        assertTrue(CrashReporter.hasPending(app))
        val opened = assertNotNull(shadowOf(app).nextStartedActivity)
        assertEquals(CrashActivity::class.java.name, opened.component?.className)
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0, "started from outside an activity")

        val text = assertNotNull(CrashReporter.takeForDisplay(app))
        val lines = text.lines()
        assertTrue("kind: java exception" in lines)
        assertTrue(
            "java.lang.IllegalStateException: MapLibre is not initialized" in lines,
            "the root cause is the headline",
        )
        assertTrue(
            lines.any {
                it.startsWith("java.lang.ExceptionInInitializerError")
            },
            "the whole stack trace follows",
        )
        assertTrue(lines.any { it.startsWith("process: ") && it.endsWith(", thread: main") })
        assertTrue(lines.any { it.startsWith("android: ") && it.endsWith("(API 35)") })
    }

    @Test
    fun `a report is shown once then kept as the last one`() {
        CrashReporter.recordJavaCrash(app, Thread("worker"), IllegalArgumentException("bad"))
        assertTrue(CrashReporter.hasPending(app))
        val first = assertNotNull(CrashReporter.takeForDisplay(app))
        assertFalse(CrashReporter.hasPending(app), "the next start is a normal one")
        assertEquals(first, CrashReporter.takeForDisplay(app), "still there to be read again")
    }

    @Test
    fun `nothing to show when nothing crashed`() {
        assertFalse(CrashReporter.hasPending(app))
        assertNull(CrashReporter.takeForDisplay(app))
        CrashReporter.collectNativeCrash(app)
        assertFalse(CrashReporter.hasPending(app))
    }

    @Test
    fun `a native crash of the previous run is reported once at the next start with its tombstone`() {
        val tombstone =
            byteArrayOf(
                0x0a,
                0x10,
            ) + "Abort message: 'vkCreateInstance failed'".toByteArray() + byteArrayOf(0x12, 0x02) +
                "/data/app/fr.toolmap/lib/arm64/libmaplibre.so".toByteArray()
        exit(INSTALLED - 1_000, ApplicationExitInfo.REASON_CRASH_NATIVE) // an older APK
        exit(INSTALLED + 1_000, ApplicationExitInfo.REASON_CRASH_NATIVE, trace = tombstone)
        exit(INSTALLED + 2_000, ApplicationExitInfo.REASON_USER_REQUESTED)

        CrashReporter.collectNativeCrash(app)

        assertTrue(CrashReporter.hasPending(app))
        val lines = assertNotNull(CrashReporter.takeForDisplay(app)).lines()
        assertTrue("kind: native crash" in lines)
        assertTrue(lines.any { it.startsWith("signal 11 (SIGSEGV)") })
        assertTrue("Abort message: 'vkCreateInstance failed'" in lines)
        assertTrue("/data/app/fr.toolmap/lib/arm64/libmaplibre.so" in lines)

        CrashReporter.collectNativeCrash(app)
        assertFalse(CrashReporter.hasPending(app), "already reported")
    }

    private fun exit(
        at: Long,
        reason: Int,
        trace: ByteArray? = null,
    ) {
        val builder =
            ApplicationExitInfoBuilder
                .newBuilder()
                .setProcessName(app.packageName)
                .setTimestamp(at)
                .setReason(reason)
                .setStatus(11)
        trace?.let { builder.setTraceInputStream(ByteArrayInputStream(it)) }
        shadowOf(app.getSystemService(ActivityManager::class.java)).addApplicationExitInfo(builder.build())
    }

    private companion object {
        const val INSTALLED = 1_700_000_000_000L
    }
}
