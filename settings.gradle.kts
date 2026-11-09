pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ToolMap"

include(":core")
// The native compute engine (Rust, ADR-119) and its JNI door.
include(":engine")
include(":data")
include(":p2p")
// The live-test tools (`debugkit/`, ADR-070) are out of the build since P21 was closed (ADR-072): to bring them back,
// include(":debugkit") here and debugImplementation(project(":debugkit")) in app/build.gradle.kts.
include(":app")
include(":baselineprofile")
