// Macrobenchmark module that generates the Baseline Profile of the app (P15.5, ADR-057). It runs on a connected device
// (the emulator), never on the CI: `./gradlew :app:generateBaselineProfile` rewrites app/src/main/generated/baselineProfiles.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

val compileSdkLevel =
    libs.versions.compileSdk
        .get()
        .toInt()
val targetSdkLevel =
    libs.versions.targetSdk
        .get()
        .toInt()

android {
    namespace = "fr.toolmap.baselineprofile"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        // Profile collection needs Android 9 (API 28) at least; the app itself keeps minSdk 26.
        minSdk = 28
        targetSdk = targetSdkLevel
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // The emulator (or phone) that adb sees; no Gradle managed device.
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    onVariants { variant ->
        val artifactsLoader = variant.artifacts.getBuiltArtifactsLoader()
        variant.instrumentationRunnerArguments.put(
            "targetAppId",
            variant.testedApks.map { artifactsLoader.load(it)?.applicationId.orEmpty() },
        )
    }
}
