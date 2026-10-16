package app.atlas.rendertiles.compile

import app.atlas.render.py.Dict
import app.atlas.render.py.pyNum
import app.atlas.rendertiles.model.GroundKey

/**
 * Ground keys (plan-vector D6): every ground primitive's slot in the renders' painter's order, so the core draws the
 * ground in that order by its depth slots. The order is `amsterdam.py`'s `render` (its ground layers in their fixed
 * order, amsterdam.py:981) with `regions.py`'s `draw_under` and `draw_over` inside it:
 *
 * 1. land cover ([GroundKey.LAND]): one path per kind, the kinds in the order the land list (sorted by `LAND`'s draw
 *    order, then by area, largest first) first shows them; the class is that kind's rank, the order its place in the
 *    sorted list;
 * 2. the water ([GroundKey.WATER]): class 0 the water rings, 1 the islands;
 * 3. areas ([GroundKey.AREA]) in data order;
 * 4. roads ([GroundKey.ROAD]): class by class in [ROAD_ORDER], data order within a class (amsterdam.py:679);
 * 5. over the ground ([GroundKey.OVER]): class 0 rail in data order, 1 big roads in the stable order of their layer,
 *    2 runways and taxiways in the order of [AERO_ORDER], 3 marine works in data order, 5 a park's edge;
 * 6. marks ([GroundKey.MARKS]): class 0 tram tracks in data order.
 */
object GroundKeys {
    /**
     * `amsterdam.py`'s road classes in drawing order (`ROAD_ORDER`, private in the port: `RenderTablesTest` holds this
     * copy equal to it).
     */
    val ROAD_ORDER: List<String> = listOf("path", "footway", "steps", "cycleway", "service", "pedestrian", "living_street", "unclassified", "residential", "busway", "tertiary", "secondary")

    /** `regions.py`'s `draw_over` sorts runways, taxiways and stands by this (a local table in the port; copied, checked by test). */
    val AERO_ORDER: Map<String, Int> = mapOf("parking_position" to 3, "taxilane" to 1, "taxiway" to 1, "stopway" to 0, "runway" to 2)

    /** The keys of `S['land']` (already sorted by the loader), by index. */
    fun land(land: List<Dict>): LongArray {
        val rank = LinkedHashMap<String, Int>()
        for (a in land) rank.getOrPut(a["kind"] as String) { rank.size }
        return LongArray(land.size) { GroundKey.of(GroundKey.LAND, rank.getValue(land[it]["kind"] as String), it) }
    }

    fun water(index: Int, hole: Boolean): Long = GroundKey.of(GroundKey.WATER, if (hole) 1 else 0, index)

    fun area(index: Int): Long = GroundKey.of(GroundKey.AREA, 0, index)

    fun road(hw: String, index: Int): Long {
        val k = ROAD_ORDER.indexOf(hw)
        require(k >= 0) { "road $index has class $hw, which the renders do not draw" }
        return GroundKey.of(GroundKey.ROAD, k, index)
    }

    /** The keys of `S['big']`: their rank in the stable sort by layer (`sorted(S['big'], key=layer)`). */
    fun big(big: List<Dict>): LongArray {
        val order = big.indices.sortedWith(compareBy<Int> { pyNum(big[it]["layer"]) + 0.0 }.thenBy { it })
        val out = LongArray(big.size)
        for ((rank, i) in order.withIndex()) out[i] = GroundKey.of(GroundKey.OVER, 1, rank)
        return out
    }

    /** The keys of `S['aero']`: their rank in the stable sort by [AERO_ORDER]. */
    fun aero(aero: List<Dict>): LongArray {
        val order = aero.indices.sortedWith(compareBy<Int> { AERO_ORDER[aero[it]["kind"] as String] ?: throw IllegalArgumentException("aeroway ${aero[it]["kind"]} has no order") }.thenBy { it })
        val out = LongArray(aero.size)
        for ((rank, i) in order.withIndex()) out[i] = GroundKey.of(GroundKey.OVER, 2, rank)
        return out
    }

    fun rail(index: Int): Long = GroundKey.of(GroundKey.OVER, 0, index)

    fun marine(index: Int): Long = GroundKey.of(GroundKey.OVER, 3, index)

    fun park(index: Int): Long = GroundKey.of(GroundKey.OVER, 5, index)

    fun tram(index: Int): Long = GroundKey.of(GroundKey.MARKS, 0, index)
}
