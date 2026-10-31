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
    // render tiles (plan-vector K2): `packer render-tiles`
    implementation(project(":rendertiles"))
    // zstd's compressor for `packer country`, which stores the core's window packs with their chunks at level 19 (the
    // core vendors zstd's decompression alone): rendertiles' own
    implementation(libs.zstd.jni)
    // `packer world`: Planetiler (Apache-2.0) runs Atlas's own profile over an extract into a scratch MBTiles (ENGINE.md
    // step 5); its shapefile and EPSG readers (GeoTools, not on Maven Central) are left out, as the packer reads the land
    // polygons and Natural Earth's shapefiles itself
    implementation(libs.planetiler.core) { exclude(group = "org.geotools") }
    implementation(libs.sqlite.bundled)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    // the format-3 reader, Kotlin's twin of the core's: a tile decoded and checked
    testImplementation(testFixtures(project(":rendertiles")))
}

tasks.test {
    // the world's checks on real data (ENGINE.md step 5), skipped without them: the extracts, windows and packs they
    // read, as -Datlas.world.<name>=<path> on the command line, and the heap Planetiler takes for a region's extract
    // (-Datlas.world.heap, at most 4g)
    maxHeapSize = providers.systemProperty("atlas.world.heap").orElse("1536m").get()
    for ((k, v) in providers.systemPropertiesPrefixedBy("atlas.world.").get()) systemProperty(k, v)
    // The repo's root, with its cache/ (big downloads such as Luxembourg) and data/.
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // The core's stage P tool (atlas_stagep, the host-stagep preset's), which `packer country` builds a country's windows
    // with, from -Patlas.stagep or ATLAS_STAGEP (android.yml's build job builds it before the tests); without it the tests
    // that need it are skipped.
    providers.gradleProperty("atlas.stagep").orElse(providers.environmentVariable("ATLAS_STAGEP")).orNull?.let { path ->
        val tool = file(path)
        inputs.files(tool).withPropertyName("coreStagePTool").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("atlas.stagep", tool.absolutePath)
    }
}
