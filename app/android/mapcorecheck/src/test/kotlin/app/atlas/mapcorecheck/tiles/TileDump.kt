package app.atlas.mapcorecheck.tiles

import app.atlas.rendertiles.CanonicalDump
import app.atlas.rendertiles.Format3Reader
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Schema
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * The tile reader's test entry points in the core's host build (`CP/jni/TestTiles.cpp`, plan-vector K3), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object TileHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 2

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's tile entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The layers and columns the core reads: `layer <id> <label>`, then `column <name> <codec>`; the keep ladder. */
    @JvmStatic external fun schema(): String

    /**
     * A pack as the core opens it: `format`, `section`, `chunk` and `reach` lines, rhead's (its views with their
     * tolerances, its bands) and style's lines, `verify`; or `error=...`.
     */
    @JvmStatic external fun pack(path: String): String

    /** The ladder index a frame loads the pack at: [view]'s when snapped onto one, else the band of [dist]; -1: no such view. */
    @JvmStatic external fun tolerance(path: String, view: String?, dist: Double): Int

    /** Tile z/x/y's canonical dump as the core decodes it (checked against its chunk and reach), or `error=...`. */
    @JvmStatic external fun dump(path: String, z: Int, x: Int, y: Int): String

    /** The SHA-256 of tile z/x/y's canonical dump, or `error=...`. */
    @JvmStatic external fun hash(path: String, z: Int, x: Int, y: Int): String

    /** Raw tile bytes (a direct buffer) as the core decodes them, or `error=...`. */
    @JvmStatic external fun decode(raw: ByteBuffer): String

    /** Where each column of raw tile bytes lies: `<layer> <column> <offset> <length>` lines. */
    @JvmStatic external fun columns(raw: ByteBuffer): String

    /** Every ring of [column] in layer [layer] of tile z/x/y at ladder [level]: per feature its count, then its kept indexes. */
    @JvmStatic external fun simplify(path: String, z: Int, x: Int, y: Int, layer: Int, column: String, level: Int): IntArray?

    /** Every column edge, then every row edge, of zoom [z] as the core computes them (2^z + 1 of each). */
    @JvmStatic external fun edges(z: Int): IntArray?

    /** Nanoseconds to decompress, check and decode tile z/x/y once, the best of [repeat]. */
    @JvmStatic external fun decodeNanos(path: String, z: Int, x: Int, y: Int, repeat: Int): Long
}

/** The bundled render packs :mapcorecheck's bundledTiles task made (`-Datlas.tiles`). */
internal object BundledPacks {
    val dir: File = File(System.getProperty("atlas.tiles") ?: error("atlas.tiles names no folder of bundled packs"))

    val files: List<File> by lazy {
        val packs = listOf("amsterdam", "grachten").map { File(dir, "$it.atlas") }
        for (f in packs) require(f.isFile) { "no bundled pack at $f (run :mapcorecheck:bundledTiles)" }
        packs
    }

    val readers: Map<File, Format3Reader> by lazy { files.associateWith { Format3Reader(it) } }
}

/** A tile's canonical text as the Kotlin reader decodes it. */
internal fun kotlinDump(raw: ByteArray): String = CanonicalDump.of(Format3Reader.decodeTile(raw))

internal fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun direct(bytes: ByteArray): ByteBuffer = ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()

/**
 * Where two canonical dumps of a tile first differ, naming the record and the field (a damaged byte's column is named
 * this way), or null when they are equal.
 */
internal fun firstDifference(core: String, kotlin: String): String? {
    if (core == kotlin) return null
    val a = core.split('\n')
    val b = kotlin.split('\n')
    val i = (0 until minOf(a.size, b.size)).firstOrNull { a[it] != b[it] } ?: minOf(a.size, b.size)
    val coreLine = a.getOrNull(i) ?: "<end>"
    val kotlinLine = b.getOrNull(i) ?: "<end>"
    if (coreLine.startsWith("error=")) return "the core refuses what Kotlin reads: $coreLine"
    val at = (0 until minOf(coreLine.length, kotlinLine.length)).firstOrNull { coreLine[it] != kotlinLine[it] }
        ?: minOf(coreLine.length, kotlinLine.length)
    // a field one side writes and the other leaves out (as its default) is where the lines part
    val inCore = fieldAt(coreLine, at)
    val inKotlin = fieldAt(kotlinLine, at)
    val field = if (inCore != null && inCore != inKotlin && !kotlinLine.contains(" ${inCore.substringAfter('.')}=")) inCore else inKotlin ?: inCore
    return "line ${i + 1} differs${field?.let { " in field $it" } ?: ""}:\n  core:   ${coreLine.take(300)}\n  kotlin: ${kotlinLine.take(300)}"
}

/** The `layer.field` of a feature line whose text position [at] falls in, from the layer's column names. */
private fun fieldAt(line: String, at: Int): String? {
    val label = line.substringBefore(" @", "")
    val layer = Layer.entries.firstOrNull { it.label == label } ?: return null
    val names = runCatching { Schema.columns(layer) }.getOrNull()?.map { it.name }?.filter { !it.endsWith(".keep") }?.sorted()
        ?: return null
    var found: String? = null
    var from = 0
    for (n in names) {
        val start = line.indexOf(" $n=", from)
        if (start < 0) continue
        if (start > at) break
        found = n
        from = start + 1
    }
    return found?.let { "${layer.label}.$it" }
}
