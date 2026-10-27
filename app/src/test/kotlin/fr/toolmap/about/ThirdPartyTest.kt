package fr.toolmap.about

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThirdPartyTest {
    /** Libraries of the catalog that never reach the APK: tests and annotation processors. */
    private val notShipped =
        setOf(
            "junit:junit",
            "org.robolectric:robolectric",
            "org.jetbrains.kotlin:kotlin-test-junit",
            "com.squareup.okhttp3:mockwebserver",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test",
            "androidx.room:room-compiler",
            "com.google.dagger:hilt-compiler",
            "androidx.compose.ui:ui-tooling",
        )

    private fun catalogModules(): List<String> {
        val root = File(requireNotNull(System.getProperty("fixtures.dir"))).parentFile
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        return Regex("""module\s*=\s*"([^"]+)"""").findAll(catalog).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `every library of the catalog that is shipped has a licence entry`() {
        val modules = catalogModules()
        assertTrue(modules.size > 20, "the catalog was not read: $modules")
        val missing =
            modules
                .filter { it !in notShipped }
                .filter { module -> THIRD_PARTY.none { entry -> entry.modules.any { module.startsWith(it) } } }
        assertEquals(emptyList(), missing, "libraries without a licence in ThirdParty.kt")
    }

    @Test
    fun `entries are unique and filled in`() {
        assertEquals(THIRD_PARTY.size, THIRD_PARTY.map { it.name }.toSet().size)
        assertTrue(THIRD_PARTY.all { it.license.isNotBlank() && it.modules.isNotEmpty() })
        assertTrue(DATA_CREDITS.all { (name, credit) -> name.isNotBlank() && credit.isNotBlank() })
    }
}
