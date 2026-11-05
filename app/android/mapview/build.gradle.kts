plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "app.atlas.mapview"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The CPU raster of the renders' SVG, today's map's on the phone until the core drew every place (ENGINE.md step 3):
    // in tests alone, as the likeness oracle (:likeness compiles the same folder into its tests), never in the APK.
    sourceSets { getByName("test") { kotlin.srcDir("src/raster/kotlin") } }
    testOptions {
        unitTests {
            // Robolectric draws real pixels (native graphics) at SDK 34, the highest JDK 17 runs.
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
                // -Datlas.svg.only=<prefix> limits the SVG reference comparison to some files while working on them.
                providers.systemProperty("atlas.svg.only").orNull?.let { only -> it.systemProperty("atlas.svg.only", only) }
                providers.systemProperty("atlas.svg.file").orNull?.let { file -> it.systemProperty("atlas.svg.file", file) }
                providers.systemProperty("atlas.svg.mode").orNull?.let { mode -> it.systemProperty("atlas.svg.mode", mode) }
                // -Datlas.calibrate=true reruns the likeness calibration over every D8 case and rewrites likeness.json (G1)
                providers.systemProperty("atlas.calibrate").orNull?.let { c -> it.systemProperty("atlas.calibrate", c) }
            }
        }
    }
}

dependencies {
    // The port of render/ the core's moment and the packs' readers still come from (ENGINE.md step 7 moves them).
    api(project(":render"))
    api(project(":places"))
    // The map's core (plan-vector D19): the C++23 Vulkan engine and its Kotlin façade.
    api(project(":mapcore"))
    // The world's index (world.idx: ENGINE.md step 5), read as the packer writes it.
    implementation(project(":tiles"))
    implementation(libs.androidx.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    // The renders' SVG model and parser the CPU raster draws (src/raster), and their test fixtures.
    testImplementation(project(":anchor"))
    testImplementation(testFixtures(project(":anchor")))
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Reads the Overpass extract of the Westerkerk area (real trees, streets to route along) in tests.
    testImplementation(libs.kotlinx.serialization.json)
    // The empty activity Compose UI tests host their content in.
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
