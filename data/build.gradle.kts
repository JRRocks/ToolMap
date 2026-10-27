import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Android library: Room database, MBTiles tile store, OkHttp tile interceptor, downloads (phases P1, P2, P5).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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
    namespace = "fr.toolmap.data"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        minSdk = minSdkLevel
    }

    // The interface of the BRouter app's routing service (P19.1, ADR-064).
    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Tests read shared fixtures (fixtures/aqx/*.aqx) from the repository root.
            test.systemProperty("fixtures.dir", rootProject.file("fixtures").absolutePath)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    // Versioned schemas let later migrations be tested against the real previous versions.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core"))
    // Tracks, routes, waypoints and folders (P5). Exposed because the DAOs and entities are public API of :data.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Exposed in the public API (Interceptor, OkHttpClient factories, StateFlow).
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Real android.database.sqlite on the JVM, for the MBTiles store.
    testImplementation(libs.robolectric)
}
