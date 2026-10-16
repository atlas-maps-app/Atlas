// Render tiles (plan-vector K2): stage P of Atlas's vector map engine. The port's own loaded scene (`:render`) written as
// format-3 render tiles: D1's pack container with a columnar render-tile payload in zstd chunks, the binary `rhead` and
// `style` sections the core reads, keep codes, ground keys and label candidates. Plain Kotlin on the JVM: the packer
// (`packer render-tiles`), the app's bundled tiles (Gradle), the core's checks. The test fixtures read the tiles back.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    // the engine port: the scene a tile is derived from, its frame and its views
    api(project(":render"))
    // zstd for the tiles' chunks (the core decompresses them with the same library): the reference implementation
    // through JNI, BSD-2-Clause (zstd itself BSD-3-Clause)
    implementation(libs.zstd.jni)
    // the pack's JSON manifest, through render's PackManifest
    implementation(libs.kotlinx.serialization.json)
    testFixturesApi(project(":render"))
    testFixturesImplementation(libs.zstd.jni)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // the canal ring's scene is loaded whole, as the packer loads it
    maxHeapSize = "3g"
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
