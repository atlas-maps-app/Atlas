package app.atlas.rendertiles.compile

import app.atlas.render.Woods
import app.atlas.render.py.Dict
import app.atlas.render.py.PyMath
import app.atlas.render.py.truthy

/**
 * A park's and a zoo's planted woods as the engine plants them (the core's load/Woods.hpp): woods.py's plant, the port's
 * [Woods.plant], once for the whole region about its frame's origin, out to the farthest of its box's corners and 40 m
 * (a whole number of the planting grid's 2 m cells), so every wood is planted whole on its own lattice and every view of
 * the region and every tile has the same trees. The renders plant about the view they draw, its target and its radius.
 */
object PlantedWoods {
    /** The radius about the frame's origin (lat0, lon0, kx, kz) that holds a region's box (south, west, north, east) and 40 m, in whole 2 m. */
    fun radius(bbox: List<Double>, lat0: Double, lon0: Double, kx: Double, kz: Double): Double {
        var far = 0.0
        for (lat in listOf(bbox[0], bbox[2])) {
            for (lon in listOf(bbox[1], bbox[3])) far = maxOf(far, PyMath.hypot((lon - lon0) * kx, -(lat - lat0) * kz))
        }
        return 2.0 * Math.ceil((far + 40.0) / 2.0)
    }

    /** The trees of region scene [S]'s woods, where its R names a park or a zoo (none else), in the frame (lat0, lon0, kx, kz). */
    @Suppress("UNCHECKED_CAST")
    fun of(S: Dict, lat0: Double, lon0: Double, kx: Double, kz: Double): List<Dict> {
        val R = (S["R"] as Map<String, Any?>?) ?: return emptyList()
        if (!truthy(R["park"]) && !truthy(R["zoo"])) return emptyList()
        val bbox = (R["bbox"] as List<Any?>).map { (it as Number).toDouble() }
        return Woods.plant(S, 0.0, 0.0, radius(bbox, lat0, lon0, kx, kz))
    }
}
