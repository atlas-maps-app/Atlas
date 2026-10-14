// Atlas's engine, ported 1:1 from the Python engine in render/ (see render/README.md): plain Kotlin, no Android types.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "3g"
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // The Python oracle runs in the tests (python3, PYTHONHASHSEED=0); its outputs go to the repo's cache/oracle.
    environment("PYTHONHASHSEED", "0")
    System.getProperty("atlas.online")?.let { systemProperty("atlas.online", it) }
}
