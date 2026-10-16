// The pack builder, a command-line tool: an OSM PBF extract to the place store `:search` reads, and the packs manifest.
// .github/workflows/packs.yml runs it on GitHub; `./gradlew :packer:installDist` puts it in build/install/packer.
plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

application {
    mainClass.set("app.atlas.packer.MainKt")
    applicationName = "packer"
    // A country's node locations sit in memory; the workflow raises this with JAVA_OPTS.
    applicationDefaultJvmArgs = listOf("-Xmx4g")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":places"))
    implementation(project(":search"))
    implementation(project(":tiles"))
    // the region pack format (PackWriter, PackReader, the Overpass client): plain Kotlin, shared with the phone
    implementation(project(":render"))
    implementation(project(":anchor"))
    implementation(libs.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    maxHeapSize = "1536m"
    // Shared fixtures live in app/android/testdata; the repo's cache/ (big downloads such as Luxembourg) is one level up.
    systemProperty("atlas.testdata", rootProject.file("testdata").absolutePath)
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
}
