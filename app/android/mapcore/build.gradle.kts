import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipFile
import javax.inject.Inject

// :mapcore, the map's core (plan-vector T3, T4, D19): the C++ engine in cpp/, built by CMake into libatlasmap.so for
// arm64-v8a and x86_64, and its Kotlin façade. The toolchain is pinned in tools.properties, which CMake and CI read
// too: the NDK, CMake, Slang (fetched here, checked by SHA-256), the Android validation layer (packaged into debug
// builds) and, for the MODS probe only, Kitware's CMake 4.2.x.
plugins {
    alias(libs.plugins.android.library)
}

val pins = Properties().apply { load(providers.fileContents(layout.projectDirectory.file("tools.properties")).asText.get().reader()) }
fun pin(key: String): String = pins.getProperty(key) ?: error("tools.properties has no $key")

// The tools cache (git-ignored), shared by every checkout's builds: -Patlas.tools=<dir> moves it.
val repoDir: File = rootProject.layout.projectDirectory.asFile.resolve("../..").canonicalFile
val toolsDir: File = providers.gradleProperty("atlas.tools").map { File(it) }.getOrElse(repoDir.resolve("cache/atlas-tools"))
val hostPlatform: String = run {
    val os = providers.systemProperty("os.name").get().lowercase()
    val arch = providers.systemProperty("os.arch").get().lowercase()
    val osName = when {
        os.contains("mac") -> "macos"
        os.contains("linux") -> "linux"
        else -> error("The core's tools are pinned for macOS and Linux hosts, not $os")
    }
    "$osName-${if (arch == "aarch64" || arch == "arm64") "aarch64" else "x86_64"}"
}

