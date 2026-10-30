import java.io.File
import java.util.Properties
import javax.inject.Inject

// :mapcorecheck (plan-vector D19, T10): tests only, on the JVM, against the core's host build loaded through JNI.
// MT/tiles holds the tile reader to the Kotlin writer (L0 (b), K3), MT/style the style's layouts and light (K5),
// MT/likeness the likeness harness's candidates and gates (K4), MT/numerics pymath to StrictMath and PyMath (L0 (d),
// K3b; -Datlas.numerics.n arguments a function, 10^7 when not given), MT/facade the façade's host entry points (K1).
// -Datlas.l0.damages sets how many damaged tiles the tile reader's differential check decodes (1,500 when not given).
//
// The host build is the core's CMake project (mapcore/cpp) compiled for this machine without sanitizers (the JVM loads
// it) into libatlasmap_host, with the test entry points (jni/Test*.cpp); the tests find it through -Datlas.mapcore.host.
// It is a Debug build unless -Patlas.mapcore.buildType names another (Release, or Bench for RelWithDebInfo: the core's
// release and bench kinds, which pymath's timing test needs); another build type configures it again.
// The compiler is clang: ATLAS_HOST_CC, ATLAS_HOST_CXX, ATLAS_HOST_CXXFLAGS and ATLAS_HOST_LDFLAGS when set (CI's build
// job: LLVM's clang and libc++, as core.yml installs them; they reach this task alone, not AGP's NDK build), else CC,
// CXX and the rest, else the system's (Apple's on this Mac). CMake and Ninja are the Android SDK's, at the version
// mapcore/tools.properties pins, unless -Patlas.cmake (or ATLAS_CMAKE) names another; ccache when ATLAS_CCACHE=1. The
// drawing tests' Vulkan: ATLAS_LAVAPIPE_DIR (a folder of the pinned emulator package's loader, lavapipe and validation
// layer, as CI unpacks it), else the loader's own variables when they are set, else the SDK's emulator package, whose
// loader, lavapipe and validation layer run on this Mac and on Linux.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

val repoDir: File = rootProject.layout.projectDirectory.asFile.resolve("../..").canonicalFile
val mapcoreDir: File = rootProject.layout.projectDirectory.dir("mapcore").asFile
val pins = Properties().apply {
    load(providers.fileContents(rootProject.layout.projectDirectory.file("mapcore/tools.properties")).asText.get().reader())
}
val osName: String = providers.systemProperty("os.name").get().lowercase()
val isMac = osName.contains("mac")
val home: String = providers.systemProperty("user.home").get()

/** The Android SDK: ANDROID_HOME, ANDROID_SDK_ROOT, or where Android Studio puts it. */
val sdkDir: File? = listOfNotNull(
    providers.environmentVariable("ANDROID_HOME").orNull,
    providers.environmentVariable("ANDROID_SDK_ROOT").orNull,
    if (isMac) "$home/Library/Android/sdk" else "$home/Android/Sdk",
).map(::File).firstOrNull { it.isDirectory }

val sdkCmakeBin: File? = sdkDir?.resolve("cmake/${pins.getProperty("cmake.version")}/bin")?.takeIf { it.resolve("cmake").isFile }
val cmakeExe: String = providers.gradleProperty("atlas.cmake").orElse(providers.environmentVariable("ATLAS_CMAKE"))
    .getOrElse(sdkCmakeBin?.resolve("cmake")?.path ?: "cmake")
val ninjaExe: String? = providers.gradleProperty("atlas.ninja").orElse(providers.environmentVariable("ATLAS_NINJA")).orNull
    ?: sdkCmakeBin?.resolve("ninja")?.takeIf { it.isFile }?.path

/** The pinned tools' cache (Gradle's :mapcore:fetchSlang unpacks slangc there): -Patlas.tools moves it, as for :mapcore. */
val toolsPath: String = providers.gradleProperty("atlas.tools").getOrElse(repoDir.resolve("cache/atlas-tools").path)

/** The JDK the tests run on: its jni.h for the host build. */
val testJdk: Provider<String> = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }
    .map { it.metadata.installationPath.asFile.absolutePath }

