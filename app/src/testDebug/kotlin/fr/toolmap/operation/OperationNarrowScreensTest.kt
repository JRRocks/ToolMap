package fr.toolmap.operation

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import fr.toolmap.core.hierarchy.HierarchyState
import fr.toolmap.core.hierarchy.Post
import fr.toolmap.core.record.Fix
import fr.toolmap.data.hierarchy.OperationController
import fr.toolmap.data.hierarchy.OperationStorage
import fr.toolmap.operation.demo.DemoKeyVault
import fr.toolmap.record.LocationSource
import fr.toolmap.share.STEP_LABELS_TAG
import fr.toolmap.ui.Formats
import fr.toolmap.ui.LocalFormats
import fr.toolmap.ui.theme.ToolMapTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The screens of the « Opération » mode on a narrow phone (320 dp wide, the emulator of the CI): no text is cut or
 * ends in « … » (P39.8). A line may wrap, as the maquettes allow; a word may not be clipped (« Admini »).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w320dp-h640dp")
class OperationNarrowScreensTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val temp = TemporaryFolder()

    private val noGps =
        object : LocationSource {
            override fun fixes(
                minIntervalMillis: Long,
                minDistanceMeters: Float,
            ): Flow<Fix> = emptyFlow()
        }

    /** The texts cut on a screen, « where: text ». */
    private val cut = ArrayList<String>()

    private fun session(demo: Boolean): OperationSession {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val storage = OperationStorage(temp.newFolder("operations"), DemoKeyVault())
        val session = OperationSession(context, OperationController(storage, null, { null }), storage, noGps)
        if (!demo) return session
        session.startDemo()
        var waited = 0
        while (session.demo.value == null && waited < DEMO_WAIT_MS) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(POLL_MS)
            waited += POLL_MS.toInt()
        }
        assertNotNull(session.demo.value, "the demonstration is built")
        return session
    }

    /** Every text laid out on the screen (dialogs and sheets included) fits: no clipped line, no ellipsis. */
    private fun checkTexts(where: String) {
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        val texts = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        for (node in rule.onAllNodes(texts, useUnmergedTree = true).fetchSemanticsNodes()) {
            val layouts = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            for (layout in layouts) if (isCut(layout)) cut += "$where: « ${layout.layoutInput.text} »"
        }
    }

    /**
     * A text is cut when lines are dropped (more lines than it may show, or than its height), when a line ends in
     * « … », when a single line is wider than its place, or when one of its words is (it would be broken in two).
     */
    private fun isCut(layout: TextLayoutResult): Boolean {
        val width = layout.size.width + 1f
        val intrinsics = layout.multiParagraph.intrinsics
        return layout.didOverflowHeight ||
            (0 until layout.lineCount).any { layout.isLineEllipsized(it) } ||
            (!layout.layoutInput.softWrap && intrinsics.maxIntrinsicWidth > width) ||
            intrinsics.minIntrinsicWidth > width
    }

    private fun OperationSession.at(
        where: String,
        number: Int,
        nav: OperationNav = OperationNav(),
    ) {
        viewAs(number)
        this.nav.value = nav
        checkTexts(where)
        this.nav.value = OperationNav()
    }

    private fun OperationSession.groupNamed(name: String): Long =
        requireNotNull(demoSeat(1)?.view?.value?.groups?.firstOrNull { it.name == name }).id

    private fun OperationSession.member(number: Int): Long = requireNotNull(demo.value?.ids?.get(number))

    @Test
    fun `the screens of every role fit a narrow phone`() {
        val session = session(demo = true)
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    Box {
                        OperationRoot(session, onBack = {})
                        ShareWindows(session, setName = { "Membre 23" })
                    }
                }
            }
        }
        val nord = session.groupNamed("Nord")
        val sud = session.groupNamed("Sud")
        val ouest = session.groupNamed("Ouest")

        fun page(vararg pages: OpPage) = OperationNav(pages = listOf(OpPage.Main) + pages)
        // The screen of the operation, from the place of each role, and its menu.
        for (n in listOf(1, 2, 4, 5, 7, 14, 21)) session.at("main $n", n)
        session.at("menu 1", 1, OperationNav(menu = true))
        session.at("group list", 7, page(OpPage.GroupList(sud)))
        // The administration.
        session.at("admin", 5, page(OpPage.Admin))
        session.at("waiting", 5, page(OpPage.Admin, OpPage.WaitingList))
        session.at("group Nord", 5, page(OpPage.Admin, OpPage.GroupPage(nord)))
        session.at("group Ouest", 5, page(OpPage.Admin, OpPage.GroupPage(ouest)))
        session.at("operation chief", 5, page(OpPage.Admin, OpPage.OperationChief))
        session.at("admins 1", 1, page(OpPage.Admin, OpPage.Admins))
        session.at("admins 5", 5, page(OpPage.Admin, OpPage.Admins))
        session.at("name requests", 5, page(OpPage.Admin, OpPage.NameRequests))
        // The sheets.
        val sheets =
            listOf(
                1 to OpSheet.Invite,
                1 to OpSheet.Transfer,
                1 to OpSheet.Successor,
                1 to OpSheet.NameAdmin,
                5 to OpSheet.Place(session.member(21)),
                5 to OpSheet.AddMembers(nord),
                5 to OpSheet.Nominate(nord, Post.DEPUTY),
                5 to OpSheet.Nominate(HierarchyState.ROOT, Post.LEADER),
                5 to OpSheet.NewGroup,
                5 to OpSheet.EditGroup(ouest),
                1 to OpSheet.Member(session.member(11)),
                5 to OpSheet.Member(session.member(11)),
                5 to OpSheet.Member(session.member(10)),
                5 to OpSheet.RenameMember(session.member(11)),
                7 to OpSheet.MyName,
                5 to OpSheet.MyName,
                1 to OpSheet.RenameOperation,
                1 to OpSheet.Frequency(),
                1 to OpSheet.Frequency(draft = 45),
                1 to OpSheet.Frequency(draft = 20, typing = true),
            )
        for ((n, sheet) in sheets) session.at("sheet $sheet ($n)", n, OperationNav(sheet = sheet))
        // The minutes under the notches of the rhythm do not run into one another (« 1 mi2 », « 19 min » on the
        // emulator of the CI in 0.4.262, when they were not placed under the notches): at 320 dp only some are
        // written, « 1 min » and « 10 min » always, at least 4 dp apart (F8, `tour6/img/11-f8-b-pousse`).
        session.viewAs(1)
        session.nav.value = OperationNav(sheet = OpSheet.Frequency(draft = 120))
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        val written =
            rule
                .onNodeWithTag(STEP_LABELS_TAG, useUnmergedTree = true)
                .onChildren()
                .fetchSemanticsNodes()
                .filter { it.layoutInfo.isPlaced }
                .sortedBy { it.boundsInRoot.left }
        val texts = written.map { it.config[SemanticsProperties.Text].joinToString() }
        assertEquals("1 min", texts.first(), "written: $texts")
        assertEquals("10 min", texts.last(), "written: $texts")
        assertTrue(texts.size < 10, "not every minute fits at 320 dp: $texts")
        val gap = with(rule.density) { 4.dp.toPx() }
        written.map { it.boundsInRoot }.zipWithNext().forEach { (a, b) ->
            assertTrue(a.right + gap <= b.left + 1f, "labels 4 dp apart: $texts, $a, $b")
        }
        session.nav.value = OperationNav()
        // The windows.
        val dialogs =
            listOf(
                7 to OpDialog.Quit,
                4 to OpDialog.Quit,
                2 to OpDialog.Quit,
                1 to OpDialog.CreatorQuit,
                5 to OpDialog.Unassign(session.member(11)),
                5 to OpDialog.Unassign(session.member(10)),
                1 to OpDialog.RemoveMember(session.member(11)),
                5 to OpDialog.DeleteGroup(ouest),
                1 to OpDialog.RemoveAdmin(session.member(5)),
            )
        for ((n, dialog) in dialogs) session.at("dialog $dialog ($n)", n, OperationNav(dialog = dialog))
        for (slide in listOf(0.45f, 1f)) {
            session.at("slide $slide", 1, OperationNav(dialog = OpDialog.CreatorQuit, slide = slide))
        }
        // The question of an invitation, and the 31st.
        session.viewReal()
        val link = requireNotNull(runBlocking { session.demoSeat(1)?.invitationLink() })
        assertTrue(session.onCode(link))
        checkTexts("join")
        session.closeWindows()
        session.full.value = "Exercice Vallon"
        checkTexts("full")
        session.closeWindows()
        assertTrue(cut.isEmpty(), "texts cut at 320 dp:\n" + cut.distinct().joinToString("\n"))
    }

    @Test
    fun `the entry and the creation fit a narrow phone`() {
        val session = session(demo = false)
        session.createForm.value =
            CreateForm(name = "Exercice Vallon", groups = listOf("Nord", "Sud", "Est"), myGroup = 2)
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    Column {
                        OperationCard(onCreate = {})
                        CreatePage(session, defaultName = { "Membre 1" }, onClose = {}, onCreated = {})
                    }
                }
            }
        }
        checkTexts("card and creation")
        assertTrue(cut.isEmpty(), "texts cut at 320 dp:\n" + cut.distinct().joinToString("\n"))
    }

    @Test
    fun `with no name set in the app, the entry is empty, fits, and « Rejoindre » waits for a name`() {
        val session = session(demo = false)
        var setName = ""
        // The empty field takes the caret, which blinks for ever: the clock is moved by hand, or the rule would never
        // be idle (as in `TracksRenderTest`).
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    Box(Modifier.fillMaxSize()) { ShareWindows(session, setName = { setName }) }
                }
            }
        }
        val none = "Aucun nom n'est réglé dans l'application"
        val missing = "Il faut un nom pour entrer."

        fun shown(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

        fun step(where: String) {
            rule.mainClock.advanceTimeBy(SETTLE_MS)
            checkTexts(where)
        }

        fun join() = rule.onNode(hasText("Rejoindre") and hasClickAction())
        // `tour5/05-entree-sans-nom` A: empty, a grey help, nothing in red, « Rejoindre » grey.
        session.join.value = JoinQuestion("code-1", "Exercice Vallon")
        step("join, no name")
        assertTrue(shown(none) && !shown(missing) && !shown("SM-"), "empty field, grey help")
        join().assertIsNotEnabled()
        // `05-entree-sans-nom-ecrit` A: a name written, « Rejoindre » active, the same help.
        session.typeJoinName("Prénom N.")
        step("join, name written")
        assertTrue(shown(none), "the same help")
        join().assertIsEnabled()
        // Erased: red, as before.
        session.typeJoinName("")
        step("join, name erased")
        assertTrue(shown(missing) && !shown(none), "red once erased")
        join().assertIsNotEnabled()
        // A name set in the app fills the field, as before.
        setName = "Guide 21"
        session.join.value = JoinQuestion("code-2", "Exercice Vallon")
        step("join, name set")
        assertTrue(shown("Guide 21") && shown("C'est le nom réglé dans l'application") && !shown(none), "name set")
        join().assertIsEnabled()
        assertTrue(cut.isEmpty(), "texts cut at 320 dp:\n" + cut.distinct().joinToString("\n"))
    }

    /**
     * The room the keyboard leaves on a phone 320 dp wide, the text of Android at 200 % (k1 A,
     * `tour7/img/04-etroit-texte-max`): the buttons stay in the window, the middle scrolls with the whole field in
     * sight, and the text under it is read by scrolling instead of being cut.
     */
    @Test
    @Config(qualifiers = "w320dp-h400dp", fontScale = 2f)
    fun `above the keyboard with the largest text, the buttons stay and the middle scrolls`() {
        val session = session(demo = false)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ToolMapTheme {
                CompositionLocalProvider(LocalFormats provides Formats()) {
                    Box(Modifier.fillMaxSize()) { ShareWindows(session, setName = { "" }) }
                }
            }
        }
        session.join.value = JoinQuestion("code-1", "Exercice Vallon")
        rule.mainClock.advanceTimeBy(SETTLE_MS)
        shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onNode(hasText("Annuler") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Rejoindre") and hasClickAction()).assertIsDisplayed()
        val middle = rule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        val range = middle.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(range.maxValue() > 0f, "the middle scrolls at this size")
        val room = middle.fetchSemanticsNode().boundsInRoot
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val inSight = field.top >= room.top - 1f && field.bottom <= room.bottom + 1f
        assertTrue(inSight, "the whole field in sight: $field in $room")
        // Scrolled to the end (by hand: `performScrollTo` waits for a scroll that only moves with the clock), the end
        // of « Vous arrivez « en attente »… » is in sight.
        middle.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
        rule.mainClock.advanceTimeBy(SCROLL_MS)
        val end = rule.onNodeWithText("Vous arrivez", substring = true).fetchSemanticsNode().boundsInRoot
        val scrolled = middle.fetchSemanticsNode().boundsInRoot
        assertTrue(
            end.bottom <= scrolled.bottom + 1f && end.bottom > scrolled.top,
            "the end of the text: $end in $scrolled",
        )
    }

    private companion object {
        const val DEMO_WAIT_MS = 120_000
        const val POLL_MS = 100L
        const val SETTLE_MS = 200L
        const val SCROLL_MS = 1_000L
    }
}
