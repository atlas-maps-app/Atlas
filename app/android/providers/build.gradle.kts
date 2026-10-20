plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(libs.kotlinx.coroutines.core)
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "1536m"
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // Tests that call the real services run only with -Datlas.online=true.
    System.getProperty("atlas.online")?.let { systemProperty("atlas.online", it) }
    // A downloaded national file for a one-off full-size parse (see NdwTest), and an Open Charge Map key for its online test.
    System.getProperty("atlas.ndw.file")?.let { systemProperty("atlas.ndw.file", it) }
    System.getProperty("atlas.ocm.key")?.let { systemProperty("atlas.ocm.key", it) }
}
