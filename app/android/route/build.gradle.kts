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
    // Shared fixtures (real OpenFreeMap tiles, OSM extracts) live in app/android/testdata; the repo's data/ is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // BRouter segment files for the real-data tests (they skip when E0_N50.rd5 is missing): the repo's git-ignored cache.
    systemProperty("atlas.segments", System.getProperty("atlas.segments") ?: rootProject.file("../../cache/brouter/segments4").absolutePath)
    System.getProperty("atlas.online")?.let { systemProperty("atlas.online", it) }
}
