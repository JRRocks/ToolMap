package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FavoriteChangesTest {
    private val rando = Workspaces.randoEte
    private val winter = Workspaces.randoHiver

    private fun stackOf(favorite: Workspace) = favorite.toStack { true }.stack

    private fun LayerStack.idOf(sourceId: String) = layers.last { it.sourceId == sourceId }.id

    @Test
    fun `a stack applied from a favorite changes nothing`() {
        assertTrue(FavoriteChanges.of(rando, stackOf(rando)).isEmpty())
        assertTrue(FavoriteChanges.of(winter, stackOf(winter)).isEmpty())
    }

    @Test
    fun `a new opacity is one change of that layer, with the value of the favorite`() {
        val stack = stackOf(rando).let { it.setOpacity(it.idOf("ign-pentes"), 0.5f) }
        val change = FavoriteChanges.of(rando, stack).single() as FavoriteChange.Changed
        assertEquals("ign-pentes", change.sourceId)
        assertEquals(stack.idOf("ign-pentes"), change.layerId)
        assertEquals(setOf(LayerField.OPACITY), change.fields)
        assertEquals(0.31f, change.was.opacity, 0.001f)
    }

    @Test
    fun `visibility, zoom range, elevation band and colours are told apart, top layer first`() {
        val base = stackOf(winter)
        val stack =
            base
                .setVisible(base.idOf("ign-pentes"), false)
                .setHue(base.idOf("TRACESHIVERNALES"), 0f)
                .setZoomRange(base.idOf("OSMSKIM"), 12, null)
                .setElevationRange(base.idOf("OSMSNOWM"), 1800, null, RangeLogic.AND)
        val changes = FavoriteChanges.of(winter, stack).map { it as FavoriteChange.Changed }
        assertEquals(listOf("ign-pentes", "TRACESHIVERNALES", "OSMSKIM", "OSMSNOWM"), changes.map { it.sourceId })
        assertEquals(
            listOf(LayerField.VISIBILITY, LayerField.COLOURS, LayerField.ZOOM, LayerField.ELEVATION),
            changes.map { it.fields.single() },
        )
        // A hair of difference in a slider is not a change.
        assertTrue(
            FavoriteChanges.of(
                rando,
                stackOf(rando).let {
                    it.setOpacity(it.idOf("ign-pentes"), 0.312f)
                },
            ).isEmpty(),
        )
    }

    @Test
    fun `a map added, a map removed and a new order are reported, the second OpenStreetMap kept apart`() {
        val base = stackOf(rando)
        val closeOsm = base.layers.filter { it.sourceId == "osm" }[1].id
        val stack = base.remove(closeOsm).add("opensnowmap")
        val changes = FavoriteChanges.of(rando, stack)
        assertEquals(FavoriteChange.Added(stack.layers.last().id, "opensnowmap"), changes.first())
        val removed = changes.filterIsInstance<FavoriteChange.Removed>().single()
        assertEquals("osm", removed.was.sourceId)
        assertEquals(18, removed.was.displayMinZoom)
        assertEquals(2, changes.size)

        val moved = base.moveUp(base.idOf("opentopomap"))
        assertEquals(listOf<FavoriteChange>(FavoriteChange.Reordered), FavoriteChanges.of(rando, moved))
    }

    @Test
    fun `a favorite matches a stack exactly when there is no change`() {
        val base = stackOf(rando)
        val stacks =
            listOf(
                base,
                base.setOpacity(base.idOf("ign-pentes"), 0.5f),
                base.moveDown(base.idOf("ign-ortho")),
                base.remove(base.idOf("ign-pentes")),
                base.add("osm"),
                base.setInverted(base.idOf("ign-scan25"), true),
                LayerStack(),
            )
        for (stack in stacks) assertEquals(rando.matches(stack), FavoriteChanges.of(rando, stack).isEmpty(), "$stack")
    }

    @Test
    fun `an updated built-in preset takes its place in the list, a deleted one stays out`() {
        val mine = Workspace(rando.id, rando.name, rando.layers.dropLast(1))
        val saved = Workspace("ws-1", "Chartreuse", listOf(WorkspaceLayer("osm")))
        val list = Favorites.list(hidden = setOf(winter.id), overrides = mapOf(mine.id to mine), saved = listOf(saved))
        assertEquals(listOf(mine, saved), list)
        assertEquals(listOf(rando, winter, saved), Favorites.list(emptySet(), emptyMap(), listOf(saved)))
        assertTrue(Favorites.isOverridden(mine))
        assertFalse(Favorites.isOverridden(rando))
        assertFalse(Favorites.isOverridden(saved))
        assertEquals(rando, Favorites.original(mine.id))
        assertNull(Favorites.original(saved.id))
    }

    @Test
    fun `the favorite shown as applied is the origin while the stack is still it`() {
        // The user's own copy of a built-in preset: the same maps under another id.
        val copy = rando.copy(id = "ws-copy")
        val favorites = Workspaces.builtin + copy
        val stack = stackOf(copy)
        assertEquals("ws-copy", Favorites.applied(favorites, "ws-copy", stack)?.id)
        assertEquals(rando.id, Favorites.applied(favorites, null, stack)?.id)
        assertEquals(rando.id, Favorites.applied(favorites, winter.id, stack)?.id)
        assertNull(Favorites.applied(favorites, rando.id, stack.setOpacity(stack.idOf("ign-pentes"), 0.5f)))
    }

    @Test
    fun `the origin is modified once the stack changes, never with an empty stack or one that is a favorite`() {
        val favorites = Workspaces.builtin
        val stack = stackOf(rando)
        val changed = stack.setOpacity(stack.idOf("ign-pentes"), 0.5f)
        assertNull(Favorites.modified(favorites, rando.id, stack))
        assertEquals(rando, Favorites.modified(favorites, rando.id, changed))
        assertNull(Favorites.modified(favorites, null, changed))
        assertNull(Favorites.modified(favorites, rando.id, LayerStack()))
        // The origin was deleted: nothing to update.
        assertNull(Favorites.modified(favorites, "ws-gone", changed))
        // Changed into another favorite: that one is shown as applied instead.
        assertNull(Favorites.modified(favorites, rando.id, stackOf(winter)))
    }

    @Test
    fun `a name is taken by another favorite whatever its case and spaces, never by the favorite itself`() {
        val favorites = Workspaces.builtin
        assertTrue(Favorites.nameTaken(favorites, rando.id, "Rando Hiver"))
        assertTrue(Favorites.nameTaken(favorites, rando.id, "  rando hiver "))
        assertFalse(Favorites.nameTaken(favorites, rando.id, "Rando été"))
        assertFalse(Favorites.nameTaken(favorites, rando.id, "Rando été 2026"))
    }

    @Test
    fun `a renamed built-in preset keeps its maps and is the user's version until the original comes back`() {
        val renamed = Favorites.renamed(rando, " Rando été 2026 ")
        assertEquals("Rando été 2026", renamed.name)
        assertEquals(rando.id, renamed.id)
        assertEquals(rando.layers, renamed.layers)
        assertTrue(renamed.matches(stackOf(rando)))
        assertTrue(Favorites.isOverridden(renamed))
        assertFalse(Favorites.isOverridden(Favorites.renamed(renamed, rando.name)))
    }
}
