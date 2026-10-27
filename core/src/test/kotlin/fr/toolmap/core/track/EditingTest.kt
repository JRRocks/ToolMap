package fr.toolmap.core.track

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditingTest {
    private fun line(count: Int) =
        List(count) { i -> TrackPoint(45.0 + i * 0.001, 6.0, ele = 1000.0 + i, timeMillis = 1000L * i) }

    @Test
    fun `reverse flips segments and points and drops the times`() {
        val a = listOf(TrackPoint(1.0, 1.0, timeMillis = 5), TrackPoint(2.0, 2.0, timeMillis = 6))
        val b = listOf(TrackPoint(3.0, 3.0, timeMillis = 7), TrackPoint(4.0, 4.0, timeMillis = 8))
        val r = TrackOps.reverse(listOf(a, b))
        assertEquals(listOf(4.0, 3.0), r[0].map { it.lat })
        assertEquals(listOf(2.0, 1.0), r[1].map { it.lat })
        assertTrue(r.flatten().all { it.timeMillis == null })
        // Reversing twice gives the points back in the original order.
        assertEquals(listOf(1.0, 2.0, 3.0, 4.0), TrackOps.reverse(r).flatten().map { it.lat })
    }

    @Test
    fun `split shares the cut point and refuses the ends`() {
        val (first, second) = TrackOps.split(line(5), 2)
        assertEquals(3, first.size)
        assertEquals(3, second.size)
        assertEquals(first.last(), second.first())
        assertFailsWith<IllegalArgumentException> { TrackOps.split(line(5), 0) }
        assertFailsWith<IllegalArgumentException> { TrackOps.split(line(5), 4) }
    }

    @Test
    fun `merge keeps segments apart unless the ends meet`() {
        val a = listOf(line(3))
        val far = listOf(List(3) { TrackPoint(50.0 + it * 0.001, 8.0) })
        assertEquals(2, TrackOps.merge(a, far).size)
        assertEquals(2, TrackOps.merge(a, far, joinWithinMeters = 50.0).size)

        val next = listOf(List(3) { TrackPoint(45.002 + it * 0.001, 6.0) })
        // The end of `a` is exactly the start of `next`: one segment, the shared point once.
        val joined = TrackOps.merge(a, next, joinWithinMeters = 5.0)
        assertEquals(1, joined.size)
        assertEquals(5, joined.single().size)
        // 20 m apart: joined without dropping a point.
        val close = listOf(List(2) { TrackPoint(45.0022 + it * 0.001, 6.0) })
        val j2 = TrackOps.merge(a, close, joinWithinMeters = 50.0)
        assertEquals(1, j2.size)
        assertEquals(5, j2.single().size)
        assertEquals(a, TrackOps.merge(a, emptyList()))
        assertEquals(far, TrackOps.merge(emptyList(), far))
    }

    @Test
    fun `trim keeps the range, ends included`() {
        val t = TrackOps.trim(line(10), 2, 5)
        assertEquals(listOf(1002.0, 1003.0, 1004.0, 1005.0), t.map { it.ele })
        assertFailsWith<IllegalArgumentException> { TrackOps.trim(line(3), 2, 1) }
    }

    @Test
    fun `midpoint averages what both sides have`() {
        val m =
            TrackOps.midpoint(
                TrackPoint(0.0, 0.0, ele = 100.0, timeMillis = 0),
                TrackPoint(2.0, 4.0, ele = 200.0, timeMillis = 10),
            )
        assertEquals(1.0, m.lat)
        assertEquals(2.0, m.lon)
        assertEquals(150.0, m.ele)
        assertEquals(5L, m.timeMillis)
        assertNull(TrackOps.midpoint(TrackPoint(0.0, 0.0), TrackPoint(1.0, 1.0, ele = 5.0)).ele)
    }

    @Test
    fun `adding appends, then inserts after the selected vertex`() {
        val e = PolylineEditor()
        e.add(1.0, 1.0)
        e.add(2.0, 2.0)
        e.add(3.0, 3.0)
        assertEquals(listOf(1.0, 2.0, 3.0), e.points.map { it.lat })
        assertEquals(2, e.selected)
        e.select(0)
        e.add(1.5, 1.5)
        assertEquals(listOf(1.0, 1.5, 2.0, 3.0), e.points.map { it.lat })
        assertEquals(1, e.selected)
    }

    @Test
    fun `move and delete act on the selected vertex`() {
        val e = PolylineEditor(line(4))
        assertFalse(e.moveSelected(0.0, 0.0))
        assertFalse(e.deleteSelected())
        e.select(2)
        e.moveSelected(10.0, 20.0)
        assertEquals(TrackPoint(10.0, 20.0), e.points[2])
        e.deleteSelected()
        assertEquals(3, e.points.size)
        assertEquals(2, e.selected)
        e.deleteSelected()
        e.deleteSelected()
        e.deleteSelected()
        assertTrue(e.points.isEmpty())
        assertNull(e.selected)
    }

    @Test
    fun `undo and redo walk the history and a new edit forgets the redo branch`() {
        val e = PolylineEditor()
        e.add(1.0, 1.0)
        e.add(2.0, 2.0)
        e.add(3.0, 3.0)
        assertTrue(e.undo())
        assertEquals(2, e.points.size)
        assertTrue(e.undo())
        assertTrue(e.redo())
        assertEquals(2, e.points.size)
        e.add(9.0, 9.0)
        assertFalse(e.canRedo)
        assertEquals(listOf(1.0, 2.0, 9.0), e.points.map { it.lat })
        while (e.undo()) Unit
        assertTrue(e.points.isEmpty())
        assertFalse(e.undo())
    }

    @Test
    fun `selection is not an undoable edit`() {
        val e = PolylineEditor(line(3))
        e.select(1)
        assertFalse(e.canUndo)
        e.add(1.0, 1.0)
        e.undo()
        assertEquals(3, e.points.size)
    }

    @Test
    fun `midpoint insertion selects the new vertex`() {
        val e = PolylineEditor(listOf(TrackPoint(0.0, 0.0), TrackPoint(2.0, 2.0)))
        assertFalse(e.insertMidpoint(1))
        assertTrue(e.insertMidpoint(0))
        assertEquals(3, e.points.size)
        assertEquals(1.0, e.points[1].lat)
        assertEquals(1, e.selected)
    }

    @Test
    fun `nearest finds a vertex under the finger and nothing far away`() {
        val e = PolylineEditor(line(5))
        // 0.001 degree of latitude is about 111 m.
        assertEquals(2, e.nearest(45.002, 6.0001, 30.0))
        assertNull(e.nearest(46.0, 6.0, 30.0))
        // Two candidates: the nearer one wins.
        assertEquals(3, e.nearest(45.0029, 6.0, 500.0))
    }

    @Test
    fun `freehand strokes are thinned and appended in one undoable step`() {
        // A straight 1 km line sampled every metre (1000 points) becomes a handful.
        val stroke = List(1000) { TrackPoint(45.0 + it * 0.000009, 6.0) }
        val e = PolylineEditor(listOf(TrackPoint(44.0, 6.0)))
        assertTrue(e.addStroke(stroke, toleranceMeters = 3.0))
        assertTrue(e.points.size < 10, "kept ${e.points.size}")
        assertEquals(stroke.last(), e.points.last())
        e.undo()
        assertEquals(1, e.points.size)
        assertFalse(PolylineEditor().addStroke(emptyList(), 3.0))
    }

    @Test
    fun `freehand keeps real corners`() {
        val stroke =
            List(50) { TrackPoint(45.0 + it * 0.00002, 6.0) } +
                List(50) { TrackPoint(45.00098, 6.0 + (it + 1) * 0.00002) }
        val thin = Freehand.thin(stroke, 2.0)
        assertTrue(thin.size in 3..6, "kept ${thin.size}")
        assertTrue(thin.any { Math.abs(it.lat - 45.00098) < 1e-6 && Math.abs(it.lon - 6.0) < 3e-5 }, "corner kept")
    }

    @Test
    fun `reverse, simplify, trim and split through the editor`() {
        val e = PolylineEditor(line(6))
        e.select(1)
        e.reverse()
        assertEquals(1005.0, e.points.first().ele)
        assertEquals(4, e.selected)

        e.load(line(6))
        e.select(2)
        e.trimBeforeSelected()
        assertEquals(4, e.points.size)
        assertEquals(0, e.selected)
        e.select(2)
        e.trimAfterSelected()
        assertEquals(3, e.points.size)

        e.load(line(6))
        e.select(3)
        val second = assertNotNullList(e.splitAtSelected())
        assertEquals(4, e.points.size)
        assertEquals(3, second.size)
        assertEquals(e.points.last(), second.first())
        e.select(0)
        assertNull(e.splitAtSelected())

        e.load(line(3))
        assertTrue(e.simplify(5.0), "a straight 3-point line loses its middle point")
        assertEquals(2, e.points.size)
        assertFalse(e.simplify(5.0), "nothing left to drop")
    }

    private fun <T> assertNotNullList(value: List<T>?): List<T> = value ?: throw AssertionError("expected a list")

    @Test
    fun `history is bounded by points, not steps`() {
        val e = PolylineEditor(line(100), maxHistoryPoints = 1000)
        repeat(50) { e.add(1.0 + it, 1.0) }
        var undone = 0
        while (e.undo()) undone++
        assertTrue(undone in 5..15, "undone $undone")
        // The most recent edits are always undoable even if one snapshot is bigger than the budget.
        val big = PolylineEditor(line(5000), maxHistoryPoints = 100)
        big.add(1.0, 1.0)
        assertTrue(big.undo())
        assertContentEquals(line(5000), big.points)
    }
}
