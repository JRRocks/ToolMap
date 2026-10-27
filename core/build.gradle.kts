import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: geo maths, formats (AQX, GPX...), P2P codec. No Android dependency,
// so everything here is unit-tested on the JVM (fast, works without an emulator).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // JSON for persisted app state (layer stack, camera) and later GeoJSON-like documents.
    api(libs.kotlinx.serialization.json)

    // Only to check in tests that a pairing code fits the QR versions a phone screen can show.
    testImplementation(libs.zxing.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    // Tests read shared fixtures (fixtures/aqx/*.aqx) from the repository root.
    val fixturesDir = rootProject.file("fixtures")
    systemProperty("fixtures.dir", fixturesDir.absolutePath)
    inputs.dir(fixturesDir).withPropertyName("fixtures")
    // RegexPortabilityTest reads the regular expressions of every module's main sources.
    val mainSources = rootProject.subprojects.map { it.projectDir.resolve("src/main") }
    systemProperty("main.sources", mainSources.joinToString(File.pathSeparator) { it.absolutePath })
    inputs
        .files(mainSources.map { dir -> fileTree(dir) { include("**/*.kt") } })
        .withPropertyName("mainSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
