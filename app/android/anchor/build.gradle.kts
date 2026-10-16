// The GPU's anchors: exact engine renders lifted into world space, and the SVG model they are read into. Plain Kotlin,
// no Android types, so the packer builds the same anchors on GitHub as the phone does. Its test fixtures (the GPU rules
// emulated in Java) are shared with :mapview's tests.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    // the engine port: the renders an anchor is made from, and the world tape that lifts them
    api(project(":render"))
    testImplementation(libs.junit)
}

tasks.test {
    // An anchor of grachten_home is compiled from a render of its whole scene, as render's own tests make it.
    maxHeapSize = "3g"
    // Shared fixtures live in app/android/testdata; the repo's cache/ (the engine's renders) and data/ are one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
