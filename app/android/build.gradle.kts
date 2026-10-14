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
