package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.V2
import app.atlas.render.live.LiveRegion
import app.atlas.render.live.LiveScene
import app.atlas.render.py.Dict
import app.atlas.render.py.truthy
import app.atlas.rendertiles.compile.KeepCodes
import app.atlas.rendertiles.model.Codec
import app.atlas.rendertiles.model.Feature
import app.atlas.rendertiles.model.Geom
import app.atlas.rendertiles.model.Grid
import app.atlas.rendertiles.model.Keep
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.RenderHead
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * L0 (a), the package's acceptance (plan-vector D16): every building and part of both bundled scenes, decoded from its
 * render pack, equals the scene's own record field by field; every ring rebuilt from its keep codes at every tolerance
 * of the ladder equals `regions.py`'s `simplify`; and the canal ring's tiles give the scene of each of its views (the
 * Main board's `grachten_home` at 0.7 m among them) exactly, its rings and every derived value.
 *
 * The comparisons read the scene's dicts directly, not through the packer's code.
 */
class L0aTest {
    /** The decoded buildings or parts of a pack, by source index, with their tolerance variants. */
    private class Decoded(val records: Map<Int, Feature>, val variants: Map<Pair<Int, Int>, Feature>)

    private fun decoded(b: Packs.Built, layer: Layer): Decoded {
        val records = HashMap<Int, Feature>()
        val variants = HashMap<Pair<Int, Int>, Feature>()
        for (t in b.reader.tiles()) {
            if (t.level != 14) continue
            for (f in t.layers[layer].orEmpty()) {
                val i = ((f["index"] as Long?) ?: 0L).toInt()
                assertTrue("${layer.label} $i twice", records.put(i, f) == null)
            }
            for (v in t.layers[Layer.VARIANT].orEmpty()) {
                if ((v["layer"] as Int?) != layer.id) continue
                val key = ((v["index"] as Long?) ?: 0L).toInt() to (v["tolerance"] as Int)
                assertTrue("variant $key twice", variants.put(key, v) == null)
            }
        }
        return Decoded(records, variants)
    }

    /** A decoded ring in the pack's frame's metres, as the core computes them. */
    private fun metres(g: Geom, head: RenderHead): List<V2> = (0 until g.size).map { V2(Grid.x(g.lon[it], head.lon0, head.kx), Grid.z(g.lat[it], head.lat0, head.kz)) }

    @Suppress("UNCHECKED_CAST")
    private fun pointsOf(d: Map<String, Any?>): List<V2> = d["poly"] as List<V2>

    /** Python's ints are one type: an Int and a Long of the same value are the same field value. */
    private fun int(v: Any?): Long? = (v as Number?)?.toLong()

    /**
     * The scene's record [b] against the decoded attributes [f] (a record, or a variant), every field: the schema's
     * columns, the tags the renders read, guessed, places, and every other field in `extra`. Returns the differences.
     */
    private fun differences(layer: Layer, b: Dict, f: Feature, edges: List<Amsterdam.Edge>): List<String> {
        val out = ArrayList<String>()
        val cols = Schema.BUILDING_ATTRIBUTES.associateBy { it.name }
        val extra = LinkedHashMap<String, Any?>()
        @Suppress("UNCHECKED_CAST")
        val tags = (b["tags"] as Map<String, Any?>?) ?: emptyMap()
        val handled = HashSet<String>()
        for ((k, v) in b) {
            when (k) {
                "poly", "id", "_bb", "tags" -> {}
                "guessed" -> {
                    @Suppress("UNCHECKED_CAST")
                    val g = v as Map<String, Any?>
                    if (g.values.all { it == true }) {
                        handled += k
                        if (f["guessed"] != g.keys.toList()) out += "guessed ${g.keys} != ${f["guessed"]}"
                    } else extra[k] = v
                }
                "places" -> {
                    handled += k; handled += "place_hours"
                    @Suppress("UNCHECKED_CAST")
                    val ps = v as List<Dict>
                    if (f["places"] != ps.map { int(it["id"]) }) out += "places"
                    @Suppress("UNCHECKED_CAST")
                    val hours = ps.map { ((it["tags"] as Map<String, Any?>)["opening_hours"] as String?) ?: "" }
                    if (f["place_hours"] != hours) out += "place_hours"
                }
                else -> {
                    val col = cols[k]
                    val fits = col != null && when (col.codec) {
                        Codec.CENTI -> v == null || v is Double
                        Codec.NVARZ -> v == null || v is Int || v is Long
                        Codec.STR -> v == null || v is String
                        Codec.BOOL -> v is Boolean
                        else -> false
                    }
                    if (!fits) { extra[k] = v; continue }
                    handled += k
                    val got = f[k]
                    val same = when (col!!.codec) {
                        Codec.NVARZ -> int(v) == int(got)
                        Codec.CENTI -> (v as Double?)?.toRawBits() == (got as Double?)?.toRawBits()
                        else -> v == got
                    }
                    if (!same) out += "$k: $v != $got"
                }
            }
        }
        for (key in Schema.RENDER_TAGS.getValue(layer)) if (tags[key] != f[Schema.tag(key)]) out += "tag $key: ${tags[key]} != ${f[Schema.tag(key)]}"
        if ((f["extra"] ?: emptyMap<String, Any?>()) != extra) out += "extra: $extra != ${f["extra"]}"
        // nothing decoded that the scene does not hold
        for (k in f.fields.keys) {
            if (k in setOf("index", "id", "ring", "flags", "extra", "layer", "tolerance") || k.startsWith("tag:")) continue
            if (k !in handled) out += "decoded $k=${f[k]}, which the scene does not have"
        }
        // the derived flags, by the renders' own tests
        val btype = tags["building"] as String?
        @Suppress("UNCHECKED_CAST")
        val places = (b["places"] as List<Any?>?) ?: emptyList()
        var flags = 0L
        if (btype in setOf("church", "cathedral", "chapel") || tags["building:part"] == "church" || truthy(b["in_church"])) flags = flags or 1
        if (btype in setOf("industrial", "warehouse", "service")) flags = flags or 2
        if (places.isNotEmpty() || btype in setOf("retail", "commercial")) flags = flags or 4
        if (btype == "construction") flags = flags or 8
        if (Amsterdam.nearWater(edges, pointsOf(b), 16.0)) flags = flags or 16
        if (btype == "houseboat") flags = flags or 32
        if (((b["year"] as Number?)?.toInt() ?: 0) >= 1960 && btype !in setOf("church", "cathedral", "chapel")) flags = flags or 64
        if (flags != ((f["flags"] as Long?) ?: 0L)) out += "flags ${f["flags"]} != $flags"
        return out
    }

    /** Every building and part of [scene] against the decoded records of [b], at tolerance 0. */
    private fun checkScene(name: String, b: Packs.Built, scene: LiveScene) {
        scene.state.restore()
        val edges = Amsterdam.waterEdges(scene.S)
        val head = b.reader.head
        var n = 0
        val failures = ArrayList<String>()
        for ((layer, key) in listOf(Layer.BUILDING to "buildings", Layer.PART to "parts")) {
            @Suppress("UNCHECKED_CAST")
            val list = scene.S[key] as List<Dict>
            val dec = decoded(b, layer)
            assertEquals("$name: ${layer.label} records", list.size, dec.records.size)
            for ((i, rec) in list.withIndex()) {
                val f = dec.records[i] ?: run { failures += "$name ${layer.label} $i missing"; null } ?: continue
                if (int(f["id"]) != int(rec["id"])) failures += "$name ${layer.label} $i: id ${f["id"]} != ${rec["id"]}"
                val ring = f["ring"] as Geom
                if (metres(ring, head) != pointsOf(rec)) failures += "$name ${layer.label} $i: the ring differs"
                // _bb: the box of the tolerance-0 ring, which a reader derives from the ring
                val bb = rec["_bb"] as DoubleArray?
                if (bb != null) {
                    val p = metres(ring, head)
                    val box = doubleArrayOf(p.minOf { it.x }, p.maxOf { it.x }, p.minOf { it.y }, p.maxOf { it.y })
                    if (!box.contentEquals(bb)) failures += "$name ${layer.label} $i: _bb ${bb.toList()} is not the ring's box ${box.toList()}"
                }
                for (d in differences(layer, rec, f, edges)) failures += "$name ${layer.label} $i (${rec["id"]}): $d"
                n++
            }
        }
        println("L0A $name: $n buildings and parts equal their scene records field by field; ${failures.size} differences")
        assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun `the Westerkerk's buildings and parts equal its scene field by field`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        val b = Packs.build(Packs.westerkerkPack, "westerkerk")
        val scene = Packs.scene(Packs.region(Packs.westerkerkPack))
        checkScene("westerkerk", b, scene)
    }

    @Test
    fun `the canal ring's buildings and parts equal its scene field by field`() {
        assumeTrue("the canal ring's pack is kept in the repo's cache", Packs.grachtenPack.isFile)
        val b = Packs.build(Packs.grachtenPack, "grachten")
        val scene = Packs.scene(Packs.region(Packs.grachtenPack))
        checkScene("grachten", b, scene)
    }

    /**
     * Each of the canal ring's views (the Main board's `grachten_home` at 0.7 m, `grachten` at 0.8, `grachten_eat` at
     * 0.6) loaded as its renders load it equals the tiles at that view's tolerance: every ring simplified by its keep
     * codes, every attribute with the tolerance's variants applied.
     */
    @Test
    fun `the canal ring's views are their tiles at their tolerance`() {
        assumeTrue("the canal ring's pack is kept in the repo's cache", Packs.grachtenPack.isFile)
        val b = Packs.build(Packs.grachtenPack, "grachten")
        val region = Packs.region(Packs.grachtenPack)
        val head = b.reader.head
        val decoded = listOf(Layer.BUILDING, Layer.PART).associateWith { decoded(b, it) }
        for (view in region.ladder.knots.map { it.name }) {
            val scene = Packs.scene(region, view)
            val level = Keep.indexOf(scene.tolerance)
            assertTrue("$view loads at ${scene.tolerance}, which is on the ladder", level > 0)
            scene.state.restore()
            val edges = Amsterdam.waterEdges(scene.S)
            val failures = ArrayList<String>()
            var n = 0
            var varied = 0
            for ((layer, key) in listOf(Layer.BUILDING to "buildings", Layer.PART to "parts")) {
                @Suppress("UNCHECKED_CAST")
                val list = scene.S[key] as List<Dict>
                val dec = decoded.getValue(layer)
                for ((i, rec) in list.withIndex()) {
                    val f = dec.records.getValue(i)
                    val ring = f["ring"] as Geom
                    if (KeepCodes.simplify(metres(ring, head), ring.keep!!, level) != pointsOf(rec)) failures += "$view ${layer.label} $i: the ring at ${scene.tolerance} differs"
                    val v = dec.variants[i to level]
                    if (v != null) varied++
                    val attrs = v ?: f
                    for (d in differences(layer, rec, attrs, edges)) failures += "$view ${layer.label} $i (${rec["id"]}): $d"
                    n++
                }
            }
            println("L0A $view (${scene.tolerance} m): $n buildings and parts equal the tiles at their tolerance ($varied by a variant); ${failures.size} differences")
            assertTrue(failures.take(20).joinToString("\n"), failures.isEmpty())
        }
    }

    /** Every ring with keep codes (buildings, parts, areas) rebuilt at every positive ladder tolerance equals `simplify`. */
    @Test
    fun `every ring rebuilt at every ladder tolerance equals simplify`() {
        for ((name, file) in listOf("westerkerk" to Packs.westerkerkPack, "grachten" to Packs.grachtenPack)) {
            if (!file.isFile) continue
            val b = Packs.build(file, name)
            val head = b.reader.head
            var rings = 0
            var checks = 0
            var simplified = 0
            for (t in b.reader.tiles()) {
                if (t.level != 14) continue
                for (layer in listOf(Layer.BUILDING, Layer.PART, Layer.GROUND)) for (f in t.layers[layer].orEmpty()) {
                    if (layer == Layer.GROUND && ((f["source"] as Int?) ?: 0) != 0) continue // land cover is clipped, never simplified by the renders
                    val g = f["ring"] as Geom
                    val pts = metres(g, head)
                    for (i in 1 until Keep.TOLERANCES.size) {
                        val want = Regions.simplify(pts, Keep.TOLERANCES[i])
                        val got = KeepCodes.simplify(pts, g.keep!!, i)
                        assertEquals("$name ${layer.label} ${f["index"]} at ${Keep.TOLERANCES[i]} m", want, got)
                        if (want.size != pts.size) simplified++
                        checks++
                    }
                    rings++
                }
            }
            println("L0A $name: $rings rings at ${Keep.TOLERANCES.size - 1} tolerances, $checks rebuilt equal to simplify ($simplified simplified)")
        }
    }
}
