package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.ViewLadder
import app.atlas.render.py.Dict
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.TreeMap
import java.util.TreeSet

/**
 * What the tiles carry is what the renders read when they draw: the boards' views rendered with every record and every
 * tag map recording what is read (the Westerkerk at its four moments from above, close and at eye level; the Main board
 * at sunny and wet, which is night), each render's output first checked equal to the same render unrecorded.
 *
 * - **Tags**: a key read and not carried ([Schema.RENDER_TAGS]), or a record's tags read whole, fails.
 * - **Fields**: a field a record held when it was loaded, read while drawing, that its layer neither carries as a
 *   column nor derives from ([READ_THROUGH]) fails, as does a read of a field [Schema.DROPPED] gives as never read.
 *
 * **Its limits**: it sees what these renders reach: the two bundled regions (another region's runways, terrain towns,
 * coasters or `real.py` are not drawn), the code paths these cameras and moments take, and conditions true for some
 * feature in view. A region K20a packs needs its own views here. The packer's own rule backs it: a field a record holds
 * with no column and no entry in [Schema.DROPPED] fails the build.
 */
class RenderTagsTest {
    private val seen = TreeMap<String, TreeSet<String>>()
    private val fields = TreeMap<String, TreeSet<String>>()
    private val whole = TreeSet<String>()

    private inner class Recording(val kind: String, val inner: Map<String, Any?>) : Map<String, Any?> by inner {
        private fun note(k: String) { seen.getOrPut(kind) { TreeSet() }.add(k) }
        override fun get(key: String): Any? { note(key); return inner[key] }
        override fun containsKey(key: String): Boolean { note(key); return inner.containsKey(key) }
        override val entries: Set<Map.Entry<String, Any?>> get() { note("*"); return inner.entries }
        override val keys: Set<String> get() { note("*"); return inner.keys }
        override val values: Collection<Any?> get() { note("*"); return inner.values }
    }

    /** Each record's fields as the scene was loaded, before any render wrote its own (a building's shadow box `bb`, `_rf`). */
    private val atLoad = IdentityHashMap<Any, Set<String>>()

    private fun keysAtLoad(S: Dict) {
        for (v in S.values) if (v is List<*>) for (o in v) if (o is Map<*, *>) atLoad.getOrPut(o) { o.keys.map { it as String }.toSet() }
    }

    /** A record that notes every field it held when loaded and is read while drawing; its own map, so the port can write to it. */
    private inner class RecordingDict(val kind: String, inner: Map<String, Any?>) : LinkedHashMap<String, Any?>(inner) {
        private val loaded: Set<String> = atLoad[inner] ?: HashSet(inner.keys)
        var on = false
        private fun note(k: Any?) { if (on && k is String && k in loaded) fields.getOrPut(kind) { TreeSet() }.add(k) }
        override fun get(key: String): Any? { note(key); return super.get(key) }
        override fun containsKey(key: String): Boolean { note(key); return super.containsKey(key) }
        override fun getOrDefault(key: String, defaultValue: Any?): Any? { note(key); return super.getOrDefault(key, defaultValue) }
        override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>> get() { if (on) whole += kind; return super.entries }
        override val keys: MutableSet<String> get() { if (on) whole += kind; return super.keys }
        override val values: MutableCollection<Any?> get() { if (on) whole += kind; return super.values }
    }

    @Suppress("UNCHECKED_CAST")
    private fun recordTags(S: Dict) {
        for ((k, v) in S) {
            if (v !is List<*>) continue
            for (o in v) if (o is MutableMap<*, *>) {
                val m = o as MutableMap<String, Any?>
                val t = m["tags"] as? Map<String, Any?> ?: continue
                if (t !is Recording) m["tags"] = Recording(k, t)
            }
        }
    }

