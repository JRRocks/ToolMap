package fr.toolmap.tracks

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fr.toolmap.alert.AlertUi
import fr.toolmap.core.route.LegMode
import fr.toolmap.core.route.LegState
import fr.toolmap.core.route.RouteLeg
import fr.toolmap.core.route.RoutePlan
import fr.toolmap.core.route.RoutePlanGpx
import fr.toolmap.core.route.RouteStop
import fr.toolmap.core.route.RouteSummary
import fr.toolmap.core.route.RoutingProfiles
import fr.toolmap.core.track.TrackJson
import fr.toolmap.core.track.TrackPoint
import fr.toolmap.core.track.Waypoint
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.data.tracks.RouteEntity
import fr.toolmap.data.tracks.TrackLibrary
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The sheet of a saved route (P19.6, screen 6 of `docs/previews/p19-routing.html`): the line « km · ↑ · ↓ · durée ·
 * profil » read from the plan kept in the GPX extensions. Since P29.4 (« Repère ») « Suivre cet itinéraire » is its one
 * filled action, « Modifier le tracé » and « Convertir en trace » its tiles, and the rest is in its ⋮.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RouteSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val a = RouteStop(45.0, 6.0)
    private val b = RouteStop(45.01, 6.0)
    private val c = RouteStop(45.02, 6.0)

    private fun leg(
        from: RouteStop,
        to: RouteStop,
        time: Long,
    ) = RouteLeg(
        RoutingProfiles.BIKE,
        LegMode.ROUTED,
        LegState.OK,
        listOf(TrackPoint(from.lat, from.lon, 1000.0), TrackPoint(to.lat, to.lon, 1100.0)),
        time,
    )

    /** A route by bike, A → B → C, 10 + 5 min: 15 min. */
    private val plan = RoutePlan(listOf(a, b, c), listOf(leg(a, b, 600_000), leg(b, c, 300_000)))

    private fun route(
        points: List<TrackPoint>,
        extrasJson: String?,
    ) = RouteEntity(
        id = 7,
        name = "Col du Granier",
        extrasJson = extrasJson,
        colorArgb = 0xFFE53935.toInt(),
        widthDp = 4f,
        createdAt = 0,
        pointCount = points.size,
        minLat = 45.0,
        minLon = 6.0,
        maxLat = 45.02,
        maxLon = 6.0,
        distanceMeters = 2224.0,
        pointsJson = TrackJson.encodeWaypoints(points.map { Waypoint(it.lat, it.lon, it.ele) }),
    )

    private fun show(
        route: RouteEntity,
        onConvert: (ItemRef, String) -> Unit = { _, _ -> },
    ) {
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    TappedItemSheet(
                        ref = ItemRef(ItemKind.ROUTE, route.id),
                        library = TrackLibrary(emptyList(), emptyList(), listOf(route), emptyList()),
                        from = null,
                        onDetails = {},
                        onHide = {},
                        onEdit = {},
                        onGoTo = {},
                        onShare = { _, _ -> },
                        onExport = { _, _ -> },
                        onDelete = {},
                        alerts = AlertUi(),
                        onFollow = {},
                        onStopFollowing = {},
                        onWatch = {},
                        onDismiss = {},
                        onConvert = onConvert,
                    )
                }
            }
        }
    }

    @Test
    fun `a route following the ways gives its length, climb, time and profile`() {
        show(route(plan.geometry, TrackJson.encodeExtras(RoutePlanGpx.attach(emptyList(), plan))))
        rule.onNodeWithText("Col du Granier").assertExists()
        val s = RouteSummary.of(plan, RoutingProfiles.BIKE)
        val fmt = Formats()
        val line = "${fmt.distance(2224.0)} · ↑ ${fmt.elevation(s.ascentMeters)} · ↓ ${fmt.elevation(s.descentMeters)}"
        rule.onNodeWithText("$line · 15 min · Vélo").assertExists()
    }

    @Test
    fun `a route drawn by hand gives no time and no profile`() {
        val points = listOf(a.point, b.point, c.point)
        show(route(points, null))
        rule.onNodeWithText(Formats().distance(2224.0)).assertExists()
        rule.onAllNodesWithText("min", substring = true).assertCountEquals(0)
        rule.onAllNodesWithText("Vélo", substring = true).assertCountEquals(0)
    }

    @Test
    fun `one filled action and two tiles, and « Convertir en trace » converts this route`() {
        val converted = ArrayList<Pair<ItemRef, String>>()
        show(route(plan.geometry, null)) { ref, label -> converted += ref to label }
        listOf("Suivre cet itinéraire", "Modifier le tracé", "Convertir en trace").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onAllNodesWithText("Statistiques").assertCountEquals(0)
        rule.onNodeWithText("Convertir en trace").performClick()
        assertEquals(listOf(ItemRef(ItemKind.ROUTE, 7) to "Col du Granier"), converted)
    }

    @Test
    fun `sharing, export, hiding and deletion are in the menu of the sheet`() {
        show(route(plan.geometry, null))
        rule.onAllNodesWithText("Partager").assertCountEquals(0)
        rule.onNodeWithContentDescription("Menu").performClick()
        listOf("Partager", "Exporter", "Masquer", "Supprimer").forEach { rule.onNodeWithText(it).assertExists() }
    }
}
