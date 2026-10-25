package app.atlas.rendertiles.compile

import app.atlas.render.Woods
import app.atlas.render.live.LiveRegion
import app.atlas.render.py.Dict
import app.atlas.render.py.truthy
import kotlin.math.hypot

/**
 * A park's and a zoo's planted woods as the engine plants them (the core's load/Woods.hpp): woods.py's plant, the port's
 * [Woods.plant], once for the whole region, about the centre of its box and out to its half-diagonal, as the port loads
 * a region whole (LiveEngine's wholeCamera, whose scene the packer cuts): every wood in it planted whole on its own
 * lattice, the same trees at every view and in every tile. The renders plant about the view they draw, its target and
 * its radius. The core's tool gets the anchor in its job, three doubles, as it gets the frame's.
 */
object PlantedWoods {
    /** Where [region]'s woods are planted about: its box's centre in its frame and its half-diagonal (LiveEngine.wholeCamera's target and radius). */
    fun anchor(region: LiveRegion): DoubleArray {
        val box = region.bbox ?: throw IllegalStateException("region ${region.id} has no box to plant its woods in")
        val f = region.frame
        val x0 = f.x(box.south, box.west)
        val x1 = f.x(box.north, box.east)
        val z0 = f.z(box.north, box.west)
        val z1 = f.z(box.south, box.east)
        return doubleArrayOf((x0 + x1) / 2, (z0 + z1) / 2, hypot(x1 - x0, z1 - z0) / 2)
    }

    /** Whether region scene [S]'s R names a park or a zoo, which plant their woods. */
    @Suppress("UNCHECKED_CAST")
    fun plants(S: Dict): Boolean {
        val R = (S["R"] as Map<String, Any?>?) ?: return false
        return truthy(R["park"]) || truthy(R["zoo"])
    }

    /** The trees of [region]'s scene [S]'s woods, where it plants them (none else). */
    fun of(S: Dict, region: LiveRegion): List<Dict> {
        if (!plants(S)) return emptyList()
        val (cx, cz, reach) = anchor(region)
        return Woods.plant(S, cx, cz, reach)
    }
}
