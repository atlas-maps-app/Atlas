// BRouter's routing core (https://github.com/abrensch/brouter, MIT, see LICENSE), vendored as source from commit 2989810
// (v1.7.10 + 13, 28 September 2026): the modules util, codec, expressions, mapaccess and core, merged. It is not on Maven Central.
// Atlas's additions live in btools.router.AtlasTrackAccess only, so an update is a copy of the five source folders.
plugins {
    `java-library`
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-Xlint:none")
}
