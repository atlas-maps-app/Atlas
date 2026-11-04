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

// Robolectric's Android (libs.robolectric.android.all) as a dependency Gradle fetches and caches like any other, handed
// to Robolectric to use offline: Robolectric fetched it from Maven Central itself, once a test started, with no second
// try (an IOException failed CI's likeness job on 4 November, run 41757855839), into ~/.m2, which no cache keeps. The
// test tasks take the jar as an input by its content.
class RobolectricAndroidAll(@get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val jar: FileCollection) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-Drobolectric.offline=true", "-Drobolectric.dependency.dir=${jar.singleFile.parentFile.absolutePath}")
}

val robolectricAndroidAll = libs.robolectric.android.all
// The configurations testDependencies fetches: the Android modules' debug unit tests' (the variant CI tests), the JVM
// modules' tests', their Kotlin compiler plugins' and Robolectric's Android.
val testedConfigurations = Regex("^(debugUnitTest|test)[A-Z].*Classpath$|^kotlinCompilerPluginClasspath(DebugUnitTest|Test)$|^robolectricAndroidAll$")
subprojects {
    afterEvaluate {
        if (configurations.findByName("testImplementation")?.dependencies?.any { it.group == "org.robolectric" } != true) return@afterEvaluate
        val androidAll = configurations.create("robolectricAndroidAll") {
            isCanBeConsumed = false
            isTransitive = false
        }
        dependencies.add(androidAll.name, robolectricAndroidAll)
        tasks.withType<Test>().configureEach { jvmArgumentProviders.add(RobolectricAndroidAll(androidAll)) }
    }

    // The external artifacts the module's tests are built and run with, fetched into Gradle's caches: CI's apk job runs
    // it, so that the entry it keeps holds them for the test jobs, which restore apk's entries (on 4 November Maven Central
    // answered unit-app's fetch of a Robolectric artifact with 429 Too Many Requests, run 41771160319, when apk's entry
    // held only what the APKs are built from). A fetch that fails after Gradle's own tries is said, not failed: the APKs
    // still go to the emulator, and a test job that needs the artifact fetches it itself.
    tasks.register("testDependencies") {
        description = "Fetches the external artifacts this module's tests are built and run with into Gradle's caches."
        val artifacts = configurations.filter { it.isCanBeResolved && testedConfigurations.matches(it.name) }.map { configuration ->
            configuration.name to configuration.incoming.artifactView {
                componentFilter { it is ModuleComponentIdentifier }
                lenient(true)
            }.artifacts
        }
        artifacts.forEach { (name, collection) -> inputs.files(collection.artifactFiles).withPropertyName(name) }
        doLast {
            val failures = artifacts.flatMap { (_, collection) -> collection.failures }
            failures.forEach { println("::warning::$path could not fetch ${it.message?.lineSequence()?.first()}") }
            println("$path: ${artifacts.flatMap { (_, collection) -> collection.artifactFiles.files }.toSet().size} artifacts, ${failures.size} not fetched")
        }
    }
}
