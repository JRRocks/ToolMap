package fr.toolmap.share

import android.Manifest
import android.app.Application
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareServiceResumeTest {
    private val app: Application = RuntimeEnvironment.getApplication()

    /** What the service leaves behind: "active" until the sharing is stopped for good. */
    private fun leftActive(active: Boolean) {
        app
            .getSharedPreferences(ShareService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ShareService.KEY_ACTIVE, active)
            .commit()
    }

    private fun resume(
        inGroup: Boolean = true,
        sharing: Boolean = false,
    ) = ShareService.resumeIfInterrupted(app, inGroup, sharing)

    @Test
    fun `a sharing cut without being stopped starts again when the app comes on screen`() {
        leftActive(true)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(resume())
        assertEquals(ShareService::class.java.name, shadowOf(app).nextStartedService?.component?.className)
    }

    @Test
    fun `listening to the group starts the service without the location permission`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(ShareService.listen(app))
        val started = shadowOf(app).nextStartedService
        assertEquals(ShareService::class.java.name, started?.component?.className)
        assertEquals("fr.toolmap.share.LISTEN", started?.action)
    }

    @Test
    fun `nothing starts once stopped, while sharing, out of a group or without the location permission`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        leftActive(false)
        assertFalse(resume())
        leftActive(true)
        assertFalse(resume(sharing = true))
        assertFalse(resume(inGroup = false))
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertFalse(resume())
        assertNull(shadowOf(app).nextStartedService)
    }
}
