package app.atlas.mapcorecheck.load

import java.io.File

/**
 * Stage P's cut's test entry point in the core's host build (`CP/jni/TestLoad.cpp`), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object LoadHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 6

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's load entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /**
     * A region's tiles cut by the core from [raw] (its box from [regionJson], its frame and its variants' tolerances from the render
     * pack [pack], loaded whole and at each of them), beside [pack]'s tiles (the port's): `tiles=… cut=… missing=… differ=… bytes=…
     * variants=…`, the loading's counts of its buildings (`pairs=… uncovered=… flat=…` and what the cleaning did) and
     * `layers=<layer>:<lines not the pack's>/<lines>,…`; or `error=…`.
     */
    @JvmStatic external fun cutRegion(raw: String, regionJson: String, pack: String, name: String): String

    /**
     * The tiles of the render pack [a] beside those of [b], layer by layer of their canonical texts: `tiles=… missing=… extra=…
     * layers=<layer>:<lines that differ>/<lines>,…`; or `error=…`.
     */
    @JvmStatic external fun comparePacks(a: String, b: String): String

    /**
     * The layers the buildings' cleaning touches (`CP/load/Clean.hpp`, core-p14): the buildings and parts and their variants, and
     * what stage P derives from their rings, the things on their walls (a door's and a shopfront's wall) and the quays they line.
     * Every other layer of the core's cut is still the port's, line for line, until cut 2 makes the test packs with the core.
     */
    val TOUCHED = setOf("building", "part", "variant", "thing", "quay")

    /** The layers of a summary's `layers=` whose lines differ, each `<layer>:<differ>/<lines>`, but the [TOUCHED] ones. */
    fun untouchedThatDiffer(summary: String): List<String> {
        val layers = summary.split(' ').firstOrNull { it.startsWith("layers=") }?.removePrefix("layers=") ?: error("no layers in: $summary")
        if (layers == "none") return emptyList()
        return layers.split(',').filter { l ->
            val name = l.substringBefore(':')
            val differ = l.substringAfter(':').substringBefore('/').toLong()
            name !in TOUCHED && differ != 0L
        }
    }
}
