package fr.toolmap.record

import android.app.Application
import android.location.Location
import android.location.LocationManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationToFixTest {
    private fun location() =
        Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 45.0
            longitude = 6.0
            time = 1234L
            accuracy = 8f
        }

    @Test
    fun `an altitude above sea level is taken as such with its accuracy`() {
        val fix =
            location()
                .apply {
                    altitude = 1280.0
                    mslAltitudeMeters = 1233.5
                    mslAltitudeAccuracyMeters = 4f
                    verticalAccuracyMeters = 9f
                }.toFix()
        assertEquals(1233.5, fix.ele)
        assertTrue(fix.eleIsMsl)
        assertEquals(4.0, fix.verticalAccuracyMeters)
        assertEquals(8.0, fix.accuracyMeters)
    }

    @Test
    fun `a plain altitude is kept but not known to be above sea level`() {
        val fix = location().apply { altitude = 1280.0 }.toFix()
        assertEquals(1280.0, fix.ele)
        assertFalse(fix.eleIsMsl)
        assertNull(fix.verticalAccuracyMeters)
    }

    @Test
    fun `no altitude gives none`() {
        val fix = location().toFix()
        assertNull(fix.ele)
        assertFalse(fix.eleIsMsl)
    }

    @Test
    fun `the vertical accuracy of a plain altitude is passed on`() {
        val fix =
            location()
                .apply {
                    altitude = 10.0
                    verticalAccuracyMeters = 12f
                }.toFix()
        assertEquals(12.0, fix.verticalAccuracyMeters)
    }
}
