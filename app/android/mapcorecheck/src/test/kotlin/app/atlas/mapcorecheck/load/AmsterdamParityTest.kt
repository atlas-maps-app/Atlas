package app.atlas.mapcorecheck.load

import app.atlas.anchor.AnchorCases
import app.atlas.render.Amsterdam
import app.atlas.render.Generic
import app.atlas.render.PoiStyle
import app.atlas.render.Real
import app.atlas.render.Regions
import app.atlas.render.RenderOptions
import app.atlas.render.V2
import app.atlas.render.live.LiveEngine
import app.atlas.render.py.Dict
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
    const val ABI = 3

    init {
        val path = System.getProperty("atlas.mapcore.host") ?: error("atlas.mapcore.host names no host build of the core")
        require(File(path).isFile) { "no host build of the core at $path" }
        System.load(path)
        check(abi() == ABI) { "the host build's load entry points are version ${abi()}, these tests' $ABI" }
    }

    @JvmStatic external fun abi(): Int

    /**
     * The living map's load of [raw] written to [out] as canonical lines: `lines=… buildings=… … load_ms=…`, or `error=…`. [looks]: 0
     * none (as LiveEngine loads it), 1 Amsterdam's New looks (ATLAS_LOOKS), 2 its tradition and kit (ATLAS_KIT, with [tradition]'s file),
     * 3 generic.py's looks after the load, as [region]'s with [tradition]'s file (or none), the land use from the areas.
     */
    @JvmStatic external fun dump(raw: String, out: String, looks: Int, region: String?, tradition: ByteArray?): String

    /**
     * A region's load of [raw] at [tolerance] in the frame and box given (the port's after its configure), written to [out] as canonical
     * lines: `lines=… buildings=… land=… load_ms=…`, or `error=…`.
     */
    @JvmStatic external fun dumpRegion(
        raw: String, out: String, name: String, lat0: Double, lon0: Double, kx: Double, kz: Double,
        south: Double, west: Double, north: Double, east: Double, tradition: ByteArray?, tolerance: Double,
    ): String

    /** The bytes of tradition [tid]'s file, as the port's [Generic.tradition] reads them; null for none. */
    fun traditionBytes(tid: String?): ByteArray? {
        if (tid.isNullOrEmpty()) return null
        Generic.tradDir?.let { return File(it, "$tid.json").readBytes() }
        return (Generic::class.java.getResourceAsStream("/traditions/$tid.json") ?: error("no tradition $tid")).use { it.readBytes() }
    }

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
        SceneLines.compare(westerkerk.id, File(westerkerk.dir, "raw.json"), scene.S, (System.nanoTime() - t0) / 1_000_000) { out -> LoadHost.dump(File(westerkerk.dir, "raw.json").path, out, 0, null, null) }
    }

    /**
     * The living map with generic.py's looks, as amsterdam.py gives its own data them: Amsterdam's New looks (ATLAS_LOOKS: the homes
     * keep their brick and plaster, every other building its kind's look) and its tradition and kit (ATLAS_KIT: nl_amsterdam's walls,
     * gables, cornices, hoist beams and chimneys), the land use from the areas.
     */
    @Test
    fun theCoreGivesTheLivingMapItsLooksAsThePortDoes() {
        val westerkerk = AnchorCases.westerkerk()
        val raw = File(westerkerk.dir, "raw.json")
        val before = Amsterdam.amsRaw
        try {
            Amsterdam.amsRaw = raw
            for ((mode, name) in listOf(1 to "looks", 2 to "kit")) {
                Regions.resetPlace()
                val t0 = System.nanoTime()
                val S = Amsterdam.load(null, RenderOptions(looks = mode == 1, kit = mode == 2))
                val ms = (System.nanoTime() - t0) / 1_000_000
                SceneLines.compare("${westerkerk.id}-$name", raw, S, ms) { out ->
                    LoadHost.dump(raw.path, out, mode, null, if (mode == 2) LoadHost.traditionBytes("nl_amsterdam") else null)
                }
            }
        } finally {
            Amsterdam.amsRaw = before
            Regions.resetPlace()
        }
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
            SceneLines.compare(name, raw, S, (System.nanoTime() - t0) / 1_000_000) { out -> LoadHost.dump(raw.path, out, 0, null, null) }
        }
        println("load: ${raws.size} other raw.json held to the port")
    }

    /**
     * Each building tradition (render/traditions) and Paris's own looks over other data, as regions.py gives a region its looks after
     * the load: the Westerkerk's and the canal ring's raw.json, and every raw.json `-Datlas.load.raws` names, each loaded as the living
     * map, then given generic.py's looks as Paris's (without a tradition: its stone tones and mansards), Paris's, San Gimignano's,
     * Bergen's, Notting Hill's and Amsterdam's tradition's, the land use from the areas, on both sides. The regions' own data only
     * meets its own tradition (and San Gimignano's waits for the terrain): here every rule of every tradition meets every place.
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun theCoreGivesOtherDataEachTraditionsLooksAsThePortDoes() {
        val raws = ArrayList<Pair<String, File>>()
        raws += "amsterdam" to File(AnchorCases.westerkerk().dir, "raw.json")
        AnchorCases.grachten()?.let { raws += it.id to File(it.dir, "raw.json") }
        System.getProperty("atlas.load.raws")?.split(File.pathSeparatorChar)?.filter { it.isNotBlank() }?.forEach {
            val f = File(it)
            assertTrue("atlas.load.raws names $it, which is not a file", f.isFile)
            raws += (f.parentFile?.name ?: f.name) to f
        }
        val looks = listOf("paris" to null, "paris" to "fr_paris", "sangimignano" to "it_tuscan", "bergen" to "no_timber", "nottinghill" to "gb_terrace", "ams" to "nl_amsterdam")
        for ((name, raw) in raws) {
            for ((region, tid) in looks) {
                Regions.resetPlace()
                val t0 = System.nanoTime()
                val S = Amsterdam.load(raw)
                S["tradition"] = tid
                val areas = (S["areas"] as List<Map<String, Any?>>).filter {
                    val t = it["tags"] as Map<String, Any?>?
                    t != null && t.isNotEmpty() && (t["landuse"] as? String)?.let { k -> k in Generic.LANDUSE_KIND } == true && (it["poly"] as List<*>).size > 2
                }.map { (it["tags"] as Map<String, Any?>)["landuse"] as String to (it["poly"] as List<V2>) }
                val landuseAt: (Dict) -> String? = { b ->
                    val c = Real.centroid(b["poly"] as List<V2>)
                    areas.firstOrNull { (_, poly) -> Real.pip(c.x, c.y, poly) }?.first
                }
                Generic.apply(S, region, landuseAt)
                val ms = (System.nanoTime() - t0) / 1_000_000
                SceneLines.compare("$name-${tid ?: region}", raw, S, ms) { out -> LoadHost.dump(raw.path, out, 3, region, LoadHost.traditionBytes(tid)) }
            }
        }
        println("load: ${raws.size} raw.json given ${looks.size} looks each, held to the port")
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
