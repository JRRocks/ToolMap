package fr.toolmap.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records what the user does first with the app: cold start, the map drawn, a few moves and a zoom, then the main
 * screens. The classes and methods it runs are compiled ahead of time on the phone (Baseline Profile).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        val appId =
            InstrumentationRegistry.getArguments().getString("targetAppId")
                ?: throw IllegalStateException("targetAppId not passed as instrumentation runner arg")
        rule.collect(packageName = appId, includeInStartupProfile = false) {
            // Permissions are asked once; the profile must not stop on the dialog.
            device.executeShellCommand("pm grant $appId android.permission.ACCESS_FINE_LOCATION")
            device.executeShellCommand("pm grant $appId android.permission.ACCESS_COARSE_LOCATION")
            device.executeShellCommand("pm grant $appId android.permission.POST_NOTIFICATIONS")
            pressHome()
            startActivityAndWait()
            // The first tiles come from the network on a fresh install: give the map time to draw.
            device.waitForIdle()
            Thread.sleep(MAP_DRAW_MS)

            val w = device.displayWidth
            val h = device.displayHeight
            repeat(MOVES) {
                device.swipe(w * 3 / 4, h / 2, w / 4, h / 2, SWIPE_STEPS)
                device.waitForIdle()
                device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, SWIPE_STEPS)
                device.waitForIdle()
                device.swipe(w / 4, h / 2, w * 3 / 4, h / 2, SWIPE_STEPS)
                device.waitForIdle()
                device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, SWIPE_STEPS)
                device.waitForIdle()
            }
            device.findObject(By.pkg(appId))?.apply {
                pinchOpen(PINCH_PERCENT)
                pinchClose(PINCH_PERCENT)
            }
            Thread.sleep(MAP_DRAW_MS)

            // The bottom buttons of the map (content descriptions): each one opens a sheet or a full screen.
            for (description in listOf("Zoomer", "Dézoomer", "Cartes", "Traces et waypoints")) {
                openAndBack(appId, By.desc(description))
            }
            // The menu, then the settings it leads to.
            device.findObject(By.desc("Menu"))?.let { menu ->
                menu.click()
                device.waitForIdle()
                openAndBack(appId, By.text("Réglages…"))
            }
            // Scroll whatever list is on screen once, to compile the list paths.
            device.findObject(By.scrollable(true))?.fling(Direction.DOWN)
        }
    }

    private fun MacrobenchmarkScope.openAndBack(
        appId: String,
        selector: BySelector,
    ) {
        val entry = device.findObject(selector) ?: return
        entry.click()
        device.wait(Until.hasObject(By.pkg(appId)), SCREEN_WAIT_MS)
        device.waitForIdle()
        Thread.sleep(SCREEN_SETTLE_MS)
        device.pressBack()
        device.waitForIdle()
    }

    private companion object {
        const val MAP_DRAW_MS = 4_000L
        const val MOVES = 2
        const val SWIPE_STEPS = 20
        const val PINCH_PERCENT = 0.4f
        const val SCREEN_WAIT_MS = 3_000L
        const val SCREEN_SETTLE_MS = 1_500L
    }
}
