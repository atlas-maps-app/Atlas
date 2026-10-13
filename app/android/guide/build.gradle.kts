plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    // The phrase tables (osrm-text-instructions and Atlas's own) are JSON resources, read as a tree; no compiler plugin needed.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "1536m"
    // Shared fixtures (real OpenFreeMap tiles, OSM extracts) live in app/android/testdata; the repo's data/ is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