/** Downloads a pinned release archive once, checks its SHA-256, and unpacks it: a tar.gz with tar, a zip in Java. */
abstract class FetchTool @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    /** For a zip: the entries to unpack, as "entry=relative/path/in/destination". For a tar.gz: patterns to leave out. */
    @get:Input abstract val entries: ListProperty<String>
    @get:Internal abstract val downloads: DirectoryProperty
    @get:OutputDirectory abstract val destination: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = destination.get().asFile
        val stamp = out.resolve(".atlas-sha256")
        if (stamp.isFile && stamp.readText().trim() == sha256.get()) return // unpacked already, by any build
        val archive = downloads.get().asFile.resolve(url.get().substringAfterLast('/'))
        if (!archive.isFile || sha256Of(archive) != sha256.get()) {
            archive.parentFile.mkdirs()
            val part = File(archive.path + ".part")
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
            val response = client.send(HttpRequest.newBuilder(URI(url.get())).build(), HttpResponse.BodyHandlers.ofFile(part.toPath()))
            check(response.statusCode() == 200) { "${url.get()}: HTTP ${response.statusCode()}" }
            val actual = sha256Of(part)
            check(actual == sha256.get()) { "${url.get()}: SHA-256 $actual, but tools.properties pins ${sha256.get()}" }
            Files.move(part.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // Unpacked beside the destination, then moved into place, so builds running at once never see half a tool.
        val staging = Files.createTempDirectory(out.parentFile.apply { mkdirs() }.toPath(), ".${out.name}-").toFile()
        if (archive.name.endsWith(".zip")) {
            ZipFile(archive).use { zip ->
                for (spec in entries.get()) {
                    val (name, target) = spec.split('=', limit = 2)
                    val entry = zip.getEntry(name) ?: error("${archive.name} has no $name")
                    val file = staging.resolve(target).apply { parentFile.mkdirs() }
                    zip.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
                }
            }
        } else {
            exec.exec { commandLine(listOf("tar", "-xzf", archive.path, "-C", staging.path) + entries.get().map { "--exclude=$it" }) }
        }
        staging.resolve(".atlas-sha256").writeText(sha256.get() + "\n")
        // createTempDirectory makes the folder private to its owner; the unpacked tool is as readable as its archive.
        staging.setReadable(true, false)
        staging.setExecutable(true, false)
        out.deleteRecursively()
        Files.move(staging.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val slangVersion = pin("slang.version")
val slangDir = toolsDir.resolve("slang-$slangVersion-$hostPlatform")
val fetchSlang = tasks.register<FetchTool>("fetchSlang") {
    description = "Fetches slangc $slangVersion for this host (tools.properties)."
    val file = pin("slang.$hostPlatform.file")
    url = "https://github.com/shader-slang/slang/releases/download/v$slangVersion/$file"
    sha256 = pin("slang.$hostPlatform.sha256")
    // The LLVM CPU back end, the docs and the optional modules are not needed to compile shaders to SPIR-V.
    entries = listOf("*slang-llvm*", "*share/doc*", "*standard-module*", "*libgfx*", "*gfx.slang*", "*debug-info*")
    downloads = toolsDir.resolve("downloads")
    destination = slangDir
}

val vvlDir = toolsDir.resolve("vvl-android-${pin("vvl.version")}")
val fetchValidationLayer = tasks.register<FetchTool>("fetchValidationLayer") {
    description = "Fetches the Khronos validation layer's Android build (tools.properties) for debug builds."
    url = pin("vvl.android.url")
    sha256 = pin("vvl.android.sha256")
    val root = pin("vvl.android.file").removeSuffix(".zip")
    entries = listOf("arm64-v8a", "x86_64").map { abi -> "$root/$abi/libVkLayer_khronos_validation.so=$abi/libVkLayer_khronos_validation.so" }
    downloads = toolsDir.resolve("downloads")
    destination = vvlDir
}

// Kitware's CMake 4.2.x, for the MODS probe of `import std` and `import vulkan` (plan-vector T3); not part of a build.
tasks.register<FetchTool>("fetchModsCmake") {
    description = "Fetches Kitware's CMake ${pin("mods.cmake.version")} for the MODS probe."
    val platform = if (hostPlatform.startsWith("macos")) "macos-universal" else hostPlatform
    val file = pin("mods.cmake.$platform.file")
    url = "https://github.com/Kitware/CMake/releases/download/v${pin("mods.cmake.version")}/$file"
    sha256 = pin("mods.cmake.$platform.sha256")
    entries = listOf("*/doc/*", "*/man/*")
    downloads = toolsDir.resolve("downloads")
    destination = toolsDir.resolve("cmake-${pin("mods.cmake.version")}-$platform")
}

// The validation layer goes into debug builds (on the emulator CI enables it; on a phone on request): about 28 MB per
// ABI. -Patlas.vvl=false leaves it out.
val packageValidationLayer = providers.gradleProperty("atlas.vvl").map { it != "false" }.getOrElse(true)
// ccache on CI (android.yml sets ATLAS_CCACHE=1), keyed there by the NDK and the flags.
val ccache = providers.environmentVariable("ATLAS_CCACHE").map { it == "1" }.getOrElse(false)

android {
    namespace = "app.atlas.mapcore"
    compileSdk = 37
    ndkVersion = pin("ndk.version")

    defaultConfig {
        minSdk = 29
        externalNativeBuild {
            cmake {
                targets += "atlasmap"
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DATLAS_TOOLS_DIR=${toolsDir.path}",
                    "-DATLAS_SLANGC=${slangDir.resolve("bin/slangc").path}",
                )
                if (ccache) arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("cpp/CMakeLists.txt")
            version = pin("cmake.version")
            // Inside the build folder (build-builders/<name>/mapcore/cxx for a builder), not mapcore/.cxx.
            buildStagingDirectory = layout.buildDirectory.dir("cxx").get().asFile
        }
    }

    buildTypes {
        // Debug: -O1 -g, assertions, UBSan's minimal runtime trapping; the validation layer packaged.
        debug {
            externalNativeBuild { cmake { arguments += listOf("-DATLAS_BUILD=debug", "-DATLAS_SANITIZE=ubsan-minimal") } }
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        // Release: -O2, ThinLTO, hidden symbols, sections collected; arm64-v8a only. AGP strips the library in the APK;
        // the unstripped one stays in the build folder (merged_native_libs) for ndk-stack. CI does not keep it yet:
        // release.yml and android.yml upload only the APKs.
        release {
            externalNativeBuild { cmake { arguments += "-DATLAS_BUILD=release" } }
            ndk { abiFilters += "arm64-v8a" }
        }
        // Bench: release code with symbols, for simpleperf and the bench APK; arm64-v8a and the emulator's x86_64.
        create("bench") {
            initWith(getByName("release"))
            externalNativeBuild { cmake { arguments.clear(); arguments += "-DATLAS_BUILD=bench" } }
            ndk { abiFilters.clear(); abiFilters += listOf("arm64-v8a", "x86_64") }
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (packageValidationLayer) variant.sources.jniLibs?.addGeneratedSourceDirectory(fetchValidationLayer, FetchTool::destination)
    }
}

// CMake configures with slangc in place (it checks its version against tools.properties).
tasks.matching { it.name.startsWith("configureCMake") }.configureEach { dependsOn(fetchSlang) }

dependencies {
    testImplementation(libs.junit)
}
