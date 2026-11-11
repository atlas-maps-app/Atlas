plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    maxHeapSize = "1536m"
    // The repo's root, whose data/ holds the shared fixtures (OSM extracts).
    systemProperty("atlas.repo", rootProject.file("../..").absolutePath)
    // Pictures the tests draw (the icon contact sheet) go to the task's own temporary folder.
    systemProperty("atlas.out", temporaryDir.absolutePath)
    // A palette to try before choosing it (-Patlas.palette=<file>): FamilyColoursTest draws and judges it beside today's.
    providers.gradleProperty("atlas.palette").orNull?.let { systemProperty("atlas.palette", it) }
    // A table of kinds' colours to try in place of the app's (-Patlas.kindColours=<file>, a line `kind #RRGGBB` each;
    // an empty file for none): put where KindColours reads it first, for this run only; FamilyColoursTest judges it.
    providers.gradleProperty("atlas.kindColours").orNull?.let { path ->
        val table = providers.fileContents(layout.projectDirectory.file(path)).asText
        val dir = layout.buildDirectory.dir("kind-colours")
        inputs.property("atlas.kindColours", table)
        classpath += files(dir)
        systemProperty("atlas.kindColours", path)
        doFirst { dir.get().file("atlas/places/kind_colours_trial.tsv").asFile.apply { parentFile.mkdirs() }.writeText(table.get()) }
    }
}
