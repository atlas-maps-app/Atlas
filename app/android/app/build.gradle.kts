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

    // The release key comes from the environment (CI secrets, see .github/workflows/release.yml); without it the
    // release build is signed with the debug key so it still installs. Alphas are debug builds signed with the release
    // key, so they update one another and a later release.
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
        debug {
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKey != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        // Built-in Kotlin takes its jvmTarget from targetCompatibility; nothing else to set.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
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
                it.systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
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
    // Every Atlas module: the shared model, tiles, the engine and the map view, OSM knowledge, providers, search,
    // routing and the guide.
    implementation(project(":core"))
    implementation(project(":tiles"))
    implementation(project(":engine"))
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
