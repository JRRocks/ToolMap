package fr.toolmap.service

import android.app.Application
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ServiceWakeLockTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `holding twice takes the lock once and releasing lets it go`() {
        val lock = ServiceWakeLock(context, "ToolMap:test")
        assertFalse(lock.held)
        lock.hold()
        lock.hold()
        assertTrue(lock.held)
        assertNotNull(ShadowPowerManager.getLatestWakeLock())
        lock.release()
        assertFalse(lock.held)
        assertFalse(assertNotNull(ShadowPowerManager.getLatestWakeLock()).isHeld, "one release is enough")
        lock.release()
    }

    @Test
    fun `a lock that timed out is taken again at the next call`() {
        val lock = ServiceWakeLock(context, "ToolMap:test")
        lock.hold()
        // What Android does when the timeout elapses: the lock is let go without the service asking.
        assertNotNull(ShadowPowerManager.getLatestWakeLock()).release()
        assertFalse(lock.held)
        lock.hold()
        assertTrue(lock.held)
    }
}
