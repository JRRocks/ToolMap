package fr.toolmap.share

import fr.toolmap.core.p2p.MemberLook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MemberIconsTest {
    @Test
    fun `every token of the wire has its silhouette, in the same order`() {
        assertEquals(MemberLook.ICONS.size, MEMBER_ICON_RES.size)
        assertNull(memberIconRes(MemberLook.INITIALS))
        assertNull(memberIconRes(MemberLook.PICTURE))
        for (id in 1 until MemberLook.ICONS.size) assertNotNull(memberIconRes(id), MemberLook.ICONS[id])
    }

    @Test
    fun `the drawables are named after the token keys`() {
        val fields = fr.toolmap.R.drawable::class.java.fields.associate { it.getInt(null) to it.name }
        for (id in 1 until MemberLook.ICONS.size) {
            assertEquals("ic_token_${MemberLook.ICONS[id]}", fields[MEMBER_ICON_RES[id]])
        }
    }
}