    /**
     * Every record of [S]'s lists replaced by a [RecordingDict] (one per record, so what holds the same record still
     * does: a node in `nodes`, `pois` and `trees`, a building's places, a node's `_b`). The kind is the first list that
     * holds it, in [ORDER] then the rest.
     */
    @Suppress("UNCHECKED_CAST")
    private fun recordFields(S: Dict): List<RecordingDict> {
        val memo = IdentityHashMap<Any, RecordingDict>()
        val lists = (ORDER.filter { S[it] is List<*> } + S.keys.filter { it !in ORDER && S[it] is List<*> }.sorted())
        for (k in lists) for (o in S[k] as List<*>) if (o is MutableMap<*, *> && o !is RecordingDict) memo.getOrPut(o) { RecordingDict(k, o as Map<String, Any?>) }
        fun swap(v: Any?): Any? = when {
            v == null -> null
            memo.containsKey(v) -> memo[v]
            v is List<*> && v.any { it != null && memo.containsKey(it) } -> v.map { e -> if (e != null && memo.containsKey(e)) memo[e] else e }
            else -> v
        }
        for (k in lists) S[k] = (S[k] as List<*>).map { swap(it) }.toMutableList()
        for (r in memo.values) for (e in r.entries.toList()) { val w = swap(e.value); if (w !== e.value) r[e.key] = w }
        return memo.values.toList()
    }

    /** What a scene list's records draw from, by layer: its columns' fields, its geometry, and what the packer derives from. */
    private fun carried(layer: Layer): Set<String> {
        val cols = Schema.columns(layer).map { it.name.substringBefore('.') }.filter { !it.startsWith("tag:") }.toSet()
        return cols + GEOMETRY + "tags" + READ_THROUGH
    }

    private val layers: Map<String, Layer> = mapOf(
        "buildings" to Layer.BUILDING, "parts" to Layer.PART, "areas" to Layer.GROUND, "land" to Layer.GROUND, "roads" to Layer.ROAD,
        "trees" to Layer.TREE, "nodes" to Layer.THING, "pois" to Layer.THING, "labels" to Layer.LABEL,
        "big" to Layer.LINE, "rail" to Layer.LINE, "aero" to Layer.LINE, "power" to Layer.LINE, "marine" to Layer.LINE, "park" to Layer.LINE,
        "cranes" to Layer.ITEM, "masts" to Layer.ITEM, "mtowers" to Layer.ITEM, "turbines" to Layer.ITEM,
    )

    /** The tag keys each scene list's tiles carry ([Schema.RENDER_TAGS], and what a thing derives). */
    private val carriedTags: Map<String, Set<String>> = mapOf(
        "buildings" to Schema.RENDER_TAGS.getValue(Layer.BUILDING).toSet(),
        "parts" to Schema.RENDER_TAGS.getValue(Layer.PART).toSet(),
        "areas" to Schema.RENDER_TAGS.getValue(Layer.GROUND).toSet(),
        "land" to Schema.RENDER_TAGS.getValue(Layer.GROUND).toSet(),
        "roads" to Schema.RENDER_TAGS.getValue(Layer.ROAD).toSet(),
        "trees" to Schema.RENDER_TAGS.getValue(Layer.TREE).toSet(),
        // a thing's house number is carried as its door and the door's number
        "nodes" to Schema.RENDER_TAGS.getValue(Layer.THING).toSet() + "addr:housenumber",
        "pois" to Schema.RENDER_TAGS.getValue(Layer.THING).toSet() + "addr:housenumber",
        "big" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(), "rail" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(),
        "aero" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(), "power" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(),
        "marine" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(), "park" to Schema.RENDER_TAGS.getValue(Layer.LINE).toSet(),
    )

    /** Items keep every tag. */
    private val items = setOf("cranes", "masts", "mtowers", "turbines")

