plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(project(":places"))
    api(project(":providers"))
    implementation(libs.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "1536m"
    // Shared fixtures (real OpenFreeMap tiles, OSM extracts) live in app/android/testdata; the repo's data/ is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
