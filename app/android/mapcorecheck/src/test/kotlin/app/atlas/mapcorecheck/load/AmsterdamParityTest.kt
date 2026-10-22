package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import app.atlas.render.Amsterdam
import app.atlas.render.PoiStyle
import app.atlas.render.Regions
import app.atlas.render.live.LiveEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Stage P's loaders' test entry points in the core's host build (`CP/jni/TestLoad.cpp`), loaded from
 * `-Datlas.mapcore.host` (the library :mapcorecheck's coreHost task builds).
 */
internal object LoadHost {
    /** The layout version of these entry points; a library of another is refused. */
    const val ABI = 2

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's load entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /** The living map's load of [raw] written to [out] as canonical lines: `lines=… buildings=… … load_ms=…`, or `error=…`. */
    @JvmStatic external fun dump(raw: String, out: String): String

    /**
     * A region's load of [raw] at [tolerance] in the frame and box given (the port's after its configure), written to [out] as canonical
     * lines: `lines=… buildings=… land=… load_ms=…`, or `error=…`.
     */
    @JvmStatic external fun dumpRegion(
        raw: String, out: String, name: String, lat0: Double, lon0: Double, kx: Double, kz: Double,
        south: Double, west: Double, north: Double, east: Double, tradition: String?, tolerance: Double,
    ): String

    /** The core's capitalize() of each UTF-8 text of [bytes] ([lengths] bytes each): each a u32 length and its bytes. */
    @JvmStatic external fun capitalize(bytes: ByteArray, lengths: IntArray): ByteArray
}

/**
 * Stage P held to the port (ENGINE.md step 3): the living map's load in the core (`CP/load/Amsterdam.hpp`) gives every
 * record and field the port's `Amsterdam.load` gives (with guess_tags, the places' styles and buildings, and `hidden`),
 * to the bit, in the same order. Both sides write the scene as TestLoad.cpp's canonical lines, compared one by one.
 *
 * The Westerkerk's scene is the port's as the packer loads the living map ([LiveEngine.loadScene]); the canal ring's
 * raw.json, and every raw.json `-Datlas.load.raws` names (a list split by the path separator: the renderer's
 * data/regions on this Mac), go through `Amsterdam.load` itself, in the Westerkerk's frame: data the living map's rules
 * never met, from Paris, New York and Bergen among them.
 */
class AmsterdamParityTest {
    @Test
    fun theCoreLoadsTheLivingMapAsThePortDoes() {
        val westerkerk = AnchorCases.westerkerk()
        val t0 = System.nanoTime()
        val scene = LiveEngine.loadScene(westerkerk)
        SceneLines.compare(westerkerk.id, File(westerkerk.dir, "raw.json"), scene.S, (System.nanoTime() - t0) / 1_000_000) { out -> LoadHost.dump(File(westerkerk.dir, "raw.json").path, out) }
    }

    @Test
    fun theCoreLoadsOtherDataAsThePortDoes() {
        val raws = ArrayList<Pair<String, File>>()
        AnchorCases.grachten()?.let { raws += it.id to File(it.dir, "raw.json") }
        System.getProperty("atlas.load.raws")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach {
            val f = File(it)
            assertTrue("atlas.load.raws names $it, which is not a file", f.isFile)
            raws += (f.parentFile?.name ?: f.name) to f
        }
        for ((name, raw) in raws) {
            Regions.resetPlace()
            val t0 = System.nanoTime()
            val S = Amsterdam.load(raw)
            SceneLines.compare(name, raw, S, (System.nanoTime() - t0) / 1_000_000) { out -> LoadHost.dump(raw.path, out) }
        }
        println("load: ${raws.size} other raw.json held to the port")
    }

    /**
     * The core's capitalize() (`CP/text/Case.hpp`) against the port's own (PoiStyle.capitalize, which labels a shop the
     * tables do not name) on every code point: first, between two letters, after two, and alone; and on texts that
     * end words with a capital sigma, which Java lowercases by its word breaks.
     */
    @Test
    fun theCoreCapitalizesAsThePortDoes() {
        val port = PoiStyle::class.java.getDeclaredMethod("capitalize", String::class.java).apply { isAccessible = true }
        val texts = ArrayList<String>()
        for (c in 0..0x10FFFF) {
            if (c in 0xD800..0xDFFF) continue
            val s = String(Character.toChars(c))
            texts += s + "AB"
            texts += "x" + s + "y"
            texts += "xx$s"
            texts += s
        }
        texts += listOf(
            "", "ΟΔΥΣΣΕΥΣ", "ΑΣ", "ΑΣ ΒΑ", "ΑΣ.", "Σ", "ΣΣ", "ΑΣΑ", "ΑΑΣ", "ἈΣ", "İSTANBUL", "ßtraße", "ǆemal", "ǅ",
            "3D_printing_materials".replace('_', ' '), "Plumbing & Heating", "ﬀ", "ŉ", "ᾳ", "Ⓐⓑ", "ⅰⅱ", "𐐀𐐨", "ΣΑΣ ΣΑΣ",
        )
        val bytes = java.io.ByteArrayOutputStream()
        val lengths = IntArray(texts.size)
        for ((i, s) in texts.withIndex()) {
            val b = s.toByteArray(Charsets.UTF_8)
            lengths[i] = b.size
            bytes.write(b)
        }
        val out = LoadHost.capitalize(bytes.toByteArray(), lengths)
        var at = 0
        val wrong = ArrayList<String>()
        for (s in texts) {
            var n = 0
            for (k in 0 until 4) n = n or ((out[at + k].toInt() and 0xFF) shl (8 * k))
            val core = String(out, at + 4, n, Charsets.UTF_8)
            at += 4 + n
            val want = port.invoke(PoiStyle, s) as String
            if (core != want && wrong.size < 20) wrong += "${s.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }}: core $core, the port $want"
        }
        assertEquals("every text read back", out.size, at)
        assertTrue("capitalize differs from the port's on:\n  ${wrong.joinToString("\n  ")}", wrong.isEmpty())
    }

}
