package app.atlas.rendertiles.compile

import app.atlas.render.V2
import app.atlas.rendertiles.model.GridPoint
import app.atlas.rendertiles.model.Layer

/**
 * Label candidates (plan-vector D13): what the renders' canal names are chosen from, and the place nodes K23 names.
 *
 * - **Canal names** (`amsterdam.py:729–741`): `S['labels']` of kind canal, in source order, each with what the rule
 *   reads: the name, the way's middle vertex `pts[n / 2]` (its anchor, and the point the radius test takes) and the
 *   vertex before it (the baseline's direction), and the way's vertex count, and its slot among the marks
 *   ([GroundKeys.canal]). A way of fewer than 2 vertices is never drawn and is not written. Street names (kind street)
 *   are never drawn by the renders and are not written either.
 * - **Place nodes**: nodes tagged place=*, with their name, the place value and the tags a rank reads.
 */
object Labels {
    /**
     * A label before it is placed: its fields ([app.atlas.rendertiles.model.Schema.LABEL]), reference point, order, and
     * how far its drawing reaches from the point, metres (a canal name's letters, 3.2 m high, run along the water).
     */
    class Item(val fields: Map<String, Any?>, val ref: GridPoint, val order: Long, val reach: Double)

    /** What a label record holds: `amsterdam.py`'s `dict(pts, name, kind)`. */
    private val FIELDS = setOf("pts", "name", "kind")

    fun features(scene: SceneToTiles): List<Item> {
        val out = ArrayList<Item>()
        for ((i, lb) in scene.labelDicts().withIndex()) {
            for (k in lb.keys) check(k in FIELDS) { "label $i: the field $k has no column in the label layer" }
            if (lb["kind"] != "canal") continue
            @Suppress("UNCHECKED_CAST")
            val pts = lb["pts"] as List<V2>
            if (pts.size < 2) continue
            val what = { "canal label $i" }
            val mid = scene.gridPoint(pts[pts.size / 2], what)
            val prev = scene.gridPoint(pts[pts.size / 2 - 1], what)
            val name = lb["name"] as String
            val fields = linkedMapOf<String, Any?>(
                "source" to 0, "index" to i, "name" to name, "pos" to mid, "prev" to prev, "count" to pts.size.toLong(), "ground_key" to GroundKeys.canal(i),
            )
            out += Item(fields, mid, i.toLong(), 1.6 * name.length + 3.2)
        }
        for ((i, n) in scene.sceneNodes.withIndex()) {
            @Suppress("UNCHECKED_CAST")
            val t = (n["tags"] as Map<String, Any?>?) ?: continue
            val place = t["place"] as? String ?: continue
            val p = scene.gridPoint(V2(n["x"] as Double, n["z"] as Double)) { "place node $i" }
            val fields = linkedMapOf<String, Any?>(
                "source" to 1, "index" to i, "id" to (n["id"] as Number).toLong(), "name" to t["name"] as String?, "pos" to p, "prev" to p, "count" to 1L,
                "kind" to place,
            )
            fields.putAll(scene.tagsFor(Layer.LABEL, n))
            out += Item(fields, p, (1L shl 40) or i.toLong(), 0.0)
        }
        return out
    }
}
