plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Only to read the Overpass extracts and Earcut's JSON fixtures in tests.
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test {
    maxHeapSize = "1536m"
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
