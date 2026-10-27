import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.baselineprofile)
}

val compileSdkLevel = libs.versions.compileSdk.get().toInt()
val minSdkLevel = libs.versions.minSdk.get().toInt()
val targetSdkLevel = libs.versions.targetSdk.get().toInt()
// The version users see, and the APK file names built from it (docs/specs/release.md §1, ADR-040, ADR-059).
// version.properties holds it with the fingerprint of the sources it was built from. A local build that makes an APK
// from different sources raises the patch number and writes both back: two different APKs never share a number.
// CI never writes: it fails when the pushed sources do not match the fingerprint (pushed without a local build).
val appVersionName: String = appVersion(rootProject.rootDir)

fun appVersion(root: File): String {
    val file = root.resolve("version.properties")
    val props = Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
    val version = props.getProperty("version")?.trim().orEmpty()
    val stored = props.getProperty("sourceHash")?.trim().orEmpty()
    require(Regex("""\d+\.\d+\.\d+""").matches(version)) { "version.properties : version invalide « $version »" }
    val current = appSourceFingerprint(root)
    if (current == stored) return version
    if (providers.environmentVariable("CI").orNull == "true") {
        throw GradleException(
            "version.properties ne correspond pas aux sources ($stored ≠ $current) : compiler l'APK en local " +
                "(./gradlew assembleDebug) pour augmenter le numéro de version, puis pousser version.properties.",
        )
    }
    // Only a build that makes an APK or a bundle takes a number; IDE syncs, tests and style checks do not.
    val makesApk =
        gradle.startParameter.taskNames.any { task ->
            listOf("assemble", "bundle", "install", "package").any {
                task.substringAfterLast(':').startsWith(it, ignoreCase = true)
            }
        }
    if (!makesApk) return version
    // An empty fingerprint means the version was set by hand (a new MINOR, ADR-040): it is kept as it is.
    val next =
        if (stored.isEmpty()) {
            version
        } else {
            version.split('.').let { (major, minor, patch) ->
                "$major.$minor.${patch.toInt() + 1}"
            }
        }
    file.writeText(
        """
        |# ToolMap version (ADR-059, docs/specs/release.md §1). Written by app/build.gradle.kts at each local APK build
        |# whose sources changed: the patch number goes up by one. For a new MINOR or MAJOR, write it here by hand
        |# (e.g. version=0.5.0) and empty sourceHash: the next build keeps it.
        |version=$next
        |sourceHash=$current
        |
        """.trimMargin(),
        Charsets.UTF_8,
    )
    val change = if (stored.isEmpty()) "version $next enregistrée" else "sources modifiées, version $version → $next"
    logger.lifecycle("ToolMap : $change")
    return next
}

