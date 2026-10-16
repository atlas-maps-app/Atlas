package app.atlas.rendertiles.compile

import app.atlas.render.Real
import app.atlas.render.py.PyMath
import app.atlas.render.py.pyNum

/**
 * How far the renders draw from a feature beyond its vertices, metres ([app.atlas.rendertiles.model.Format]'s *Boxes
 * and tops*): how much a cell's box is widened and how high its top reaches. Read off the port's drawing code, each is
 * a bound, never less than what is drawn, with the renders' own defaults where a height or a width is not mapped.
 */
object Drawn {
    /** Chimney stacks and pots on a roof: up to 1.6 m above its top (`amsterdam.py`'s `trad_chimneys`). */
    const val CHIMNEY = 1.6

    /** A thing: a lamp's 4.2 m mast and its lantern, a bench, a bollard, a door or a shopfront on the facade beside its node, a café's glow. */
    const val THING = 5.0

    /** Beyond a road's half width: a bridge's railings 0.3 m out, the pavements drawn at eye level. */
    const val ROAD_SIDE = 3.0

    /** A road's, a rail's or a track's top: decks at 0.05 to 0.09 m. */
    const val ROAD_TOP = 0.1

    /** A bridge: its deck 0.45 m up and its railing lines at 1.1 m. */
    const val BRIDGE_TOP = 2.0

    /** Tram tracks: two rails 0.72 m either side of the line. */
    const val TRAM = 1.5

    /** Areas are drawn at 0.02 m (`gp(a['poly'], c, 0.02)`), land cover at 0.01 m (`gpoly(cam, a['poly'], 0.01)`). */
    const val AREA_TOP = 0.02
    const val LAND_TOP = 0.01

    /** A tree's height and crown where the scene has none: `amsterdam.py`'s defaults. */
    const val TREE_H = 12.0
    const val TREE_CROWN = 7.0

    /**
     * A tree's crown reaches at most 0.8 crown diameters from its trunk (the close-up's clumps lie within 1.08 crown radii
     * and are 0.475 radii across; `draw_tree3d`'s within 0.8 and 0.55) and at most half a crown above its height; a tree
     * symbol is a 3 m disc.
     */
    const val CROWN_REACH = 0.8
    const val TREE_SYMBOL = 3.0

    /** (widening, top) of a line of `source` [source] ([app.atlas.rendertiles.model.Schema.LINE]) and record [d]. */
    fun line(source: Int, d: Map<String, Any?>): Pair<Double, Double> = when (source) {
        // rail: the ballast 1.6 m either side
        1 -> 2.0 to ROAD_TOP
        // a big road: its asphalt by lanes (`draw_over`'s own width), raised 0.5 m a layer
        2 -> {
            val pad = runCatching {
                val t = tags(d)
                val hw = d["hw"] as String
                val lanes = PyMath.int(Real.num(t["lanes"], if (hw.endsWith("link")) 1.0 else 2.0)!!).toInt()
                val w = Real.num(t["width"], lanes * 3.5 + (if (hw == "motorway") 3.0 else 1.0))!!
                if (w.isFinite()) w / 2 + 0.5 else BIG_UNKNOWN
            }.getOrDefault(BIG_UNKNOWN)
            pad to 0.07 + maxOf(0.0, pyNum(d["layer"])) * 0.5
        }
        // runways (45 m wide unless mapped), taxiways (23 m), taxilanes and stopways (15 m), stands' lead-in lines
        3 -> {
            val t = tags(d)
            val w = when (d["kind"]) {
                "runway" -> Real.num(t["width"], 45.0)
                "taxiway" -> Real.num(t["width"], 23.0)
                "taxilane", "stopway" -> Real.num(t["width"], 15.0)
                else -> 0.0
            } ?: 0.0
            (if (w.isFinite()) w / 2 + 1.0 else BIG_UNKNOWN) to ROAD_TOP
        }
        // power lines: three conductors 6 m apart, towers 38 m high and their tops 2 m more
        4 -> 7.0 to 40.0
        // marine works: a breakwater's strip is 9 m wide; a closed one is its own ring; all at 0.3 m
        5 -> (if (d["closed"] == true) 0.5 else 5.0) to 0.3
        // a park's edge: a dashed line on the ground
        6 -> 1.0 to 0.15
        else -> 0.0 to 0.0
    }

    /** A big road or runway whose width cannot be read: the widest the renders draw one. */
    private const val BIG_UNKNOWN = 30.0

    /** (reach from its position, top) of an item of `source` [source] ([app.atlas.rendertiles.model.Schema.ITEM]) and record [d]. */
    fun item(source: Int, d: Map<String, Any?>): Pair<Double, Double> {
        // Python's `x or default`: a height of 0 is not mapped either
        val h = (d["h"] as Number?)?.toDouble()?.takeIf { it != 0.0 }
        return when (source) {
            // a crane: on a quay, the ship-to-shore shape, its boom out over the water (at most 112 m) and 22 m back, its
            // A-frame 20 m above its height (44 m unless mapped); in a yard, a portal within its outline, 26 m unless mapped
            0 -> (if (d["poly"] != null) 5.0 else 115.0) to (Real.num(tags(d)["height"], 44.0) ?: 44.0) + 20.0
            // a mast: 30 m (lighting) or 25 m unless mapped, and a lighting mast's frame of floodlights 1.3 m above
            1 -> 2.0 to (h ?: 30.0) + 1.3
            // a tower with a height: its shaft, and a cab 2.7 times its 3.2 m radius
            2 -> maxOf(8.7, (h ?: 0.0) / 30) to (h ?: 0.0)
            // a pylon: power lines draw their own towers at their vertices; the renders draw nothing at the node
            3 -> 0.0 to (h ?: 0.0)
            // a turbine: its hub (100 m unless mapped) 2 m upwind, its blades (a 100 m rotor unless mapped)
            4 -> {
                val r = ((d["rotor"] as Number?)?.toDouble()?.takeIf { it != 0.0 } ?: 100.0) / 2
                (r + 3.0) to (h ?: 100.0) + r
            }
            else -> 0.0 to (h ?: 0.0)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun tags(d: Map<String, Any?>): Map<String, Any?> = (d["tags"] as Map<String, Any?>?) ?: emptyMap()
}
