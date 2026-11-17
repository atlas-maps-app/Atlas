package app.atlas.mapcorecheck.frame

import java.io.File
import java.nio.ByteBuffer

/**
 * The frame core's entry points in the core's host build (`CP/jni/TestTwin.cpp`, plan-vector K7), loaded from
 * `-Datlas.mapcore.host`.
 */
internal object FrameHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 1

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's frame entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The pack's ladder: a `frame` line, then a `knot` line per knot in the ladder's order (doubles as their bits). */
    @JvmStatic external fun ladder(path: String): String

    /**
     * The frame's view of a camera state over the pack at [path] with a theme block (or none): the view it snapped onto,
     * its distance, ViewLadder.place's settings, the engine camera's values and ScreenLayers.of's, in [Canon]'s text.
     */
    @JvmStatic external fun place(path: String, camera: ByteBuffer, theme: ByteBuffer?): String

    /** Device px of world points (x, y, z triples): per point the double camera's x, y, then the vertex stage's x, y. */
    @JvmStatic external fun project(path: String, camera: ByteBuffer, points: DoubleArray): DoubleArray?

    /** The snap at rest of each value: the double's, then the float's (widened). */
    @JvmStatic external fun snap(values: DoubleArray): DoubleArray

    /** The SVG's print precision of each value at [places] decimals (view::decimals). */
    @JvmStatic external fun decimals(values: DoubleArray, places: Int): DoubleArray

    /** The twin's stream of the frame over render packs (`id=path`) at a camera state and a theme block; null on a failure. */
    @JvmStatic external fun twin(packs: Array<String>, active: String?, camera: ByteBuffer, theme: ByteBuffer, full: Boolean): ByteArray?

    /**
     * The frame's counts over render packs (`id=path`) at a camera state and a theme block: `pass`, `kind` and `total`
     * lines, then `fse opaque <x> translucent <x>` from the overdraw mode; null on a failure.
     */
    @JvmStatic external fun stats(packs: Array<String>, active: String?, camera: ByteBuffer, theme: ByteBuffer, full: Boolean): String?

    /** Why the last frame drawn on this thread failed, and what it noted. */
    @JvmStatic external fun lastError(): String

    @JvmStatic external fun lastNotes(): String
}
