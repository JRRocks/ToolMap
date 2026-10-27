import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android library of the debug builds only (ADR-070, P21): the tools of the live tests of the position sharing. Out of
// the build since P21 was closed (ADR-072, 2026-10-24): settings.gradle.kts no longer includes it and no APK has it.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val compileSdkLevel =
    libs.versions.compileSdk
        .get()
        .toInt()
val minSdkLevel =
    libs.versions.minSdk
        .get()
        .toInt()

android {
    namespace = "fr.toolmap.debugkit"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        minSdk = minSdkLevel
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
