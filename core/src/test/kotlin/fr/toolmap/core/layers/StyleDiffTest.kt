package fr.toolmap.core.layers

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StyleDiffTest {
    private fun layer(
        id: String,
        source: String = "s-$id",
        opacity: Float = 1f,
    ) = AppliedLayer(id, source, LayerProps(opacity = opacity))

    @Test
    fun `identical states need no operation`() {
        val state = listOf(layer("a"), layer("b"))
        assertEquals(emptyList(), StyleDiff.compute(state, state))
    }

    @Test
    fun `an empty style gets sources then layers bottom to top`() {
        val desired = listOf(layer("a"), layer("b"))
        val ops = StyleDiff.compute(emptyList(), desired)
        assertEquals(
            listOf(
                StyleOp.AddSource("s-a"),
                StyleOp.AddSource("s-b"),
            ),
            ops.filterIsInstance<StyleOp.AddSource>().sortedBy { it.sourceId },
        )
        val adds = ops.filterIsInstance<StyleOp.AddLayer>()
        assertEquals(listOf("a", "b"), adds.map { it.layer.id })
        assertEquals(desired, StyleDiff.apply(emptyList(), ops))
    }

    @Test
    fun `removing the last layer of a source removes the source`() {
        val current = listOf(layer("a"), layer("b"))
        val ops = StyleDiff.compute(current, listOf(layer("a")))
        assertTrue(StyleOp.RemoveLayer("b") in ops)
        assertTrue(StyleOp.RemoveSource("s-b") in ops)
        assertTrue(StyleOp.RemoveSource("s-a") !in ops)
    }

    @Test
    fun `a source shared by two layers survives the removal of one of them`() {
        val current = listOf(layer("a", "shared"), layer("b", "shared"))
        val ops = StyleDiff.compute(current, listOf(layer("a", "shared")))
        assertEquals(listOf<StyleOp>(StyleOp.RemoveLayer("b")), ops)
    }

    @Test
    fun `a property change is an update, not a rebuild`() {
        val current = listOf(layer("a"), layer("b", opacity = 1f))
        val desired = listOf(layer("a"), layer("b", opacity = 0.5f))
        assertEquals(listOf<StyleOp>(StyleOp.UpdateLayer("b", desired[1].props)), StyleDiff.compute(current, desired))
    }

    @Test
    fun `a layer whose source changes is rebuilt in place`() {
        val current = listOf(layer("a", "osm"), layer("b", "pentes"))
        val desired = listOf(layer("a", "scan25"), layer("b", "pentes"))
        val ops = StyleDiff.compute(current, desired)
        assertEquals(desired, StyleDiff.apply(current, ops))
        assertTrue(StyleOp.RemoveSource("osm") in ops)
        assertTrue(StyleOp.AddSource("scan25") in ops)
    }

    @Test
    fun `swapping two layers ends in the desired order`() {
        val current = listOf(layer("a"), layer("b"), layer("c"))
        val desired = listOf(layer("c"), layer("b"), layer("a"))
        assertEquals(desired, StyleDiff.apply(current, StyleDiff.compute(current, desired)))
    }

    @Test
    fun `any transition between random stacks ends exactly in the desired state`() {
        val random = Random(20261002)
        val pool = ('a'..'h').map { it.toString() }
        repeat(500) {
            fun randomState(): List<AppliedLayer> =
                pool
                    .shuffled(random)
                    .take(random.nextInt(0, pool.size + 1))
                    .map { id -> layer(id, "s-${random.nextInt(0, 4)}", opacity = random.nextInt(0, 3) / 2f) }
            val current = randomState()
            val desired = randomState()
            val ops = StyleDiff.compute(current, desired)
            assertEquals(desired, StyleDiff.apply(current, ops), "from $current to $desired via $ops")
            // Sources: every wanted source is present after the ops, no unused one is left.
            val present = current.map { it.sourceId }.toMutableSet()
            ops.forEach {
                when (it) {
                    is StyleOp.AddSource -> present += it.sourceId
                    is StyleOp.RemoveSource -> present -= it.sourceId
                    else -> Unit
                }
            }
            assertEquals(desired.map { it.sourceId }.toSet(), present)
        }
    }

    @Test
    fun `stack to style drops unknown sources and converts brightness`() {
        val stack =
            LayerStack()
                .add("osm")
                .add("ghost")
                .add("pentes", opacity = 0.5f)
                .setAdjustments("l3", 0.2f, -0.5f, -1f)
        val known = setOf("osm", "pentes")
        val desired = StackToStyle.desired(stack) { it in known }
        assertEquals(listOf("l1", "l3"), desired.map { it.id })
        val pentes = desired.last().props
        assertEquals(0.5f, pentes.opacity)
        assertEquals(0.2f, pentes.contrast)
        assertEquals(0f, pentes.brightnessMin)
        assertEquals(0.5f, pentes.brightnessMax)
        assertEquals(-1f, pentes.saturation)
    }
}
