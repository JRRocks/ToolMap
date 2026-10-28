package fr.toolmap.share

import android.app.Application
import android.content.pm.ServiceInfo
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The foreground types of the sharing service (P22.3, ADR-090): `specialUse` to listen, `location` to share. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ShareServiceTypesTest {
    private val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
    private val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

    @Test
    fun `android 14 and later listen as specialUse and share as specialUse and location`() {
        assertEquals(special, ShareService.foregroundTypes(share = false, sdk = 34))
        assertEquals(special or location, ShareService.foregroundTypes(share = true, sdk = 37))
    }

    @Test
    fun `before android 14 the listening needs no type, and before android 10 nothing has one`() {
        assertEquals(0, ShareService.foregroundTypes(share = false, sdk = 33))
        assertEquals(location, ShareService.foregroundTypes(share = true, sdk = 29))
        assertEquals(0, ShareService.foregroundTypes(share = true, sdk = 28))
    }
}
