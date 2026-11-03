package fr.toolmap.core.layers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BlendRecipeTest {
    private val base = LayerConfig("a", "osm")
    private val hidden = LayerConfig("b", "ign-photo", visible = false)
    private val slopes = LayerConfig("c", "ign-slopes", opacity = 0.6f, displayMinZoom = 10, displayMaxZoom = 15)
    private val top = LayerConfig("d", "ign-maps", blend = BlendMode.MULTIPLY, contrast = 0.25f)
    private val stack = LayerStack(listOf(base, hidden, slopes, top))

    @Test
    fun aPlainLayerHasNoRecipe() {
        assertNull(BlendRecipe.of(stack, base))
        assertNull(BlendRecipe.of(stack, slopes))
        assertNull(BlendRecipe.of(stack, LayerConfig("x", "osm", tint = 0xFF0000, tintStrength = 0f)))
    }

    @Test
    fun theRecipeHoldsTheVisibleLayersUnderItAndItself() {
        val recipe = assertNotNull(BlendRecipe.of(stack, top))
        assertEquals(listOf("osm", "ign-slopes", "ign-maps"), recipe.parts.map { it.sourceId })
        assertEquals(BlendMode.MULTIPLY, recipe.parts.last().blend)
        // Below zoom 10 the slopes are not drawn, so not composed either.
        assertEquals(listOf("osm", "ign-maps"), recipe.partsAt(9).map { it.sourceId })
        assertEquals(3, recipe.partsAt(12).size)
    }

    @Test
    fun theQueryCarriesEverySettingAndReadsBack() {
        val tinted =
            LayerConfig(
                "e",
                "ign-maps",
                opacity = 0.35f,
                contrast = -0.5f,
                brightness = 0.1f,
                saturation = -1f,
                hue = 45f,
                inverted = true,
                displayMinZoom = 8,
                displayMaxZoom = 16,
                displayMinElevation = -20,
                displayMaxElevation = 1500,
                rangeLogic = RangeLogic.OR,
                blend = BlendMode.SOFT_LIGHT,
                tint = 0x3366CC,
                tintStrength = 0.8f,
            )
        val recipe = BlendRecipe(listOf(LayerConfig("", "osm"), tinted.copy(id = "")))
        val query = recipe.query()
        assertEquals("blend=osm;ign-maps,soft-light,0.35,-0.5,0.1,-1,45,1,8,16,-20,1500,or,3366cc,0.8", query)
        assertEquals(recipe, BlendRecipe.parse(query))
        // A band joined by « OU » is drawn at every zoom (whole in its range, its band elsewhere).
        assertEquals(2, recipe.partsAt(3).size)
    }

    @Test
    fun oddSourceIdsAreEscaped() {
        val recipe =
            BlendRecipe(listOf(LayerConfig("", "ma carte;été,1%"), LayerConfig("", "b", blend = BlendMode.HUE)))
        val query = recipe.query()
        assertEquals("blend=ma%20carte%3B%C3%A9t%C3%A9%2C1%25;b,hue", query)
        assertEquals(recipe, BlendRecipe.parse("alt=1500,&$query&x=1"))
    }

    @Test
    fun brokenQueriesAreRefused() {
        assertNull(BlendRecipe.parse(null))
        assertNull(BlendRecipe.parse("alt=1500,"))
        assertNull(BlendRecipe.parse("blend=osm,blurry"))
        assertNull(BlendRecipe.parse("blend=osm,,abc"))
        assertNull(BlendRecipe.parse("blend=osm,,,,,,,,huit"))
        assertNull(BlendRecipe.parse("blend=os%2"))
        assertNull(BlendRecipe.parse("blend=,multiply"))
        assertNull(BlendRecipe.parse("blend=osm,,1,,,,,,,,,,,,,,extra"))
    }
}

class BlendStyleTest {
    @Test
    fun `a blended layer gets the recipe as its source and leaves its colours to the tiles`() {
        val stack =
            LayerStack()
                .add("osm")
                .add("ign-maps", opacity = 0.7f)
                .add("gpx-like")
                .let { it.setAdjustments("l2", 0.4f, 0f, 0f).setBlend("l2", BlendMode.MULTIPLY) }
        val applied = StackToStyle.desired(stack, darkening = 0.5f) { it != "gpx-like" }
        assertEquals(listOf("l1", "l2"), applied.map { it.id })
        assertEquals("osm", applied[0].sourceId)
        val recipe = assertNotNull(BlendRecipe.parse(StyleSources.queryOf(applied[1].sourceId)))
        assertEquals("ign-maps", StyleSources.sourceOf(applied[1].sourceId))
        assertEquals(listOf("osm", "ign-maps"), recipe.parts.map { it.sourceId })
        assertEquals(0.4f, recipe.parts.last().contrast)
        assertEquals(0.7f, recipe.parts.last().opacity)
        // Opacity and colours are in the tiles; the night mode still darkens the map.
        assertEquals(LayerProps(brightnessMax = 0.5f), applied[1].props)

        // Back to Normal: a plain layer again.
        val plain = StackToStyle.desired(stack.setBlend("l2", BlendMode.NORMAL)) { true }
        assertEquals("ign-maps", plain[1].sourceId)
    }
}
