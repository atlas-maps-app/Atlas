plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    api(project(":tiles"))
    // Tradition profiles are JSON resources, read as a plain element tree (no reflection, no compiler plugin).
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "1536m"
    // Shared fixtures (real OpenFreeMap tiles, OSM extracts) live in app/android/testdata; the repo's data/ is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // The preview renders (Java2D, PNG) land here.
    systemProperty("atlas.preview", layout.buildDirectory.dir("preview").get().asFile.absolutePath)
    providers.systemProperty("atlas.online").orNull?.let { systemProperty("atlas.online", it) }
}
