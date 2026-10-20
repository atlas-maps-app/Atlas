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
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // Tests against the real published packs run only with -Datlas.online=true.
    providers.systemProperty("atlas.online").orNull?.let { systemProperty("atlas.online", it) }
}
