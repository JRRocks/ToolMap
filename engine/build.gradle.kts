import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

// Android library: the native compute engine (Rust, ADR-119, docs/specs/native-engine.md) and the Kotlin side of its
// JNI door. The crate of src/main/rust is built by cargo for the three Android architectures of the APK, with the
// linker of the NDK, and for the build machine itself so that the JVM tests compare it with the Kotlin code of :core.
//
// Rust and the NDK are optional on a developer's machine: without them (or with -Ptoolmap.engine=off) the APK has no
// native library and the Kotlin code does all the work, as before. They are required on CI
// (-Ptoolmap.engine.required=true, or CI=true), so that an APK handed out always has the library.
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

/** The NDK whose linker builds the library (keep in sync with scripts/install-android-sdk.sh). */
val pinnedNdk = "28.2.13676358"

/** Android ABI → Rust target, the architectures of the APK (app/build.gradle.kts, abiFilters). */
val androidTargets =
    mapOf(
        "arm64-v8a" to "aarch64-linux-android",
        "armeabi-v7a" to "armv7-linux-androideabi",
        "x86_64" to "x86_64-linux-android",
    )

fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("windows")

/** `cargo` on the PATH or where rustup installs it; `null` when Rust is not installed. */
fun findCargo(): String? {
    val name = if (isWindows()) "cargo.exe" else "cargo"
    val folders =
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map(::File) +
            File(System.getProperty("user.home"), ".cargo/bin")
    return folders.map { File(it, name) }.firstOrNull { it.isFile }?.absolutePath
}

/** The NDK: the pinned version of the SDK, else the one the environment names, else the newest installed. */
fun findNdk(): String? {
    providers.gradleProperty("toolmap.ndk.dir").orNull?.let { return it }
    val sdk =
        rootProject.file("local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.trim()
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
    val installed = sdk?.let { File(it, "ndk") }?.listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }
    installed.firstOrNull { it.name == pinnedNdk }?.let { return it.absolutePath }
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK_LATEST_HOME")
        .mapNotNull { System.getenv(it) }
        .firstOrNull { File(it).isDirectory }
        ?.let { return it }
    return installed.lastOrNull()?.absolutePath
}

/** `-Ptoolmap.engine=off` builds as a machine without Rust does: no native library, the Kotlin code does the work. */
val engineOff: Boolean = providers.gradleProperty("toolmap.engine").orNull == "off"
val cargoPath: String? = if (engineOff) null else findCargo()
val ndkPath: String? = findNdk()
val engineRequired: Boolean =
    providers.gradleProperty("toolmap.engine.required").map { it == "true" }.orNull
        ?: (providers.environmentVariable("CI").orNull == "true")

/** True when this build puts the native library in the APK: the app then expects to find it (NativeEngine). */
val buildsNativeLibrary: Boolean = engineRequired || (cargoPath != null && ndkPath != null)
if (!buildsNativeLibrary) {
    val missing =
        when {
            engineOff -> "-Ptoolmap.engine=off"
            cargoPath == null -> "Rust introuvable"
            else -> "NDK introuvable"
        }
    logger.lifecycle(
        "ToolMap : moteur natif absent de ce build ($missing) : les calculs restent en Kotlin. " +
            "Voir docs/specs/native-engine.md § 6.",
    )
}

