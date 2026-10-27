package fr.toolmap.map

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The floating parts of the map push each other instead of covering each other, at any width (V2). */
@RunWith(RobolectricTestRunner::class)
// A screen larger than every case below, so that the boxes of the tests are not cut by the screen.
@Config(sdk = [35], application = Application::class, qualifiers = "w1000dp-h1000dp")
class MapChromeLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the panels go beside the boxes when they fit there, else below them`() {
        assertTrue(ChromePlacement.panelsBesideBoxes(width = 900, boxesWidth = 300, panelsWidth = 500, gap = 20))
        assertFalse(ChromePlacement.panelsBesideBoxes(width = 900, boxesWidth = 300, panelsWidth = 600, gap = 20))
        assertTrue(ChromePlacement.panelsBesideBoxes(width = 900, boxesWidth = 0, panelsWidth = 900, gap = 20))
        assertEquals(0, ChromePlacement.panelsY(boxesHeight = 400, gap = 20, beside = true))
        assertEquals(420, ChromePlacement.panelsY(boxesHeight = 400, gap = 20, beside = false))
    }

    @Test
    fun `the panels stay centred on the screen unless the boxes push them right`() {
        assertEquals(
            350,
            ChromePlacement.panelsX(width = 1000, boxesWidth = 200, panelsWidth = 300, gap = 20, beside = true),
        )
        assertEquals(
            320,
            ChromePlacement.panelsX(width = 1000, boxesWidth = 300, panelsWidth = 600, gap = 20, beside = true),
        )
        assertEquals(
            100,
            ChromePlacement.panelsX(width = 1000, boxesWidth = 300, panelsWidth = 800, gap = 20, beside = false),
        )
    }

    @Test
    fun `the buttons of the right go above the bar only when both do not fit on one line`() {
        assertFalse(ChromePlacement.endAboveBar(width = 1000, barWidth = 700, endWidth = 150, gap = 20))
        assertTrue(ChromePlacement.endAboveBar(width = 800, barWidth = 700, endWidth = 150, gap = 20))
        assertFalse(ChromePlacement.endAboveBar(width = 600, barWidth = 700, endWidth = 0, gap = 20))
        assertEquals(830, ChromePlacement.attributionMaxWidth(width = 1000, endWidth = 150, gap = 20))
        assertEquals(1000, ChromePlacement.attributionMaxWidth(width = 1000, endWidth = 0, gap = 20))
    }

    @Test
    fun `a zone starts below the boxes on a phone held upright, right of them when held sideways`() {
        assertEquals(
            ChromePlacement.Area(0, 400, 1080, 2400),
            ChromePlacement.freeOfBoxes(width = 1080, height = 2400, boxesRight = 500, boxesBottom = 400),
        )
        assertEquals(
            ChromePlacement.Area(500, 0, 2400, 1080),
            ChromePlacement.freeOfBoxes(width = 2400, height = 1080, boxesRight = 500, boxesBottom = 400),
        )
        assertEquals(
            ChromePlacement.Area(0, 0, 1080, 2400),
            ChromePlacement.freeOfBoxes(width = 1080, height = 2400, boxesRight = 0, boxesBottom = 0),
        )
    }

    @Test
    fun `on a narrow screen a wide panel goes below the boxes`() {
        val (boxes, panel) = top(width = 360.dp, boxes = 200.dp to 150.dp, panel = 300.dp to 120.dp)
        assertDisjoint(boxes, panel)
        assertTrue(panel.top >= boxes.bottom)
    }

    @Test
    fun `on a wide screen a panel goes beside the boxes`() {
        val (boxes, panel) = top(width = 900.dp, boxes = 200.dp to 150.dp, panel = 300.dp to 120.dp)
        assertDisjoint(boxes, panel)
        assertEquals(0f, panel.top)
    }

    @Test
    fun `the recording chip stays on the line of the boxes when both fit there`() {
        assertTrue(ChromePlacement.cornerBesideBoxes(width = 360, boxesWidth = 200, cornerWidth = 130, gap = 8))
        assertFalse(ChromePlacement.cornerBesideBoxes(width = 300, boxesWidth = 200, cornerWidth = 130, gap = 8))
        assertTrue(ChromePlacement.cornerBesideBoxes(width = 300, boxesWidth = 0, cornerWidth = 130, gap = 8))
        assertTrue(ChromePlacement.cornerBesideBoxes(width = 300, boxesWidth = 200, cornerWidth = 0, gap = 8))
    }

    @Test
    fun `on a phone the recording chip takes the top right corner and the panels go under both`() {
        val (boxes, corner, panel) = topWithCorner(width = 360.dp)
        assertEquals(0f, corner.top)
        assertEquals(360f, corner.right)
        assertTrue(panel.top >= boxes.bottom)
        assertNoneOverlaps(boxes, corner, panel)
    }

    @Test
    fun `on a narrow screen the recording chip goes under the boxes, on the right, and the panels under it`() {
        val (boxes, corner, panel) = topWithCorner(width = 300.dp)
        assertTrue(corner.top >= boxes.bottom)
        assertEquals(300f, corner.right)
        assertTrue(panel.top >= corner.bottom)
        assertNoneOverlaps(boxes, corner, panel)
    }

    @Test
    fun `on a wide screen the panels stay on the line, between the boxes and the recording chip`() {
        val (boxes, corner, panel) = topWithCorner(width = 900.dp)
        assertEquals(0f, panel.top)
        assertEquals(0f, corner.top)
        assertNoneOverlaps(boxes, corner, panel)
    }

    @Test
    fun `the credits wrap before the zoom buttons and the bar keeps the corner`() {
        rule.setContent {
            Box(Modifier.size(360.dp, 640.dp)) {
                MapBottomChrome(
                    attribution = { Box(Modifier.size(500.dp, 30.dp).testTag("credits")) },
                    bar = { Box(Modifier.size(250.dp, 56.dp).testTag("bar")) },
                    end = { Box(Modifier.size(48.dp, 104.dp).testTag("zoom")) },
                )
            }
        }
        val credits = bounds("credits")
        val bar = bounds("bar")
        val zoom = bounds("zoom")
        assertDisjoint(credits, zoom)
        assertDisjoint(bar, zoom)
        assertDisjoint(credits, bar)
        assertEquals(640f - 56f, bar.top)
        assertEquals(360f, zoom.right)
    }

    @Test
    fun `on a narrow screen the zoom buttons stand above the bar`() {
        rule.setContent {
            Box(Modifier.size(280.dp, 640.dp)) {
                MapBottomChrome(
                    attribution = { },
                    bar = { Box(Modifier.size(250.dp, 56.dp).testTag("bar")) },
                    end = { Box(Modifier.size(48.dp, 104.dp).testTag("zoom")) },
                )
            }
        }
        val bar = bounds("bar")
        val zoom = bounds("zoom")
        assertDisjoint(bar, zoom)
        assertTrue(zoom.bottom <= bar.top)
    }

    private fun top(
        width: Dp,
        boxes: Pair<Dp, Dp>,
        panel: Pair<Dp, Dp>,
    ): Pair<Rect, Rect> {
        rule.setContent {
            Box(Modifier.size(width, 800.dp)) {
                MapTopChrome(
                    boxes = { Box(Modifier.size(boxes.first, boxes.second).testTag("boxes")) },
                    panels = { Box(Modifier.size(panel.first, panel.second).testTag("panel")) },
                )
            }
        }
        return bounds("boxes") to bounds("panel")
    }

    private fun topWithCorner(width: Dp): Triple<Rect, Rect, Rect> {
        rule.setContent {
            Box(Modifier.size(width, 800.dp)) {
                MapTopChrome(
                    boxes = { Box(Modifier.size(200.dp, 150.dp).testTag("boxes")) },
                    panels = { Box(Modifier.size(300.dp, 120.dp).testTag("panel")) },
                    corner = { Box(Modifier.size(130.dp, 32.dp).testTag("corner")) },
                )
            }
        }
        return Triple(bounds("boxes"), bounds("corner"), bounds("panel"))
    }

    private fun assertNoneOverlaps(vararg rects: Rect) {
        rects.forEachIndexed { i, a -> rects.drop(i + 1).forEach { b -> assertDisjoint(a, b) } }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().toRect()

    private fun DpRect.toRect() = Rect(left.value, top.value, right.value, bottom.value)

    private fun assertDisjoint(
        a: Rect,
        b: Rect,
    ) {
        assertFalse(a.overlaps(b), "$a overlaps $b")
    }
}
