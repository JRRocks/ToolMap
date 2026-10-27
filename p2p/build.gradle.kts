import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android library: WebRTC data-channel links, QR codes and the share state (phase P8). The protocol itself
// (pairing codec, messages, mesh, encryption) is pure Kotlin in :core.
plugins {
    alias(libs.plugins.android.library)
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
    namespace = "fr.toolmap.p2p"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        minSdk = minSdkLevel
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    // libwebrtc without audio or video use: DataChannel only (the native libraries are the cost, see ADR-021).
    implementation(libs.webrtc)
    // QR encoding and decoding, no Google Play Services.
    api(libs.zxing.core)
    api(libs.kotlinx.coroutines.core)
    // Firebase is spoken over plain HTTPS (REST + server-sent events): no Firebase SDK, no Google Play Services (ADR-033).
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