android {
    namespace = "fr.toolmap.engine"
    compileSdk { version = release(compileSdkLevel) }

    defaultConfig {
        minSdk = minSdkLevel
        buildConfigField("boolean", "NATIVE_LIBRARY", "$buildsNativeLibrary")
    }

    buildFeatures {
        buildConfig = true
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
    // The Kotlin code the engine stands for, and the door it is installed at (fr.toolmap.core.compute).
    api(project(":core"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

/**
 * Builds the crate for each of [targets] (output folder → Rust target, an empty target being the build machine) and
 * copies the libraries into [outputDir]. Without the tools ([cargo], the NDK) it leaves the folder empty and says so,
 * unless the library is [required].
 */
@DisableCachingByDefault(because = "cargo keeps its own incremental build; only its small result is copied")
abstract class CargoBuild
    @Inject
    constructor(
        private val exec: ExecOperations,
    ) : DefaultTask() {
        @get:InputDirectory
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val crate: DirectoryProperty

        @get:Input
        abstract val targets: MapProperty<String, String>

        @get:Input
        abstract val apiLevel: Property<Int>

        @get:Input
        abstract val required: Property<Boolean>

        @get:Input
        @get:Optional
        abstract val cargo: Property<String>

        @get:Input
        @get:Optional
        abstract val ndk: Property<String>

        /** cargo's own build folder: its state, not a result of this task. */
        @get:Internal
        abstract val cargoTargetDir: DirectoryProperty

        @get:OutputDirectory
        abstract val outputDir: DirectoryProperty

        @TaskAction
        fun build() {
            val out = outputDir.get().asFile
            out.deleteRecursively()
            out.mkdirs()
            val cargoBin =
                cargo.orNull
                    ?: return missing(
                        "Rust (cargo) est introuvable (https://rustup.rs) ou coupé (-Ptoolmap.engine=off)",
                    )
            val targetDir = cargoTargetDir.get().asFile
            for ((folder, triple) in targets.get()) {
                val environment = mutableMapOf("CARGO_TARGET_DIR" to targetDir.absolutePath)
                val command = mutableListOf(cargoBin, "build", "--release", "--locked")
                if (triple.isNotEmpty()) {
                    val ndkDir = ndk.orNull ?: return missing("le NDK Android est introuvable (sdkmanager \"ndk;…\")")
                    val linker =
                        linkerOf(File(ndkDir), triple)
                            ?: return missing("le NDK $ndkDir n'a pas d'éditeur de liens pour $triple")
                    val key = triple.uppercase().replace('-', '_')
                    environment["CARGO_TARGET_${key}_LINKER"] = linker.absolutePath
                    // 16 KB memory pages (Android 15 and later): the segments of the library are aligned for them.
                    environment["CARGO_TARGET_${key}_RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                    command += listOf("--target", triple)
                }
                exec.exec {
                    workingDir = crate.get().asFile
                    environment(environment)
                    commandLine(command)
                }
                val folderOfCargo = if (triple.isEmpty()) "release" else "$triple/release"
                val built = File(targetDir, folderOfCargo).resolve(libraryName(triple))
                if (!built.isFile) throw GradleException("cargo n'a pas produit ${built.absolutePath}")
                built.copyTo(File(out, folder).resolve(built.name), overwrite = true)
            }
        }

        /** The clang of the NDK that links for [triple] at the app's lowest Android version. */
        private fun linkerOf(
            ndkDir: File,
            triple: String,
        ): File? {
            val os = System.getProperty("os.name").lowercase()
            val host =
                when {
                    os.contains("windows") -> "windows-x86_64"
                    os.contains("mac") -> "darwin-x86_64"
                    else -> "linux-x86_64"
                }
            // The NDK names the 32-bit ARM tools « armv7a », Rust « armv7 ».
            val prefix = triple.replace("armv7-", "armv7a-")
            val suffix = if (os.contains("windows")) ".cmd" else ""
            val tools = File(ndkDir, "toolchains/llvm/prebuilt/$host/bin")
            return File(tools, "$prefix${apiLevel.get()}-clang$suffix").takeIf { it.isFile }
        }

        private fun libraryName(triple: String): String {
            val os = System.getProperty("os.name").lowercase()
            return when {
                triple.isNotEmpty() -> "libtoolmap_engine.so"
                os.contains("windows") -> "toolmap_engine.dll"
                os.contains("mac") -> "libtoolmap_engine.dylib"
                else -> "libtoolmap_engine.so"
            }
        }

        private fun missing(reason: String) {
            val message = "Moteur natif (Rust) non compilé : $reason. Voir docs/specs/native-engine.md § 6."
            if (required.get()) throw GradleException(message)
            logger.warn("ToolMap : $message Les calculs restent en Kotlin dans ce build.")
        }
    }

fun CargoBuild.common() {
    group = "build"
    crate.set(layout.projectDirectory.dir("src/main/rust"))
    apiLevel.set(minSdkLevel)
    required.set(engineRequired)
    cargoPath?.let { cargo.set(it) }
    ndkPath?.let { ndk.set(it) }
    cargoTargetDir.set(layout.buildDirectory.dir("rust"))
}

// The libraries of the APK: one task per variant, each given its own folder by the Android plugin (cargo compiles
// once, in build/rust, whatever the number of variants built).
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val task =
            tasks.register<CargoBuild>("cargoBuild${name}JniLibs") {
                description = "Builds the native engine for the Android architectures of the $name APK."
                common()
                targets.set(androidTargets)
            }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(task, CargoBuild::outputDir)
    }
}

// The library of the build machine, for the JVM tests (EngineParityTest): the same Rust code, called through JNI.
val cargoBuildHost =
    tasks.register<CargoBuild>("cargoBuildHost") {
        description = "Builds the native engine for this machine, for the JVM tests."
        common()
        targets.set(mapOf("host" to ""))
        outputDir.set(layout.buildDirectory.dir("rust-host"))
    }

tasks.withType<Test>().configureEach {
    val hostDir = cargoBuildHost.flatMap { it.outputDir }
    inputs.dir(hostDir).withPropertyName("nativeEngine").withPathSensitivity(PathSensitivity.RELATIVE)
    val required = engineRequired
    doFirst {
        systemProperty("toolmap.engine.dir", hostDir.get().asFile.resolve("host").absolutePath)
        systemProperty("toolmap.engine.required", required.toString())
    }
}

// The tests of the crate itself (`cargo test`), part of `check` wherever Rust is installed.
val cargoTest =
    tasks.register<Exec>("cargoTest") {
        group = "verification"
        description = "Runs the tests of the Rust crate."
        val crateDir = layout.projectDirectory.dir("src/main/rust")
        inputs.dir(crateDir).withPropertyName("crate").withPathSensitivity(PathSensitivity.RELATIVE)
        val stamp = layout.buildDirectory.file("rust-test.stamp")
        outputs.file(stamp)
        workingDir = crateDir.asFile
        environment("CARGO_TARGET_DIR", layout.buildDirectory.dir("rust").get().asFile.absolutePath)
        commandLine(cargoPath ?: "cargo", "test", "--locked")
        val hasCargo = cargoPath != null
        val required = engineRequired
        onlyIf("Rust is installed, or the engine is required") { hasCargo || required }
        doLast { stamp.get().asFile.writeText("ok\n") }
    }

tasks.named("check") { dependsOn(cargoTest) }
