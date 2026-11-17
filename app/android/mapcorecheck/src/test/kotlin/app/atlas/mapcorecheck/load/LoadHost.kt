package app.atlas.mapcorecheck.load

import java.io.File

/**
 * Stage P's cut's test entry point in the core's host build (`CP/jni/TestLoad.cpp`), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object LoadHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 5

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's load entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /**
     * A region's tiles cut by the core from [raw] (its box from [regionJson], its frame and its variants' tolerances from the render
     * pack [pack], loaded whole and at each of them), held to [pack]'s tiles byte for byte: `tiles=… cut=… wrong=… bytes=… variants=…`
     * and, where a tile differs, ` first=<z/x/y> <the first line of their canonical dumps that does>`; or `error=…`.
     */
    @JvmStatic external fun cutRegion(raw: String, regionJson: String, pack: String, name: String): String
}