/** The compiler's environment: CI's ATLAS_HOST_* (LLVM's clang and libc++), else CC and the rest; nothing on this Mac. */
val compilerEnvironment: Map<String, String> = listOf("CC", "CXX", "CFLAGS", "CXXFLAGS", "LDFLAGS").mapNotNull { k ->
    (providers.environmentVariable("ATLAS_HOST_$k").orNull ?: providers.environmentVariable(k).orNull)?.let { k to it }
}.toMap()

/** ccache as the host build's compiler launcher, as for :mapcore's NDK build (CI sets ATLAS_CCACHE=1). */
val hostCcache: Boolean = providers.environmentVariable("ATLAS_CCACHE").map { it == "1" }.getOrElse(false)

/**
 * How many jobs the host build runs at once, as :mapcore's NDK build: -Patlas.jobs or ATLAS_JOBS when given, else
 * Ninja's own -j on CI (CI=true: every core) and 3 on a developer's machine, where other builds and tests run beside it
 * (ENGINE.md, How to work).
 */
val buildJobs: Int? = providers.gradleProperty("atlas.jobs").orElse(providers.environmentVariable("ATLAS_JOBS")).orNull
    ?.let { it.toIntOrNull()?.takeIf { n -> n > 0 } ?: throw GradleException("atlas.jobs is a number, not '$it'") }
    ?: if (providers.environmentVariable("CI").orNull == "true") null else 3

/** CMake's build type for the host build, from -Patlas.mapcore.buildType in any case (Debug when not given). */
val hostBuildType: String = providers.gradleProperty("atlas.mapcore.buildType").getOrElse("Debug").let { given ->
    when (given.lowercase()) {
        "debug" -> "Debug"
        "release" -> "Release"
        "bench", "relwithdebinfo" -> "RelWithDebInfo"
        "minsizerel" -> "MinSizeRel"
        else -> throw GradleException("atlas.mapcore.buildType is Debug, Release or Bench, not '$given'")
    }
}

/**
 * Configures the core's CMake project for this machine (again when its settings change) and builds libatlasmap_host,
 * and stage P's tool (atlas_stagep), which the packer cuts its tiles with. CMake and Ninja keep their own incremental
 * state, so a build after no change is quick.
 */
@DisableCachingByDefault(because = "CMake and Ninja keep their own incremental state")
abstract class CoreHostBuild @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    @get:IgnoreEmptyDirectories
    abstract val sources: ConfigurableFileCollection

    @get:Internal abstract val sourceDir: DirectoryProperty
    @get:Internal abstract val cmakeDir: DirectoryProperty
    @get:Input abstract val cmake: Property<String>
    @get:Input @get:Optional abstract val ninja: Property<String>
    @get:Input abstract val buildType: Property<String>
    @get:Input abstract val toolsDir: Property<String>
    @get:Input abstract val javaHome: Property<String>
    @get:Input abstract val compilerEnv: MapProperty<String, String>
    @get:Input abstract val ccache: Property<Boolean>
    /** How many jobs Ninja runs at once; not set, its own -j. */
    @get:Internal abstract val jobs: Property<Int>
    @get:OutputFile abstract val library: RegularFileProperty
    @get:OutputFile abstract val tool: RegularFileProperty

    @TaskAction
    fun build() {
        val dir = cmakeDir.get().asFile.apply { mkdirs() }
        val env = compilerEnv.get() + ("JAVA_HOME" to javaHome.get())
        val configure = listOf(
            cmake.get(), "-S", sourceDir.get().asFile.path, "-B", dir.path, "-G", "Ninja",
            "-DCMAKE_BUILD_TYPE=${buildType.get()}", "-DATLAS_SANITIZE=", "-DATLAS_TESTS=OFF", "-DATLAS_FUZZ=OFF",
            "-DATLAS_BENCH=OFF", "-DATLAS_TOOLS_DIR=${toolsDir.get()}",
        ) + listOfNotNull(ninja.orNull?.let { "-DCMAKE_MAKE_PROGRAM=$it" }) +
            (if (ccache.get()) listOf("-DCMAKE_C_COMPILER_LAUNCHER=ccache", "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache") else emptyList())
        // Configured again when its command or environment changes (a new compiler, another build type).
        val stamp = dir.resolve("atlas-configure.txt")
        val wanted = (configure + env.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).joinToString("\n")
        if (!dir.resolve("build.ninja").isFile || !stamp.isFile || stamp.readText() != wanted) {
            exec.exec {
                environment(env)
                commandLine(configure)
            }
            stamp.writeText(wanted)
        }
        exec.exec {
            environment(env)
            commandLine(
                listOf(cmake.get(), "--build", dir.path) + jobs.orNull?.let { listOf("-j", "$it") }.orEmpty() +
                    listOf("--target", "atlasmap", "atlas_stagep"),
            )
        }
        check(library.get().asFile.isFile) { "the core's host build made no ${library.get().asFile}" }
        check(tool.get().asFile.isFile) { "the core's host build made no ${tool.get().asFile}" }
    }
}

