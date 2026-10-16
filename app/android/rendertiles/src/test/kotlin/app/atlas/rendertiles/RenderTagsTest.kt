package app.atlas.rendertiles

import app.atlas.render.Amsterdam
import app.atlas.render.Regions
import app.atlas.render.live.ViewLadder
import app.atlas.render.py.Dict
import app.atlas.rendertiles.model.Layer
import app.atlas.rendertiles.model.Schema
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The tag keys the tiles carry ([Schema.RENDER_TAGS]) are every key the renders read when they draw: the boards' views
 * rendered with every tag map recording what is read (the Westerkerk at its four moments from above, close and at eye
 * level; the Main board at sunny and wet, which is night). A key read and not carried, or a record's tags read whole,
 * fails: the port has started to draw from a tag the tiles drop.
 */
class RenderTagsTest {
    private val seen = java.util.TreeMap<String, java.util.TreeSet<String>>()

    private inner class Recording(val kind: String, val inner: Map<String, Any?>) : Map<String, Any?> by inner {
        private fun note(k: String) { seen.getOrPut(kind) { java.util.TreeSet() }.add(k) }
        override fun get(key: String): Any? { note(key); return inner[key] }
        override fun containsKey(key: String): Boolean { note(key); return inner.containsKey(key) }
        override val entries: Set<Map.Entry<String, Any?>> get() { note("*"); return inner.entries }
        override val keys: Set<String> get() { note("*"); return inner.keys }
        override val values: Collection<Any?> get() { note("*"); return inner.values }
    }

    @Suppress("UNCHECKED_CAST")
    private fun record(S: Dict) {
        for ((k, v) in S) {
            if (v !is List<*>) continue
            for (o in v) if (o is MutableMap<*, *>) {
                val m = o as MutableMap<String, Any?>
                val t = m["tags"] as? Map<String, Any?> ?: continue
                if (t !is Recording) m["tags"] = Recording(k, t)
            }
        }
    }

    /** The layer a scene list's records go to, and the keys its tiles carry ([Schema.RENDER_TAGS], and what a thing derives). */
    private val carried: Map<String, Set<String>> = mapOf(
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

    @Test
    fun `the renders read no tag the tiles drop`() {
        assumeTrue(Packs.westerkerkPack.isFile)
        val a = Packs.scene(Packs.region(Packs.westerkerkPack))
        record(a.S)
        for (m in listOf("sunny", "grey", "wet", "night")) {
            a.state.restore()
            Amsterdam.render(a.S, m, LinkedHashMap(ViewLadder.livingView()), null, layer = "split")
            val eye = LinkedHashMap(ViewLadder.livingView()); eye["pitch"] = 4; eye["dist"] = 45; eye["target"] = listOf(-60, 1.6, -10)
            Amsterdam.render(a.S, m, eye, null, layer = "split")
            val close = LinkedHashMap(ViewLadder.livingView()); close["pitch"] = 25; close["dist"] = 120; close["target"] = listOf(-30, 0, -40)
            Amsterdam.render(a.S, m, close, null, layer = "split")
        }
        if (Packs.grachtenPack.isFile) {
            val g = Packs.scene(Packs.region(Packs.grachtenPack), "grachten_home")
            record(g.S)
            for (m in listOf("sunny", "wet")) {
                g.state.restore()
                Regions.render(g.S, m, Regions.cfgFor("grachten_home"))
            }
        }
        val missing = ArrayList<String>()
        for ((kind, keys) in seen) {
            if (kind in items) continue
            val have = carried[kind] ?: run { missing += "$kind: its tags are read (${keys.joinToString()}), and no layer carries them"; continue }
            for (k in keys) if (k !in have) missing += "$kind reads $k"
        }
        println("TAGS read while drawing: $seen")
        assertTrue(missing.joinToString("\n"), missing.isEmpty())
    }
}
