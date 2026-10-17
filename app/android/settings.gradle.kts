pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Atlas"

include(":app")
// Pure Kotlin, tested on the JVM: geometry and the shared model; tiles; OSM knowledge; search; routing; the guide; providers.
include(":core", ":tiles", ":places", ":search", ":route", ":guide", ":providers")
// The map engine, ported 1:1 from the Python renderer (render/ in the repo), with its region packs.
include(":render")
// Pure Kotlin: the GPU's anchors (engine renders lifted into world space) and the SVG model they are read into.
include(":anchor")
// Android library: the map view that draws the engine's frames on a Canvas.
include(":mapview")
// BRouter, the offline router, vendored as Java source (MIT).
include(":brouter")
// The pack builder: OSM PBF to the place store and the packs manifest, run by .github/workflows/packs.yml.
include(":packer")
// Pure Kotlin: render tiles (plan-vector K2), stage P of the vector engine, written by the packer and Gradle.
include(":rendertiles")
// Android library: the vector engine's core (plan-vector), C++23 built by CMake into libatlasmap.so with pinned tools.
include(":mapcore")
// Tests only, on the JVM: the core's host build through JNI, held to the Kotlin side (the tile reader to the writer).
include(":mapcorecheck")
// Tests only, on Robolectric's Skia: the likeness harness's oracle, the port's exact references the core is judged by.
include(":likeness")