val coreHost = tasks.register<CoreHostBuild>("coreHost") {
    description = "Builds the core's host library (libatlasmap_host) for the JNI tests."
    sources.from(fileTree(mapcoreDir.resolve("cpp")) { exclude("build/**", ".cache/**") })
    sources.from(mapcoreDir.resolve("tools.properties"))
    sourceDir.set(mapcoreDir.resolve("cpp"))
    cmakeDir.set(layout.buildDirectory.dir("core-host"))
    cmake.set(cmakeExe)
    ninjaExe?.let { ninja.set(it) }
    buildType.set(hostBuildType)
    toolsDir.set(toolsPath)
    javaHome.set(testJdk)
    compilerEnv.set(compilerEnvironment)
    ccache.set(hostCcache)
    buildJobs?.let { jobs.set(it) }
    library.set(layout.buildDirectory.file("core-host/libatlasmap_host.${if (isMac) "dylib" else "so"}"))
    tool.set(layout.buildDirectory.file("core-host/atlas_stagep"))
    // slangc, pinned and checked (the core's CMake refuses another), when :mapcore is in the build
    if (rootProject.findProject(":mapcore") != null) dependsOn(":mapcore:fetchSlang")
}

// The pack tests' render packs (K2): the Westerkerk's living map and the canal ring, in the port's cut (the reference the
// parity tests hold the core to), with their index, made by the packer (`render-tiles --bundled`) for L0 (b) and every
// test here that draws or reads them, and for the core's host tests (core.yml's tiles job, ATLAS_TILES_DIR). No APK
// carries them for a map (ENGINE.md step 3, one pack per country).
val packerTool: Configuration = configurations.create("packerTool") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "jvm")
    }
}

val bundledTilesDir = layout.buildDirectory.dir("bundled-tiles")
val bundledTiles = tasks.register<JavaExec>("bundledTiles") {
    description = "Builds the pack tests' render packs with the packer (the Westerkerk and the canal ring, the port's cut)."
    classpath = packerTool
    mainClass.set("app.atlas.packer.RenderTiles")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    maxHeapSize = "2g"
    val westerkerk = rootProject.layout.projectDirectory.file("app/src/main/assets/packs/amsterdam.atlas")
    inputs.file(westerkerk).withPropertyName("westerkerk").withPathSensitivity(PathSensitivity.NONE)
    val out = bundledTilesDir.get().asFile
    val work = temporaryDir.resolve("work")
    outputs.dir(out)
    args(
        "--bundled", out.path, "--repo", repoDir.path, "--packs", repoDir.resolve("cache/packs/regions").path,
        "--work", work.path, "--quiet",
    )
    doFirst {
        out.deleteRecursively()
        work.deleteRecursively()
    }
    doLast { work.deleteRecursively() }
}

/** :mapcore's Kotlin (the façade's package), and its files that use Android's types, which this module leaves out. */
val mapcoreKotlin: File = mapcoreDir.resolve("src/main/kotlin/app/atlas/mapcore")
val mapcoreAndroidOnly: List<String> = listOf("MapCore.kt", "CoreConfig.kt", "ProcessExits.kt")

