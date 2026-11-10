// Atlas, the Android app: the :app module and the libraries it is built from (see README.md for the map of modules).
// AGP 9 compiles Kotlin itself in Android modules (built-in Kotlin); the pure Kotlin modules use the Kotlin JVM plugin.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.roborazzi) apply false
}

// A builder working in parallel with others passes -Pbuilder=<name> (and --project-cache-dir .gradle-builders/<name>)
// to get build folders of its own, so several builds can run in one tree at once.
providers.gradleProperty("builder").orNull?.let { builder ->
    allprojects { layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build-builders/$builder/${project.path.trim(':').replace(':', '-')}")) }
}

// Every test JVM keeps its temp files in its task's own build folder (build/tmp/<task>/jvm, under build-builders/<name>
// for a builder), emptied before the task runs and removed after it. Java on macOS ignores TMPDIR, so the tests wrote
// to /var/folders on the main disk, which is nearly full: Robolectric holds 200-400 MB there while a run lasts, and on
// 10 November the kit board tests failed there with "No space left on device" (the owner's yes, through the Steward).
// A task that fails keeps its folder until its next run empties it; rendertiles' -Patlas.testTmp still wins.
subprojects {
    tasks.withType<Test>().configureEach {
        val tmp = temporaryDir.resolve("jvm")
        systemProperty("java.io.tmpdir", tmp.absolutePath)
        doFirst { tmp.deleteRecursively(); tmp.mkdirs() }
        doLast { tmp.deleteRecursively() }
    }
}
