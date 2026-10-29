package fr.toolmap.tracks

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import fr.toolmap.alert.AlertUi
import fr.toolmap.alert.FollowUi
import fr.toolmap.core.alert.FollowKind
import fr.toolmap.core.track.LibraryFilter
import fr.toolmap.data.tracks.ItemKind
import fr.toolmap.data.tracks.ItemRef
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tracks screens of P29.4: the list and the detail in « Topo » (A), the sheet of an item touched on the map in
 * « Repère » (B), on the library of the preview (`docs/previews/design-04-traces.html`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h2400dp")
class TracksScreensTest {
    @get:Rule
    val rule = createComposeRule()

    private val fixture = TracksFixture()
    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)

    @After
    fun tearDown() = fixture.close()

    private fun waitForLibrary(empty: Boolean = false) =
        fixture.await(rule::waitForIdle) {
            fixture.vm.library.value.let { if (empty) it?.isEmpty == true else fixture.isFilled(it) }
        }

    private fun list(
        empty: Boolean = false,
        onOpenDetail: (Long) -> Unit = {},
        onOpenOnMap: (ItemRef, MapRequest) -> Unit = { _, _ -> },
        onImport: () -> Unit = {},
        onDrawTrack: () -> Unit = {},
    ) {
        fixture.fill(empty)
        waitForLibrary(empty)
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    TracksScreen(
                        vm = fixture.vm,
                        onBack = {},
                        onImport = onImport,
                        onOpenDetail = onOpenDetail,
                        onShowOnMap = {},
                        onOpenOnMap = onOpenOnMap,
                        onDrawTrack = onDrawTrack,
                    )
                }
            }
        }
    }

    @Test
    fun `the empty library says what will be there and offers the import and the drawing`() {
        val calls = ArrayList<String>()
        list(empty = true, onImport = { calls += "import" }, onDrawTrack = { calls += "draw" })
        rule.onNodeWithText("Vos traces et waypoints s'afficheront ici").assertExists()
        rule.onAllNodesWithText("Nouveau dossier").assertCountEquals(0)
        rule.onNodeWithText("Importer un fichier…").performClick()
        rule.onNodeWithText("Dessiner une trace sur la carte").performClick()
        assertEquals(listOf("import", "draw"), calls)
    }

    @Test
    fun `the bar counts the library, the rows give figures without the kind word`() {
        list()
        rule.onNodeWithText("3 traces · 1 itinéraire · 5 waypoints").assertExists()
        rule.onNodeWithText("Fort Barraux – Les Trois Têtes").assertExists()
        // TalkBack still reads the kind, which the line no longer writes.
        rule.onNode(hasContentDescription("Trace, Fort Barraux – Les Trois Têtes", substring = true)).assertExists()
        rule.onNode(hasContentDescription("Waypoint, Les Trois Têtes", substring = true)).assertExists()
        rule.onAllNodesWithText("Trace · ", substring = true).assertCountEquals(0)
    }

    @Test
    fun `an eye shows or hides each item, read as a switch`() {
        list()
        val eyes = { rule.onAllNodes(switch and hasContentDescription("Afficher sur la carte", substring = true)) }
        // The first row is the folder « Chartreuse », shown.
        eyes().onFirst().assertIsOn()
        // Opened, it lists its hidden track, whose eye is off.
        rule.onNodeWithText("Chartreuse").performClick()
        rule.waitUntil(5_000) {
            eyes().fetchSemanticsNodes().any {
                it.config.getOrElseNullable(SemanticsProperties.ToggleableState) { null } == ToggleableState.Off
            }
        }
        eyes().onFirst().performClick()
        fixture.await(rule::waitForIdle) { fixture.vm.library.value?.folders?.first()?.visible == false }
        eyes().onFirst().assertIsOff()
    }

    @Test
    fun `a track opens its detail, a waypoint and a route open the map with their sheet`() {
        val details = ArrayList<Long>()
        val onMap = ArrayList<ItemRef>()
        list(onOpenDetail = { details += it }, onOpenOnMap = { ref, _ -> onMap += ref })
        rule.onNodeWithText("Fort Barraux – Les Trois Têtes").performClick()
        rule.onNodeWithText("Les Trois Têtes").performClick()
        rule.onNodeWithText("Chapareillan → Col du Granier").performClick()
        assertEquals(listOf(fixture.fbt), details)
        assertEquals(
            listOf(ItemRef(ItemKind.WAYPOINT, fixture.tetes), ItemRef(ItemKind.ROUTE, fixture.route)),
            onMap,
        )
    }

    // A text field in a dialog never lets Robolectric go idle on a screen wider than 320 dp (an artefact of the test
    // environment): this one runs on a 320 dp screen, the waypoints alone listed so that one is in sight.
    @Test
    @Config(qualifiers = "w320dp-h700dp")
    fun `the menu of a waypoint has Modifier, which opens the waypoint dialog`() {
        fixture.vm.setLibraryFilter(LibraryFilter.WAYPOINTS)
        list()
        // The folder « Chartreuse » comes first, then the waypoints.
        rule.onAllNodesWithContentDescription("Menu")[1].performClick()
        rule.onNodeWithText("Modifier…").performClick()
        fixture.await(rule::waitForIdle) {
            rule.onAllNodesWithText("Modifier le waypoint").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun detail() {
        fixture.fill()
        waitForLibrary()
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    TrackDetailScreen(
                        vm = fixture.vm,
                        trackId = fixture.fbt,
                        onBack = {},
                        onShowOnMap = {},
                        onDownloadAround = { _, _ -> },
                        onPrintAlong = {},
                        onEdit = {},
                        onFollow = {},
                    )
                }
            }
        }
        fixture.await(rule::waitForIdle) { fixture.vm.detail.value?.trackId == fixture.fbt }
    }

    @Test
    fun `the detail gives four key figures, the profile with its legend and one filled button`() {
        detail()
        rule.onNodeWithText("Trace · 21 oct. 2026").assertExists()
        listOf("Distance", "Dénivelé +", "Dénivelé −", "Temps estimé").forEach {
            rule.onNodeWithText(it).assertExists()
        }
        rule.onNodeWithText("Profil d'altitude").assertExists()
        rule.onNodeWithText("≥ 25 %").assertExists()
        rule.onNode(hasContentDescription("Profil d'altitude de", substring = true)).assertExists()
        rule.onNodeWithText("Afficher sur la carte").assertExists()
    }

    @Test
    fun `the other figures unfold under Tous les chiffres, the tools are grouped`() {
        detail()
        rule.onAllNodesWithText("Altitude minimale").assertCountEquals(0)
        rule.onNodeWithText("Tous les chiffres").performScrollTo().performClick()
        rule.onNodeWithText("Altitude minimale").assertExists()
        listOf("Préparer", "Sur le terrain", "Modifier").forEach { rule.onNodeWithText(it).performScrollTo() }
        listOf("Suivre cette trace", "Inverser le sens", "Simplifier…", "Fusionner avec une autre trace…").forEach {
            rule.onNodeWithText(it).performScrollTo()
        }
    }

    @Test
    fun `renaming, style, description, moving, export and deletion are in the menu of the bar`() {
        detail()
        rule.onNodeWithContentDescription("Partager").assertExists()
        rule.onNodeWithContentDescription("Menu").performClick()
        listOf("Renommer", "Style de la ligne", "Décrire", "Déplacer vers…", "Exporter en GPX…", "Supprimer").forEach {
            rule.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun `a waypoint's sheet reads distance and bearing large, and puts Aller vers ce point forward`() {
        val goes = ArrayList<ItemRef>()
        fixture.fill()
        waitForLibrary()
        val tetes = ItemRef(ItemKind.WAYPOINT, fixture.tetes)
        sheetOf(tetes, from = 45.46 to 5.96, onGoTo = { goes += it })
        rule.onNode(hasContentDescription("depuis votre position", substring = true)).assertExists()
        rule.onNodeWithText("Alerte de proximité").assertExists()
        rule.onAllNodesWithText("Partager").assertCountEquals(0)
        rule.onNodeWithText("Aller vers ce point").performClick()
        assertEquals(listOf(tetes), goes)
    }

    @Test
    fun `without a position the sheet of a waypoint gives no reading`() {
        fixture.fill()
        waitForLibrary()
        sheetOf(ItemRef(ItemKind.WAYPOINT, fixture.tetes), from = null)
        rule.onAllNodes(hasContentDescription("depuis votre position", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("Aller vers ce point").assertExists()
    }

    @Test
    fun `a track's sheet shows its small profile and Suivre cette trace, or Ne plus suivre while followed`() {
        fixture.fill()
        waitForLibrary()
        val followed = AlertUi(follow = FollowUi(FollowKind.TRACK, fixture.fbt, "Fort Barraux", reversed = false))
        sheetOf(ItemRef(ItemKind.TRACK, fixture.fbt), from = null, alerts = followed)
        rule.onNodeWithText("Ne plus suivre").assertExists()
        rule.onNodeWithText("Statistiques").assertExists()
        rule.onNodeWithText("Télécharger autour").assertExists()
        rule.waitUntil(5_000) {
            rule.onAllNodes(
                hasContentDescription("Profil d'altitude de", substring = true),
            ).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun sheetOf(
        ref: ItemRef,
        from: Pair<Double, Double>?,
        alerts: AlertUi = AlertUi(),
        onGoTo: (ItemRef) -> Unit = {},
    ) {
        val library = fixture.vm.library.value!!
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    TappedItemSheet(
                        ref = ref,
                        library = library,
                        from = from,
                        onDetails = {},
                        onHide = {},
                        onEdit = {},
                        onGoTo = onGoTo,
                        onShare = { _, _ -> },
                        onExport = { _, _ -> },
                        onDelete = {},
                        alerts = alerts,
                        onFollow = {},
                        onStopFollowing = {},
                        onWatch = {},
                        onDismiss = {},
                        profileOf = { fixture.vm.profileOf(it) },
                    )
                }
            }
        }
    }
}
