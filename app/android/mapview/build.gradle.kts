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
    testOptions {
        unitTests {
            // Robolectric draws real pixels (native graphics) at SDK 34, the highest JDK 17 runs.
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
                // -Datlas.svg.only=<prefix> limits the SVG reference comparison to some files while working on them.
                providers.systemProperty("atlas.svg.only").orNull?.let { only -> it.systemProperty("atlas.svg.only", only) }
                providers.systemProperty("atlas.svg.file").orNull?.let { file -> it.systemProperty("atlas.svg.file", file) }
                providers.systemProperty("atlas.svg.mode").orNull?.let { mode -> it.systemProperty("atlas.svg.mode", mode) }
            }
        }
    }
}

dependencies {
    // The engine: the 1:1 port of render/ (and its region packs); the screens still hand the map a tile source.
    api(project(":render"))
    api(project(":anchor"))
    api(project(":tiles"))
    api(project(":places"))
    implementation(libs.androidx.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
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
