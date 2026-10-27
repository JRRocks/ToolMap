package fr.toolmap

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import fr.toolmap.crash.CrashActivity
import fr.toolmap.crash.CrashReporter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Starts the real application (Hilt graph, MapLibre set-up, restore hook) and the main activity up to the map screen.
 * MapLibre's package is instrumented so that its native methods are no-ops: the JVM cannot load `libmaplibre.so`,
 * so nothing drawn by the native side is checked here, only everything the Java and Kotlin code does at launch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 35], application = ToolMapApp::class, instrumentedPackages = ["org.maplibre.android"])
class AppStartupTest {
    private val app: ToolMapApp get() = RuntimeEnvironment.getApplication() as ToolMapApp

    @After
    fun tearDown() {
        // The application installed its crash handler for the whole JVM: put back the test runner's one.
        (Thread.getDefaultUncaughtExceptionHandler() as? CrashReporter.Handler)?.let {
            Thread.setDefaultUncaughtExceptionHandler(it.previous)
        }
        File(app.filesDir, "crash").deleteRecursively()
    }

    @Test
    fun `the application starts and shows the map`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertNotNull(activity.window.decorView.findMapView(), "the map screen was not composed")
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `a crash not yet seen is shown before the map`() {
        CrashReporter.recordJavaCrash(app, Thread("main"), IllegalStateException("boom"))
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        assertTrue(activity.isFinishing)
        val next = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(CrashActivity::class.java.name, next.component?.className)
        assertNull(activity.window.decorView.findMapView(), "the map, which may be what crashed, is not built")
        controller.pause().stop().destroy()
    }

    private fun View.findMapView(): MapView? {
        if (this is MapView) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).findMapView()?.let { return it }
        }
        return null
    }
}
