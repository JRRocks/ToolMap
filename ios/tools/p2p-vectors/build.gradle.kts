// Writes ios/ToolMapCore/Tests/ToolMapCoreTests/P2PVectors.swift: frames, invitations, JSON and journal lines made by
// the Kotlin code of Android (core/src/main/kotlin), that the Swift port must read and write bit for bit (P32.10).
// Run from this folder with the Gradle of the Android project:
//   ../../../gradlew -p . -q run --args="$PWD/../../ToolMapCore/Tests/ToolMapCoreTests/P2PVectors.swift"
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.google.zxing:core:3.5.4")
}

application {
    mainClass.set("VectorsKt")
}

// The Android sources the vectors need, copied as they are (the p2p package alone, with what it reads).
val androidSources by tasks.registering(Sync::class) {
    from("../../../core/src/main/kotlin") {
        include("fr/toolmap/core/p2p/*.kt")
        include("fr/toolmap/core/eventlog/EventLog.kt", "fr/toolmap/core/eventlog/EventStore.kt")
        include("fr/toolmap/core/xml/JsonText.kt", "fr/toolmap/core/track/Geodesy.kt")
    }
    into(layout.buildDirectory.dir("android-src"))
}

kotlin {
    sourceSets.main {
        kotlin.srcDir(androidSources)
    }
}
