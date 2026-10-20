plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    implementation(project(":brouter"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    maxHeapSize = "1536m"
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // BRouter segment files for the real-data tests (they skip when E0_N50.rd5 is missing): the repo's git-ignored cache.
    systemProperty("atlas.segments", System.getProperty("atlas.segments") ?: rootProject.file("../../cache/brouter/segments4").absolutePath)
    System.getProperty("atlas.online")?.let { systemProperty("atlas.online", it) }
}
