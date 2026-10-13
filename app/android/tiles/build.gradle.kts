plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.sqlite.bundled)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "1536m"
    // Shared fixtures (real OpenFreeMap tiles, OSM extracts) live in app/android/testdata; the repo's data/ is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
