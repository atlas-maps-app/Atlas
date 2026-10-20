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
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    maxHeapSize = "1536m"
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // Tests that call the real services (Overpass, Photon) run only with -Datlas.online=true.
    System.getProperty("atlas.online")?.let { systemProperty("atlas.online", it) }
    // The benchmarks print their timings; on a slow CI runner their budgets stretch.
    System.getenv("CI")?.let { systemProperty("atlas.ci", it) }
}
