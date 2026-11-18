// Top-level build file: plugins are declared here (apply false) so every module shares one version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}

// Code style: ktlint (rules in .editorconfig). `./gradlew spotlessCheck` verifies, `spotlessApply` fixes.
// Spotless wires spotlessCheck into `check`.
spotless {
    kotlin {
        target("*/src/**/*.kt")
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(mapOf("ktlint_standard_chain-method-continuation" to "disabled"))
    }
}

// A test task that hangs fails after a time limit instead of holding the build until the CI job is cut (P39.15):
// 20 min, far above the longest task (the Robolectric screens of :app); 4 h for the long series of the « Opération »
// mode (-Ptoolmap.hierarchy.soak, -Ptoolmap.hierarchy.fuzz); -Ptoolmap.test.timeout.minutes=<n> to choose.
val testTimeoutMin = 20L
val longSeriesTimeoutMin = 240L
val longSeries =
    listOf("toolmap.hierarchy.soak", "toolmap.hierarchy.fuzz").any {
        !providers.gradleProperty(it).orNull.isNullOrEmpty()
    }
val testTimeout =
    providers.gradleProperty("toolmap.test.timeout.minutes").orNull?.toLong()
        ?: if (longSeries) longSeriesTimeoutMin else testTimeoutMin

subprojects {
    tasks.withType<Test>().configureEach {
        timeout.set(java.time.Duration.ofMinutes(testTimeout))
    }
}
