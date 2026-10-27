package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceTest {
    @Test
    fun `a workspace builds a stack with distinct ids in the listed order`() {
        val applied = topoPlanSlopes.toStack { true }
        assertEquals(listOf("ign-plan", "ign-scan25", "ign-pentes"), applied.stack.layers.map { it.sourceId })
        assertEquals(3, applied.stack.layers.map { it.id }.toSet().size)
        assertTrue(applied.skipped.isEmpty())
        assertEquals(0.5f, applied.stack.layers.last().opacity)
        assertEquals(8, applied.stack.layers[1].displayMinZoom)
        assertEquals(16, applied.stack.layers[1].displayMaxZoom)
    }

    @Test
    fun `a stack saved with the retired Rando Multi-Zoom map gets its maps back`() {
        val saved = LayerStack().add(Workspaces.RETIRED_RANDO_SOURCE).add("ign-pentes", opacity = 0.5f)
        val back = Workspaces.replaceRetired(saved) { it != Workspaces.RETIRED_RANDO_SOURCE }
        assertEquals(
            listOf("osm", "opentopomap", "ign-scan25", "osm", "ign-ortho", "ign-pentes"),
            back.layers.map { it.sourceId },
        )
        assertEquals((1..6).map { "${LayerStack.ID_PREFIX}$it" }, back.layers.map { it.id })
        assertEquals(12, back.layers[2].displayMinZoom)
        assertEquals(17, back.layers[2].displayMaxZoom)
        assertEquals(0.5f, back.layers.last().opacity)
        assertTrue(Workspaces.randoMultiZoom.matches(LayerStack(back.layers.dropLast(1))))
    }

    @Test
    fun `an imported copy of the Rando Multi-Zoom file keeps its layer`() {
        val saved = LayerStack().add(Workspaces.RETIRED_RANDO_SOURCE)
        assertEquals(saved, Workspaces.replaceRetired(saved) { true })
        val other = LayerStack().add("osm")
        assertEquals(other, Workspaces.replaceRetired(other) { false })
    }

    @Test
    fun `layers whose source is unknown are skipped and reported`() {
        val applied = topoPlanSlopes.toStack { it != "ign-plan" }
        assertEquals(listOf("ign-scan25", "ign-pentes"), applied.stack.layers.map { it.sourceId })
        assertEquals(listOf("ign-plan"), applied.skipped)
    }

    @Test
    fun `saving a stack and applying it gives the same layers`() {
        val stack =
            LayerStack()
                .add("osm")
                .add("ign-pentes", opacity = 0.4f)
                .let { it.setZoomRange(it.layers.last().id, 6, 14) }
                .let { it.setAdjustments(it.layers.first().id, 0.2f, -0.1f, 0.3f) }
        val workspace = Workspace.of("mine", "Ma pile", stack)
        val back = workspace.toStack { true }.stack
        assertEquals(stack.layers.map { it.copy(id = "") }, back.layers.map { it.copy(id = "") })
    }

    @Test
    fun `workspaces are written as JSON and read back`() {
        val original = topoPlanSlopes
        val decoded = assertNotNull(WorkspaceCodec.decodeOrNull(WorkspaceCodec.encode(original)))
        assertEquals(original, decoded)
        assertNull(WorkspaceCodec.decodeOrNull("not json"))
        assertNull(WorkspaceCodec.decodeOrNull(""))
    }

    @Test
    fun `only shipped presets are flagged as built in`() {
        assertTrue(Workspaces.sortieSki.isBuiltin)
        assertFalse(Workspace.of("mine", "x", LayerStack()).isBuiltin)
    }

    @Test
    fun `a workspace matches the stack it produced, whatever the layer ids`() {
        val stack = topoPlanSlopes.toStack { true }.stack
        assertTrue(topoPlanSlopes.matches(stack))
        assertTrue(topoPlanSlopes.matches(LayerStack(stack.layers.map { it.copy(id = "z${it.id}") })))
    }

    @Test
    fun `a change of opacity, order, visibility, range or colour breaks the match`() {
        val workspace = topoPlanSlopes
        val stack = workspace.toStack { true }.stack
        assertFalse(workspace.matches(stack.setOpacity("l3", 0.8f)))
        assertFalse(workspace.matches(stack.moveUp("l1")))
        assertFalse(workspace.matches(stack.setVisible("l2", false)))
        assertFalse(workspace.matches(stack.setZoomRange("l2", 9, 16)))
        assertFalse(workspace.matches(stack.setInverted("l2", true)))
        assertFalse(workspace.matches(stack.remove("l1")))
        // A hair of difference in a slider is not a change.
        assertTrue(workspace.matches(stack.setOpacity("l3", 0.502f)))
    }

    @Test
    fun `the inverted flag survives saving and applying a workspace`() {
        val stack = LayerStack().add("osm").setInverted("l1", true)
        val back = Workspace.of("mine", "x", stack).toStack { true }.stack
        assertEquals(true, back.layers.single().inverted)
    }

    @Test
    fun `the elevation band and its logic survive saving and applying a workspace`() {
        val stack =
            LayerStack().add(
                "opensnowmap",
            ).setZoomRange("l1", 8, 16).setElevationRange("l1", 1500, null, RangeLogic.OR)
        val workspace = Workspace.of("mine", "x", stack)
        val back =
            WorkspaceCodec.decodeOrNull(WorkspaceCodec.encode(workspace))!!.toStack {
                true
            }.stack.layers.single()
        assertEquals(1500, back.displayMinElevation)
        assertNull(back.displayMaxElevation)
        assertEquals(RangeLogic.OR, back.rangeLogic)
        assertTrue(workspace.matches(stack))
        assertFalse(workspace.matches(stack.setElevationRange("l1", 1600, null, RangeLogic.OR)))
        assertFalse(workspace.matches(stack.setElevationRange("l1", 1500, null, RangeLogic.AND)))
        // Without a zoom range, « ET » and « OU » draw the same thing.
        val bandOnly =
            Workspace.of(
                "b",
                "y",
                LayerStack().add("osm").setElevationRange("l1", 1500, null, RangeLogic.AND),
            )
        assertTrue(bandOnly.matches(LayerStack().add("osm").setElevationRange("l1", 1500, null, RangeLogic.OR)))
    }

    @Test
    fun `an old workspace file without the inverted flag still loads`() {
        val json = """{"id":"a","name":"Old","layers":[{"sourceId":"osm"}]}"""
        val workspace = assertNotNull(WorkspaceCodec.decodeOrNull(json))
        assertFalse(workspace.layers.single().inverted)
    }

    @Test
    fun `the shipped favorites are Rando Multi-Zoom and Sortie Ski, built in with layers and distinct ids`() {
        val all = Workspaces.builtin
        assertTrue(all.all { it.isBuiltin && it.layers.isNotEmpty() })
        assertEquals(all.size, all.map { it.id }.toSet().size)
        // ADR-069: only the author's two setups are shipped.
        assertEquals(listOf("Rando Multi-Zoom", "Sortie Ski"), all.map { it.name })
    }
}