// SHA-256 of everything that goes into the APK: the Gradle files and the non-test source sets of the app modules.
// Carriage returns are dropped so that a Windows copy and a Linux checkout of the same files give the same value.
fun appSourceFingerprint(root: File): String {
    val ignored = setOf(".DS_Store", "desktop.ini", "Thumbs.db", "local.properties")
    val files =
        buildList {
            listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradle/libs.versions.toml")
                .forEach { add(root.resolve(it)) }
            for (module in listOf("app", "core", "data", "p2p")) {
                val dir = root.resolve(module)
                addAll(dir.listFiles { f: File -> f.isFile }.orEmpty())
                dir.resolve("src").listFiles { f: File ->
                    f.isDirectory && !f.name.startsWith("test") &&
                        f.name != "androidTest"
                }
                    .orEmpty()
                    .forEach { set -> addAll(set.walkTopDown().filter { it.isFile }) }
            }
        }.filter { it.isFile && it.name !in ignored && it.extension !in setOf("iml", "apk", "aab", "log") }
    val digest = MessageDigest.getInstance("SHA-256")
    // The journal lines that WhatsNewTask copies into the APK (entry titles and goals); its other fields stay out.
    root.resolve("docs/JOURNAL.md").takeIf { it.isFile }?.readLines(Charsets.UTF_8)
        ?.filter { it.startsWith("## ") || it.startsWith("- **Objectif** :") }
        ?.forEach { digest.update((it.trimEnd() + "\n").toByteArray(Charsets.UTF_8)) }
    files.map { it.relativeTo(root).invariantSeparatorsPath to it }.sortedBy { it.first }.forEach { (path, f) ->
        digest.update(path.toByteArray(Charsets.UTF_8))
        digest.update(0)
        val bytes = f.readBytes()
        var kept = 0
        for (b in bytes) if (b != '\r'.code.toByte()) bytes[kept++] = b
        digest.update(bytes, 0, kept)
        digest.update(0)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
}

android {
    namespace = "fr.toolmap"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        applicationId = "fr.toolmap"
        minSdk = minSdkLevel
        targetSdk = targetSdkLevel
        // The release workflow passes the build number; a local build is version 1.
        versionCode = providers.gradleProperty("toolmap.versionCode").map(String::toInt).getOrElse(1)
        versionName = appVersionName
        // The commit an APK was built from, written in crash reports: CI builds know it, a local build says "local".
        val commit = providers.environmentVariable("GITHUB_SHA").orNull?.take(7) ?: "local"
        buildConfigField("String", "GIT_SHA", "\"$commit\"")

        // The Firebase project that coordinates the position sharing (ADR-033). Neither value is a secret (access is
        // decided by the database rules), but they are the user's own: they come from the environment or from
        // -Ptoolmap.firebase.url / -Ptoolmap.firebase.key, never from the repository. Empty = chosen in the app.
        fun firebase(
            property: String,
            variable: String,
        ) = (
            providers.gradleProperty(
                property,
            ).orNull ?: providers.environmentVariable(variable).orNull
        ).orEmpty().trim()
        buildConfigField(
            "String",
            "FIREBASE_DB_URL",
            "\"${firebase("toolmap.firebase.url", "TOOLMAP_FIREBASE_DB_URL")}\"",
        )
        buildConfigField(
            "String",
            "FIREBASE_API_KEY",
            "\"${firebase("toolmap.firebase.key", "TOOLMAP_FIREBASE_API_KEY")}\"",
        )

        // 32-bit x86 phones are gone; the other three cover phones, tablets, emulators and Chromebooks. The native
        // libraries of MapLibre and WebRTC are most of the APK, so this is the biggest size lever (ADR-027).
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // Release key: from the environment only, never from the repository (see docs/specs/release.md).
        providers.environmentVariable("TOOLMAP_KEYSTORE_FILE").orNull?.let { keystore ->
            create("release") {
                storeFile = file(keystore)
                storePassword = providers.environmentVariable("TOOLMAP_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("TOOLMAP_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("TOOLMAP_KEY_PASSWORD").orNull
            }
        }
        // Public debug key (committed on purpose, ADR-009): local builds, and CI builds without the publication key.
        // Anyone can sign with it: the APKs handed out use the publication key when there is one (ADR-047).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            // The publication key signs the debug APK too, when the environment provides it (ADR-047).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // The map timing lines in logcat (fr.toolmap.perf.PerfLog): debug and benchmark only, never a release.
            buildConfigField("boolean", "PERF_LOG", "true")
        }
        release {
            // Off unless asked (-Ptoolmap.minify=true): R8 has never run the app on a phone yet (ADR-027).
            val minify = providers.gradleProperty("toolmap.minify").map { it == "true" }.getOrElse(false)
            isMinifyEnabled = minify
            isShrinkResources = minify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned when no keystore is configured: the build still works and Play can sign an uploaded bundle.
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("boolean", "PERF_LOG", "false")
        }
        // What the measures of P15 run on (ADR-057): a release with R8, still installable over the debug APK.
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isProfileable = true
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "PERF_LOG", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Tests read shared fixtures (fixtures/wmts/*.xml) from the repository root.
            test.systemProperty("fixtures.dir", rootProject.file("fixtures").absolutePath)
            // The visual renders of the screens (SettingsRenderTest) are made on request: -Ptoolmap.renders=<folder>.
            test.systemProperty("toolmap.renders", project.findProperty("toolmap.renders")?.toString() ?: "")
        }
    }
}

// APK file names: ToolMap-<phase>-<version>.apk, the same locally and on CI (docs/specs/release.md §1).
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("ToolMap-${variant.buildType}-$appVersionName.apk")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":p2p"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.maplibre.android)
    // QR code scanning for the position sharing (ADR-021).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    // Installs the Baseline Profile (app/src/main/generated/baselineProfiles) when the APK is installed (P15.5).
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // View models that touch the tile store need a real android.database.sqlite on the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

// « Nouveautés » (P16.3): the last entries of the journal (title and objective), copied into the assets at build time.
abstract class WhatsNewTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val journal: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val lines = journal.orNull?.asFile?.takeIf { it.exists() }?.readLines(Charsets.UTF_8).orEmpty()
        val entries = mutableListOf<Pair<String, String>>()
        var title: String? = null
        var goal = ""

        fun close() {
            title?.let { entries += it to goal }
            title = null
            goal = ""
        }
        for (line in lines) {
            when {
                line.startsWith("## ") -> {
                    close()
                    title = line.removePrefix("## ").replace(Regex("""Session \d+ — """), "")
                }

                title != null && goal.isEmpty() && line.startsWith("- **Objectif** :") -> {
                    goal = line.removePrefix("- **Objectif** :").trim().take(MAX_GOAL)
                }
            }
        }
        close()
        val text =
            entries.take(MAX_ENTRIES).joinToString("\n\n") { (head, goal) ->
                if (goal.isEmpty()) head else "$head\n$goal"
            }
        val target = outputDir.get().asFile
        target.mkdirs()
        File(target, "whats-new.txt").writeText(text, Charsets.UTF_8)
    }

    private companion object {
        const val MAX_ENTRIES = 5
        const val MAX_GOAL = 400
    }
}

androidComponents {
    onVariants { variant ->
        val whatsNew =
            tasks.register<WhatsNewTask>("generate${variant.name.replaceFirstChar { it.uppercase() }}WhatsNew") {
                journal.set(rootProject.layout.projectDirectory.file("docs/JOURNAL.md"))
            }
        variant.sources.assets?.addGeneratedSourceDirectory(whatsNew, WhatsNewTask::outputDir)
    }
}

// Baseline Profile (P15.5, ADR-057): generated on the emulator by :baselineprofile, versioned in src/main/generated.
baselineProfile {
    // Only by hand: `./gradlew :app:generateBaselineProfile` (needs a device; the CI has none).
    automaticGenerationDuringBuild = false
    saveInSrc = true
    mergeIntoMain = true
    // Straight in src/main/baseline-prof.txt: every build type merges that file, the plugin only wires its own folder
    // into the release variants (the benchmark build type would not get it).
    baselineProfileOutputDir = "."
}