sourceSets {
    // The façade's pure Kotlin (K1) and the camera and theme writers (K5) are in :mapcore, an Android library: compiled
    // here as well, all but the files that use Android's types. They are left out by their own paths in :mapcore, so a
    // file of the same name among this module's sources stays in. A file of :mapcore that comes to use Android's types
    // must join mapcoreAndroidOnly: mapcoreAndroidFree names it before the compiler fails on its android.* import.
    test {
        val androidOnly = mapcoreAndroidOnly.map(mapcoreKotlin::resolve).toSet()
        kotlin.srcDir(mapcoreKotlin)
        kotlin.exclude { it.file in androidOnly }
        // Files of :mapview, an Android library this module cannot depend on, that use no Android types, compiled here
        // without their neighbours: K17a's field parity (MT/fx) runs the port's Turbulence beside the core's (it needs
        // only :core's ExactMath), and the phone look tool (MT/likeness/PhoneLook.kt) writes the core's moment and
        // parts with the app's own CoreMoment and MapParts.
        val mapviewKotlin = rootProject.layout.projectDirectory.dir("mapview/src/main/kotlin/app/atlas/mapview").asFile
        val mapviewShared = listOf("svg/Turbulence.kt", "MapParts.kt", "vk/CoreMoment.kt").map(mapviewKotlin::resolve).toSet()
        kotlin.srcDir(mapviewKotlin)
        kotlin.exclude { it.file.isFile && it.file.startsWith(mapviewKotlin) && it.file !in mapviewShared }
    }
}

/** Fails, naming them, when files of :mapcore that this module compiles import Android's types. */
abstract class AndroidFree : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Input abstract val excluded: ListProperty<String>

    @TaskAction
    fun check() {
        val android = Regex("""^\s*import\s+androidx?\.""", RegexOption.MULTILINE)
        val using = sources.asFileTree.files.filter { android.containsMatchIn(it.readText()) }.map { it.name }.sorted()
        if (using.isNotEmpty()) {
            throw GradleException(
                "${using.joinToString()} in :mapcore ${if (using.size == 1) "uses" else "use"} Android's types, which " +
                    ":mapcorecheck's JVM tests cannot compile: " +
                    "add them to mapcoreAndroidOnly in mapcorecheck/build.gradle.kts (now ${excluded.get().joinToString()})",
            )
        }
    }
}

val mapcoreAndroidFree = tasks.register<AndroidFree>("mapcoreAndroidFree") {
    description = "Checks that the files of :mapcore this module's tests compile use no Android types."
    sources.from(fileTree(mapcoreKotlin) { include("**/*.kt"); exclude(mapcoreAndroidOnly) })
    excluded.set(mapcoreAndroidOnly)
}
tasks.named("compileTestKotlin") { dependsOn(mapcoreAndroidFree) }

