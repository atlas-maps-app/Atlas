import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.atlas"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.atlas"
        minSdk = 29
        targetSdk = 37
        // A release build on GitHub numbers itself (ATLAS_VERSION_CODE is the workflow's run number, ATLAS_VERSION the tag),
        // so every alpha installs over the one before.
        versionCode = System.getenv("ATLAS_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("ATLAS_VERSION") ?: "0.1"
        // The OpenStreetMap OAuth 2.0 client the user registers on openstreetmap.org (a public client id, no secret):
        // -Patlas.osmClientId=... or ATLAS_OSM_CLIENT_ID. Empty, signing in to OpenStreetMap is switched off.
        val osmClientId = providers.gradleProperty("atlas.osmClientId").orElse(providers.environmentVariable("ATLAS_OSM_CLIENT_ID")).getOrElse("")
        buildConfigField("String", "OSM_CLIENT_ID", "\"" + osmClientId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "\"")
        // The same client registered on OpenStreetMap's test server, master.apis.dev.openstreetmap.org, for debug and bench
        // builds' round trip there: -Patlas.osmDevClientId=... or ATLAS_OSM_DEV_CLIENT_ID. Empty, the round trip is hidden.
        val osmDevClientId = providers.gradleProperty("atlas.osmDevClientId").orElse(providers.environmentVariable("ATLAS_OSM_DEV_CLIENT_ID")).getOrElse("")
        buildConfigField("String", "OSM_DEV_CLIENT_ID", "\"" + osmDevClientId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + "\"")
    }

    // Packs (.atlas) are compressed inside already, chunk by chunk: stored as they are, the core maps VK facts' Westerkerk
    // tiles straight from the APK (and the element pack reads in ranges).
    androidResources { noCompress += "atlas" }

    // The release key comes from the environment (CI secrets, see .github/workflows/release.yml); without it the
    // release build is signed with the debug key so it still installs. Alphas are debug builds signed with the release
    // key, so they update one another and a later release. The bench build (below) is signed the same way.
    val releaseKey = System.getenv("ATLAS_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKey != null) create("release") {
            storeFile = releaseKey
            storePassword = System.getenv("ATLAS_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ATLAS_KEY_ALIAS")
            keyPassword = System.getenv("ATLAS_KEY_PASSWORD")
        }
    }

    buildTypes {
        // BuildConfig.TIMINGS gates the frame-timings overlay on the home map (the engine's load, render, parse and
        // raster times of each exact frame): on in debug and bench, off in release. The overlay used to follow
        // FLAG_DEBUGGABLE, which a non-debuggable build does not have.
        debug {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
            buildConfigField("boolean", "TIMINGS", "true")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKey != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            buildConfigField("boolean", "TIMINGS", "false")
        }
        // The bench build: what a user's phone runs (not debuggable, so ART compiles and runs it as a release app does,
        // and the numbers are the phone's) with the timings overlay kept. Same key as the others, so it installs over them.
        // Profileable, so simpleperf (and Android Studio's profilers) can sample it on a phone without root.
        // CI builds and runs it next to the debug APK (ci/smoke.sh), and every alpha is one (release.yml): it carries no
        // map, as no APK does.
        create("bench") {
            initWith(getByName("release"))
            isDebuggable = false
            isProfileable = true
            isMinifyEnabled = false
            signingConfig = if (releaseKey != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("boolean", "TIMINGS", "true")
        }
    }
    compileOptions {
        // Built-in Kotlin takes its jvmTarget from targetCompatibility; nothing else to set.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Licence notices several jars carry under the same name; the app keeps its own in About & licences.
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/*.kotlin_module")
    }
    testOptions {
        unitTests {
            // Robolectric draws real pixels (native graphics) at SDK 34, the highest JDK 17 runs; see robolectric.properties.
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("robolectric.pixelCopyRenderMode", "hardware")
                it.systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
                // Screenshot tests always record, so a plain test run leaves the PNGs to look at (CI keeps them).
                it.systemProperty("roborazzi.test.record", "true")
                it.systemProperty("atlas.shots", layout.buildDirectory.dir("outputs/roborazzi").get().asFile.absolutePath)
                System.getProperty("atlas.online")?.let { v -> it.systemProperty("atlas.online", v) }
            }
        }
    }
}

dependencies {
    // Every Atlas module: the shared model, tiles, the engine (render) and the map view, OSM knowledge, providers,
    // search, routing and the guide.
    implementation(project(":core"))
    implementation(project(":tiles"))
    implementation(project(":render"))
    implementation(project(":places"))
    implementation(project(":providers"))
    implementation(project(":search"))
    implementation(project(":route"))
    implementation(project(":guide"))
    implementation(project(":mapview"))

    implementation(libs.androidx.core)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.car.app)
    implementation(libs.androidx.car.app.projected)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.sqlite.bundled)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Unit tests run on the JVM, where the Android build of the bundled SQLite cannot load its native library. The JVM build
// of the same catalog library carries one per desktop platform: unpack this machine's and put it on java.library.path,
// so the place store works in tests as on the phone.
val sqliteJvm: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java), "jvm")
    }
}
dependencies { sqliteJvm(libs.sqlite.bundled) }

interface ArchiveAccess {
    @get:Inject
    val archives: ArchiveOperations
}

val sqliteNativeDir = layout.buildDirectory.dir("sqlite-jvm-natives")
val sqlitePlatform = System.getProperty("os.name").lowercase().let { os ->
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> "osx_arm64"
        os.contains("win") -> "windows_x64"
        arm -> "linux_arm64"
        else -> "linux_x64"
    }
}
val unpackSqliteNatives = tasks.register<Sync>("unpackSqliteJvmNatives") {
    val archives = objects.newInstance<ArchiveAccess>().archives
    from(sqliteJvm.elements.map { files -> files.filter { it.asFile.name.startsWith("sqlite-bundled-jvm") }.map { archives.zipTree(it.asFile) } }) {
        include("natives/$sqlitePlatform/*")
        eachFile { path = name }
        includeEmptyDirs = false
    }
    into(sqliteNativeDir)
}
tasks.withType<Test>().configureEach {
    dependsOn(unpackSqliteNatives)
    val natives = sqliteNativeDir.map { it.asFile.absolutePath }
    doFirst {
        val test = this as Test
        val before = test.systemProperties["java.library.path"]?.toString()
        test.systemProperty("java.library.path", listOfNotNull(natives.get(), before).joinToString(File.pathSeparator))
    }
}

// The APK's only tiles: the Westerkerk's living render pack (plan-vector K2), for VK facts' hash lines alone (the sun's,
// the ground's and the twin's over it, which ci/hashes.env holds to the host build's), listed for no map. The APK
// carries no map (ENGINE.md step 3, one pack per country: the phone takes each place's pack from its country's tiles,
// or builds it). Made at build time by the packer through the same engine port the renders come from, from the app's
// bundled element pack (`packer render-tiles --renders`), into the assets' tiles/ folder; with the core's stage P tool
// (-Patlas.stagep or ATLAS_STAGEP) its tiles are the core's cut, the same bytes.
//
// The map CI's emulator runs draw at the Westerkerk home (VK bench's map, the home's first frames, the truth phase) is
// the canal ring, made apart from every APK (benchTiles: `packer render-tiles --bundled --draw grachten`, with the core's
// tool in the New looks, as the windows around it draw) and put on the emulator by ci/smoke.sh, where debug and bench
// builds list it (MainActivity.BENCH_PACKS). Neither is committed: each task runs again when its packs' sources or the
// packer's classpath (the engine port, the render-tile compiler, the packer and their libraries; the canal ring's
// published hash is in the packer) change, and is up to date, or taken from the build cache, otherwise. The pack tests'
// own set, both packs in the port's cut with their index, is :mapcorecheck's bundledTiles.
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
dependencies { packerTool(project(":packer")) }

/** Runs `packer render-tiles` with [arguments] into [outputDir]. */
abstract class RenderTilesTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    /** The packer and everything it runs: the engine port, the render-tile compiler and their libraries. */
    @get:Classpath
    abstract val tool: ConfigurableFileCollection

    /** The Westerkerk's element pack, the app's own. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val westerkerk: RegularFileProperty

    /** The repo's root: the packer finds the Westerkerk's pack under it, and keeps the canal ring's in its cache. */
    @get:Internal
    abstract val repo: DirectoryProperty

    @get:Nested
    abstract val launcher: Property<JavaLauncher>

    /**
     * The core's stage P tool (atlas_stagep, the host-stagep preset's), which then cuts the tiles (`packer render-tiles
     * --core`: the same tiles, byte for byte); without it, the port's cut. From -Patlas.stagep or ATLAS_STAGEP.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val core: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    /** The packer's arguments, writing into [out] and working in [work]. */
    protected abstract fun arguments(out: File, work: File): List<String>

    /** What is left of [out] once the packer is done. */
    protected open fun keep(out: File) {}

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val work = File(temporaryDir, "work")
        work.deleteRecursively()
        try {
            exec.javaexec {
                classpath = tool
                mainClass.set("app.atlas.packer.RenderTiles")
                executable = launcher.get().executablePath.asFile.absolutePath
                // the canal ring's scene, whole, and one other load at a time for its tolerances
                maxHeapSize = "2g"
                args(arguments(out, work))
            }
        } finally {
            work.deleteRecursively()
        }
        keep(out)
    }
}

