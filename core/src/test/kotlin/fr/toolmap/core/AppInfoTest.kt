package fr.toolmap.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppInfoTest {
    @Test
    fun `app name is ToolMap`() {
        assertEquals("ToolMap", AppInfo.NAME)
    }

    @Test
    fun `tagline is not blank`() {
        assertTrue(AppInfo.TAGLINE.isNotBlank())
    }
}
