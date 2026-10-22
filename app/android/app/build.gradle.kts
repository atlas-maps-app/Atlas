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
    }

    // Packs (.atlas) are compressed inside already, chunk by chunk: stored as they are, the core maps the bundled render
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
        // CI builds and runs it next to the debug APK (ci/smoke.sh); releases do not carry it.
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
// so MBTiles packs, the tile cache and the place store work in tests as on the phone.
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

// The bundled render tiles (plan-vector K2): the vector engine's first look on a first launch. The Westerkerk's living map
// (from the app's bundled pack) and the canal ring (from packs-renders' grachten.atlas, fetched once into the repo's
// cache and checked against its published SHA-256) as format-3 render packs, made at build time by the packer through the
// same engine port the renders come from, into the assets' tiles/ folder with their index bundled.json. They are built,
// not committed: the task runs again when the Westerkerk's pack or the packer's classpath (the engine port, the
// render-tile compiler, the packer and their libraries; the canal ring's published hash is in the packer) changes, and
// is up to date, or taken from the build cache, otherwise.
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

/** Runs `packer render-tiles --bundled` into [outputDir]`/tiles`. */
@CacheableTask
abstract class BundledTiles @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
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

    /** An asset root: the render packs land in its tiles/ folder. */
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val work = File(temporaryDir, "work")
        work.deleteRecursively()
        val root = repo.get().asFile
        try {
            exec.javaexec {
                classpath = tool
                mainClass.set("app.atlas.packer.RenderTiles")
                executable = launcher.get().executablePath.asFile.absolutePath
                // the canal ring's scene, whole, and one other load at a time for its tolerances
                maxHeapSize = "2g"
                args(
                    "--bundled", File(out, "tiles").absolutePath, "--repo", root.absolutePath, "--packs", File(root, "cache/packs/regions").absolutePath,
                    "--work", work.absolutePath, "--quiet",
                )
                core.orNull?.let { args("--core", it.asFile.absolutePath) }
            }
        } finally {
            work.deleteRecursively()
        }
    }
}

val generateBundledTiles = tasks.register<BundledTiles>("generateBundledTiles") {
    description = "Builds the bundled render tiles (plan-vector K2) into the app's assets."
    tool.from(packerTool)
    westerkerk.set(layout.projectDirectory.file("src/main/assets/packs/amsterdam.atlas"))
    repo.set(rootProject.layout.projectDirectory.dir("../.."))
    launcher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
    providers.gradleProperty("atlas.stagep").orElse(providers.environmentVariable("ATLAS_STAGEP")).orNull?.let { core.set(file(it)) }
    outputDir.set(layout.buildDirectory.dir("generated/tiles"))
}
androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(generateBundledTiles, BundledTiles::outputDir) }
}