    private fun md5(parts: List<String?>): String {
        val md = MessageDigest.getInstance("MD5")
        for (p in parts) { md.update((p ?: "").toByteArray(Charsets.UTF_8)); md.update(0) }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    @Test
    fun `the renders read no tag and no field the tiles drop`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        val renders = ArrayList<Pair<String, () -> String>>()
        val a = Packs.scene(Packs.region(Packs.westerkerkPack))
        keysAtLoad(a.S)
        for (m in listOf("sunny", "grey", "wet", "night")) {
            val eye = LinkedHashMap(ViewLadder.livingView()); eye["pitch"] = 4; eye["dist"] = 45; eye["target"] = listOf(-60, 1.6, -10)
            val close = LinkedHashMap(ViewLadder.livingView()); close["pitch"] = 25; close["dist"] = 120; close["target"] = listOf(-30, 0, -40)
            for ((cn, cfg) in listOf("above" to LinkedHashMap(ViewLadder.livingView()), "eye" to eye, "close" to close)) {
                renders += "westerkerk $m $cn" to {
                    a.state.restore()
                    val r = Amsterdam.render(a.S, m, cfg, null, layer = "split")
                    md5(listOf(r.svg) + r.objs.orEmpty())
                }
            }
        }
        val g = if (Packs.grachtenPack.isFile) Packs.scene(Packs.region(Packs.grachtenPack), "grachten_home") else null
        g?.let { keysAtLoad(it.S) }
        if (g != null) for (m in listOf("sunny", "wet")) renders += "grachten_home $m" to {
            g.state.restore()
            val r = Regions.render(g.S, m, Regions.cfgFor("grachten_home"))
            md5(listOf(r.g, r.o, r.o2))
        }
        // each render as it is, then recorded: the recording changes nothing drawn
        val plain = renders.map { (name, f) -> name to f() }
        val recorders = recordFields(a.S) + (g?.let { recordFields(it.S) } ?: emptyList())
        recordTags(a.S)
        g?.let { recordTags(it.S) }
        for (r in recorders) r.on = true
        for ((i, rf) in renders.withIndex()) assertEquals("${rf.first}: recording changed the render", plain[i].second, rf.second())
        for (r in recorders) r.on = false
        val missing = ArrayList<String>()
        for ((kind, keys) in seen) {
            if (kind in items) continue
            val have = carriedTags[kind] ?: run { missing += "$kind: its tags are read (${keys.joinToString()}), and no layer carries them"; continue }
            for (k in keys) if (k !in have) missing += "$kind reads the tag $k"
        }
        val never = Schema.DROPPED.values.flatMap { it.keys }.toSet() - READ_THROUGH
        for ((kind, keys) in fields) {
            val layer = layers[kind] ?: run { missing += "$kind: its fields are read (${keys.joinToString()}), and no layer carries them"; continue }
            val have = carried(layer)
            for (k in keys) {
                if (k in never) missing += "$kind reads $k, which Schema.DROPPED says the renders never read"
                else if (k !in have) missing += "$kind reads $k, which the ${layer.label} layer does not carry"
            }
        }
        println("TAGS read while drawing: $seen")
        println("FIELDS read while drawing: $fields; records read whole: $whole")
        println("RECORDING: ${renders.size} renders, each equal to the render unrecorded")
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }

    companion object {
        /** The lists a record's kind is taken from first: a tree is a node, a poi is a node, a part is not a building. */
        val ORDER = listOf("trees", "pois", "buildings", "parts", "nodes")

        /** Geometry and position: each layer's geometry columns. */
        val GEOMETRY = setOf("poly", "pts", "ring", "x", "z", "id")

        /**
         * Fields the renders read only through a value the packer derives with the port's own function: a building's
         * colours and materials (`surface_colour` → `wall_rgb`, `roof_rgb`, the board lines' [Schema.Flags.WALL_DRAWN]),
         * its ring's box, a node's style (its `style.*` columns).
         */
        val READ_THROUGH = setOf("col", "mat", "col_draw", "rcol", "rmat", "rcol_draw", "_bb", "style")
    }
}
