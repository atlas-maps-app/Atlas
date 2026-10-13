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
// Pure Kotlin, tested on the JVM: geometry and the shared model; tiles; the map engine; OSM knowledge; search; routing; the guide; providers.
include(":core", ":tiles", ":engine", ":places", ":search", ":route", ":guide", ":providers")
// Android library: the map view that draws the engine's frames on a Canvas.
include(":mapview")
// BRouter, the offline router, vendored as Java source (MIT).
include(":brouter")