/** The APK's tiles: the Westerkerk's living render pack alone, into [outputDir]`/tiles/amsterdam.atlas`. */
@CacheableTask
abstract class FactsTiles : RenderTilesTask() {
    override fun arguments(out: File, work: File): List<String> {
        val pack = File(out, "tiles/amsterdam.atlas").also { it.parentFile.mkdirs() }
        return listOf("--renders", westerkerk.get().asFile.absolutePath, pack.absolutePath, "--work", work.absolutePath, "--quiet") +
            (core.orNull?.let { listOf("--core", it.asFile.absolutePath) } ?: emptyList())
    }
}

/**
 * The canal ring CI's emulator runs draw, into [outputDir]`/grachten.atlas`: the bundled set's (`--bundled --draw
 * grachten`), with the core's tool in the renders' New looks, as the windows the phone builds around it draw (the user,
 * 28 October: "that orange buildings look when zoomed out"); the Westerkerk and the index it writes beside it go.
 */
@CacheableTask
abstract class BenchTiles : RenderTilesTask() {
    override fun arguments(out: File, work: File): List<String> {
        val root = repo.get().asFile
        return listOf(
            "--bundled", out.absolutePath, "--repo", root.absolutePath, "--packs", File(root, "cache/packs/regions").absolutePath,
            "--work", work.absolutePath, "--draw", "grachten", "--quiet",
        ) + (core.orNull?.let { listOf("--core", it.asFile.absolutePath, "--new-looks") } ?: emptyList())
    }

    override fun keep(out: File) {
        out.listFiles { f -> f.name != "grachten.atlas" }.orEmpty().forEach { it.deleteRecursively() }
    }
}

fun RenderTilesTask.packer() {
    tool.from(packerTool)
    westerkerk.set(layout.projectDirectory.file("src/main/assets/packs/amsterdam.atlas"))
    repo.set(rootProject.layout.projectDirectory.dir("../.."))
    launcher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    providers.gradleProperty("atlas.stagep").orElse(providers.environmentVariable("ATLAS_STAGEP")).orNull?.let { core.set(file(it)) }
}

val generateFactsTiles = tasks.register<FactsTiles>("generateFactsTiles") {
    description = "Builds the Westerkerk's living render pack into the app's assets, for VK facts' hash lines alone (no map lists it)."
    packer()
    outputDir.set(layout.buildDirectory.dir("generated/tiles"))
}
tasks.register<BenchTiles>("benchTiles") {
    description = "Builds the canal ring CI's emulator runs draw (ci/smoke.sh puts it on the device; no APK carries it)."
    packer()
    outputDir.set(layout.buildDirectory.dir("generated/bench-tiles"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(generateFactsTiles, FactsTiles::outputDir) }
}