dependencies {
    packerTool(project(":packer"))
    testImplementation(project(":core"))
    // the phone's reader of a region's windows' index (WindowPackBuildTest), and of its country's tiles (PhoneLook)
    testImplementation(project(":tiles"))
    testImplementation(libs.okhttp)
    testImplementation(project(":render"))
    testImplementation(project(":rendertiles"))
    testImplementation(testFixtures(project(":rendertiles")))
    testImplementation(testFixtures(project(":anchor")))
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

/**
 * The loader's variables for lavapipe: from ATLAS_LAVAPIPE_DIR (CI), else, unless the environment already names a
 * driver, from the SDK's emulator package.
 */
val vulkanEnv: Map<String, String> = run {
    val env = mutableMapOf<String, String>()
    val pinned = providers.environmentVariable("ATLAS_LAVAPIPE_DIR").orNull?.let(::File)
    val given = listOf("VK_DRIVER_FILES", "VK_ICD_FILENAMES").any { providers.environmentVariable(it).isPresent }
    val dir = pinned ?: sdkDir?.resolve("emulator/lib64/vulkan")?.takeIf { !given }
    if (dir != null && dir.resolve("lvp_icd.json").isFile) {
        env["VK_DRIVER_FILES"] = dir.resolve("lvp_icd.json").path
        if (dir.resolve("layers").isDirectory) env["VK_ADD_LAYER_PATH"] = dir.resolve("layers").path
        val loader = providers.environmentVariable("ATLAS_VULKAN_LOADER").orNull
            ?: listOf("libvulkan.dylib", "libvulkan.so.1", "libvulkan.so").map(dir::resolve).firstOrNull { it.isFile }?.path
        loader?.let { env["ATLAS_VULKAN_LOADER"] = it }
        if (!isMac) env["LD_LIBRARY_PATH"] = listOfNotNull(dir.path, providers.environmentVariable("LD_LIBRARY_PATH").orNull).joinToString(":")
    }
    env
}

tasks.test {
    dependsOn(coreHost, bundledTiles)
    maxHeapSize = "2g"
    val library = coreHost.flatMap { it.library }
    val stagep = coreHost.flatMap { it.tool }
    inputs.file(library).withPropertyName("coreHostLibrary").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(stagep).withPropertyName("coreStagePTool").withPathSensitivity(PathSensitivity.NONE)
    inputs.dir(bundledTilesDir).withPropertyName("bundledTiles").withPathSensitivity(PathSensitivity.RELATIVE)
    val tilesDir = bundledTilesDir.get().asFile.path
    val l0Out = layout.buildDirectory.dir("l0").get().asFile.path
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Datlas.mapcore.host=${library.get().asFile.absolutePath}", "-Datlas.stagep=${stagep.get().asFile.absolutePath}",
            "-Datlas.tiles=$tilesDir", "-Datlas.l0.out=$l0Out",
        )
    })
    environment(vulkanEnv)
    systemProperty("atlas.repo", repoDir.path)
    // K3's differential check: how many single-byte damages of the bundled tiles the core and Kotlin decode side by side
    providers.systemProperty("atlas.l0.damages").orNull?.let { systemProperty("atlas.l0.damages", it) }
    // K3b's parity sweep: arguments a function (CI's build job runs 10^5; the nightly the plan's 10^7)
    providers.systemProperty("atlas.numerics.n").orNull?.let { systemProperty("atlas.numerics.n", it) }
    // Stage P's parity (load/AmsterdamParityTest.kt): more raw.json files the living map's load is held to the port
    // over, a list split by the path separator (the renderer's data/regions on the Mac)
    providers.systemProperty("atlas.load.raws").orNull?.let { systemProperty("atlas.load.raws", it) }
    // and the regions' element packs load/RegionsParityTest holds to the port's Regions.load (a list of .atlas files)
    providers.systemProperty("atlas.load.packs").orNull?.let { systemProperty("atlas.load.packs", it) }
    // a region's windows' data (`packer windows`: <id>.windows, its index beside it) load/WindowPackBuildTest builds from
    providers.systemProperty("atlas.windows").orNull?.let { systemProperty("atlas.windows", it) }
    // and where load/ZooWoodsTest keeps the windows about Artis it builds with and without their woods
    providers.systemProperty("atlas.zoowoods.out").orNull?.let { systemProperty("atlas.zoowoods.out", it) }

    // K4's likeness harness: the references are inputs (they live outside the build folder), the report goes to
    // atlas.likeness.report, and the harness's settings pass through.
    systemProperty("java.awt.headless", "true")
    inputs.files(providers.systemProperty("atlas.likeness.refs").orElse(repoDir.resolve("cache/likeness/refs").absolutePath))
        .withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("likenessReferences")
    systemProperty(
        "atlas.likeness.report",
        providers.systemProperty("atlas.likeness.report").getOrElse(layout.buildDirectory.dir("likeness").get().asFile.absolutePath),
    )
    for (p in listOf("refs", "tier", "only", "phone", "style", "platform", "stage", "commit", "thresholds", "previous", "require")) {
        providers.systemProperty("atlas.likeness.$p").orNull?.let { systemProperty("atlas.likeness.$p", it) }
    }
    // The desktop render tool (likeness/RenderTool.kt): what to draw, and the PNG it writes, which is drawn again on
    // every run that asks for one.
    for (p in listOf("out", "pack", "view", "moment", "size", "mode")) {
        providers.systemProperty("atlas.render.$p").orNull?.let { systemProperty("atlas.render.$p", it) }
    }
    if (providers.systemProperty("atlas.render.out").isPresent) outputs.upToDateWhen { false }
    // The phone look tool (likeness/PhoneLook.kt): what to draw and where, and the folder it compares; it runs every
    // time it is asked.
    for (p in listOf("out", "views", "moment", "size", "parts", "path", "canal", "list", "work", "compare")) {
        providers.systemProperty("atlas.phonelook.$p").orNull?.let {
            systemProperty("atlas.phonelook.$p", it)
            outputs.upToDateWhen { false }
        }
    }
    // The engine's own references (likeness/EngineRefs.kt): a run that records them writes the source tree's
    // likeness/engine.json, so it runs every time it is asked.
    providers.systemProperty("atlas.engine.record").orNull?.let {
        systemProperty("atlas.engine.record", it)
        outputs.upToDateWhen { false }
    }
}
