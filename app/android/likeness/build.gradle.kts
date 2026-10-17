// The likeness oracle (plan-vector D16, K4): the port's exact references at each case's density, with their owner maps
// and label lists, the feature ladder, the stub's planted frames and a phone's VK check asset. Robolectric draws them with
// Android's own Skia. Tests only: nothing here ships in the app. The candidate side (the core on lavapipe) is :mapcorecheck,
// a separate JVM, which reads the reference sets this module writes.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.atlas.likeness"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                // The canal ring's scene takes about 1 GB, a reference at S3 40 MB a copy; -Plikeness.heap=7g for its nights.
                it.maxHeapSize = providers.gradleProperty("likeness.heap").getOrElse("4g")
                // The references live outside the build folder (CI caches them by the sources that draw them): the
                // tests always run, and restore a set that is there in a moment.
                it.outputs.upToDateWhen { false }
                it.outputs.cacheIf { false }
                it.systemProperty("robolectric.graphicsMode", "NATIVE")
                it.systemProperty("java.awt.headless", "true")
                it.systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
                it.systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
                // Where the reference sets go (CI caches them), which tier, shard and cases, a phone's size, the packs, the
                // style version, where a VK check asset goes, the cold build's time budget in seconds, whether every pack
                // must be there; and the coverage calibration's run (-Datlas.calibrate=true or arch, as ATLAS_CALIBRATE).
                for (p in listOf("atlas.likeness.refs", "atlas.likeness.tier", "atlas.likeness.shard", "atlas.likeness.only", "atlas.likeness.phone",
                    "atlas.likeness.packs", "atlas.likeness.style", "atlas.likeness.asset", "atlas.likeness.budget", "atlas.likeness.require",
                    "atlas.calibrate"))
                    providers.systemProperty(p).orNull?.let { v -> it.systemProperty(p, v) }
            }
        }
    }
}

dependencies {
    // The oracle: the port (:render through :mapview), SvgCanvas and the exact filters, the fonts, the world tape.
    testImplementation(project(":mapview"))
    // AnchorCases (the regions and the home views), RefMetrics, the parser's model.
    testImplementation(testFixtures(project(":anchor")))
    // The relief image's outline (an SVG path) as an Android Path.
    testImplementation(libs.androidx.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
