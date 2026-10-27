package fr.toolmap.crash

import android.app.Application
import android.content.ClipboardManager
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import fr.toolmap.MainActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CrashActivityTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        File(app.filesDir, "crash").deleteRecursively()
    }

    private fun start(): CrashActivity {
        CrashReporter.recordJavaCrash(app, Thread("main"), IllegalStateException("boom"))
        return Robolectric.buildActivity(CrashActivity::class.java).setup().get()
    }

    private fun CrashActivity.report(): String =
        (window.decorView.findViewWithTag<TextView>(CrashActivity.REPORT_TAG)).text.toString()

    private fun CrashActivity.button(label: String): Button =
        assertNotNull(
            window.decorView.find {
                it is Button &&
                    it.text == label
            } as? Button,
            label,
        )

    private fun View.find(match: (View) -> Boolean): View? {
        if (match(this)) return this
        if (this is ViewGroup) {
            for (i in 0 until childCount) getChildAt(i).find(match)?.let { return it }
        }
        return null
    }

    @Test
    fun `the report is shown and the next start is a normal one`() {
        val activity = start()
        assertTrue("java.lang.IllegalStateException: boom" in activity.report().lines())
        assertFalse(CrashReporter.hasPending(app))
    }

    @Test
    fun `the report can be copied`() {
        val activity = start()
        activity.button("Copier").performClick()
        val clip = assertNotNull(app.getSystemService(ClipboardManager::class.java).primaryClip)
        assertEquals(activity.report(), clip.getItemAt(0).text.toString())
    }

    @Test
    fun `the report can be shared as text`() {
        val activity = start()
        activity.button("Partager").performClick()
        val chooser = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = assertNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
        assertEquals("text/plain", send.type)
        assertEquals(activity.report(), send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `restarting opens the map again`() {
        val activity = start()
        activity.button("Relancer").performClick()
        val next = assertNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(MainActivity::class.java.name, next.component?.className)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `without a report the screen says so`() {
        val activity = Robolectric.buildActivity(CrashActivity::class.java).setup().get()
        assertEquals("Aucun rapport de plantage.", activity.report())
    }
}
