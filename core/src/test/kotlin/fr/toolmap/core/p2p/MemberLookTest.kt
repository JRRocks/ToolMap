package fr.toolmap.core.p2p

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemberLookTest {
    @Test
    fun `icon and colour come back from the packed number`() {
        for (icon in MemberLook.ICONS.indices) {
            val look = MemberLook.pack(icon, 0xFF3366CC.toInt())
            assertEquals(icon, MemberLook.icon(look))
            assertEquals(0xFF3366CC.toInt(), MemberLook.argb(look))
        }
    }

    @Test
    fun `an ARGB colour of version 0_4_0 or an unknown icon means the initials`() {
        assertEquals(MemberLook.INITIALS, MemberLook.icon(0xFFD32F2F.toInt()))
        assertEquals(0xFFD32F2F.toInt(), MemberLook.argb(0xFFD32F2F.toInt()))
        assertEquals(MemberLook.INITIALS, MemberLook.icon(MemberLook.pack(200, 0)))
        assertEquals(MemberLook.INITIALS, MemberLook.icon(MemberLook.pack(-1, 0)))
    }

    @Test
    fun `the map image is shared by icon and colour, and per name for initials`() {
        val star = MemberLook.ICONS.indexOf("ghost")
        val a = MemberLook.imageKey(MemberLook.pack(star, 0xFF00FF00.toInt()), "Anne")
        assertEquals(a, MemberLook.imageKey(MemberLook.pack(star, 0xFF00FF00.toInt()), "Bruno"))
        assertNotEquals(a, MemberLook.imageKey(MemberLook.pack(star, 0xFF0000FF.toInt()), "Anne"))
        val initials = MemberLook.pack(MemberLook.INITIALS, 0xFF00FF00.toInt())
        assertNotEquals(MemberLook.imageKey(initials, "Anne"), MemberLook.imageKey(initials, "Bruno"))
        assertEquals("tm-m-00ff00-ghost", a)
    }

    @Test
    fun `at most 15 tokens, all different, and the picture has its own id`() {
        assertTrue(MemberLook.ICONS.size <= 15)
        assertEquals("initials", MemberLook.ICONS[MemberLook.INITIALS])
        assertEquals(MemberLook.ICONS.size, MemberLook.ICONS.toSet().size)
        assertTrue(MemberLook.PICTURE !in MemberLook.ICONS.indices)
        assertEquals(MemberLook.PICTURE, MemberLook.icon(MemberLook.pack(MemberLook.PICTURE, 0)))
    }

    @Test
    fun `a picture names its image by content, and stands in with initials until it arrives`() {
        val look = MemberLook.pack(MemberLook.PICTURE, 0xFF00FF00.toInt())
        val one = MemberLook.imageKey(look, "Anne", byteArrayOf(1, 2, 3))
        assertTrue(one.startsWith("tm-p-"))
        assertNotEquals(one, MemberLook.imageKey(look, "Anne", byteArrayOf(1, 2, 4)))
        assertEquals("tm-m-00ff00-AN", MemberLook.imageKey(look, "Anne"))
        // A picture sent with another icon chosen is ignored.
        val ghost = MemberLook.ICONS.indexOf("ghost")
        assertEquals(
            "tm-m-00ff00-ghost",
            MemberLook.imageKey(MemberLook.pack(ghost, 0xFF00FF00.toInt()), "A", byteArrayOf(1)),
        )
    }

    @Test
    fun `a silhouette sits on white when dark and on near-black when light`() {
        assertEquals(0xFFFFFFFF.toInt(), MemberLook.backdrop(0xFF1976D2.toInt()))
        assertEquals(0xFF202124.toInt(), MemberLook.backdrop(0xFFFFEB3B.toInt()))
    }

    @Test
    fun `ink is white on dark colours and black on light ones`() {
        assertTrue(MemberLook.isDark(0xFF1976D2.toInt()))
        assertFalse(MemberLook.isDark(0xFFFFEB3B.toInt()))
    }

    @Test
    fun `HSV conversions round trip the palette`() {
        for (c in MemberPalette.colors + intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF808080.toInt())) {
            val hsv = ColorWheel.toHsv(c)
            assertEquals(c, ColorWheel.fromHsv(hsv[0], hsv[1], hsv[2]), Integer.toHexString(c))
        }
        assertEquals(0xFFFF0000.toInt(), ColorWheel.fromHsv(0f, 1f, 1f))
        assertEquals(0xFF00FF00.toInt(), ColorWheel.fromHsv(120f, 1f, 1f))
        assertEquals(0xFF0000FF.toInt(), ColorWheel.fromHsv(240f, 1f, 1f))
        assertEquals(0xFFFF0000.toInt(), ColorWheel.fromHsv(360f, 1f, 1f))
    }

    @Test
    fun `a touch on the wheel gives hue and saturation, and back`() {
        val r = 100f
        assertEquals(0f to 0f, ColorWheel.hueSatAt(0f, 0f, r).let { 0f to it.second })
        val (h, s) = ColorWheel.hueSatAt(0f, 50f, r)
        assertEquals(90f, h, 0.01f)
        assertEquals(0.5f, s, 0.001f)
        assertEquals(1f, ColorWheel.hueSatAt(-300f, 0f, r).second)
        for (hue in listOf(0f, 45f, 200f, 359f)) {
            val (x, y) = ColorWheel.pointOf(hue, 0.7f, r)
            val (h2, s2) = ColorWheel.hueSatAt(x, y, r)
            assertTrue(abs(h2 - hue) < 0.01f, "$hue → $h2")
            assertEquals(0.7f, s2, 0.001f)
        }
    }
}
