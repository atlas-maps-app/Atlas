package app.atlas.packer.world

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.util.PolygonExtracter
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.simplify.DouglasPeuckerSimplifier

/**
 * The land under the world's tiles (ENGINE.md step 5): osmdata's land polygons (OSM's coastline; the split ones at L6
 * to L11, the simplified ones at L4 and L5) or Natural Earth's land (L0 to L3), in Mercator's unit square. A tile's land
 * is every piece in it, joined and simplified at the level's tolerance in the tile's pixels, as Planetiler simplifies
 * the features over it; outside the land is the sea.
 *
 * A piece the square leaves invalid is mended first ([WorldGeometry.valid]). The tiles are walked down a quadtree
 * ([walk]), each tile's pieces cut from its parent's, so a large piece (a continent of the simplified polygons) is cut
 * once a level rather than once a tile.
 */
class LandBase(pieces: List<Geometry>) {
    private val pieces: List<Geometry> = pieces.map { WorldGeometry.valid(it) }.flatMap { g -> PolygonExtracter.getPolygons(g).map { it as Geometry } }.filter { !it.isEmpty }

    /** The pieces in tile ([z], [x], [y]) and the margin about it, cut from [from] (the parent's, or every piece). */
    fun cut(z: Int, x: Int, y: Int, from: List<Geometry>? = null): List<Geometry> {
        val env = WorldGeometry.tileWorld(z, x, y, MARGIN)
        val box = WorldGeometry.FLOAT.toGeometry(env)
        val candidates = if (from != null) from.filter { it.envelopeInternal.intersects(env) } else index().query(env).map { it as Geometry }
        val out = ArrayList<Geometry>(candidates.size)
        for (g in candidates) {
            if (env.contains(g.envelopeInternal)) {
                out += g
                continue
            }
            val c = OverlayNGRobust.overlay(g, box, OverlayNG.INTERSECTION)
            for (p in PolygonExtracter.getPolygons(c)) if (!(p as Geometry).isEmpty) out += p
        }
        return out
    }

    @Volatile private var tree: STRtree? = null

    private fun index(): STRtree = tree ?: synchronized(this) {
        tree ?: STRtree().also { t -> for (g in pieces) t.insert(g.envelopeInternal, g); t.build(); tree = t }
    }

    /**
     * The land of tile ([z], [x], [y]) in its pixels from its [pieces] (as [cut] gives them): joined, then simplified at
     * [tolerance] pixels; null where there is none.
     */
    fun land(z: Int, x: Int, y: Int, pieces: List<Geometry>, tolerance: Double): Geometry? {
        if (pieces.isEmpty()) return null
        val px = pieces.map { WorldGeometry.worldToPixels(it, z, x, y) }
        val joined = if (px.size == 1) px[0] else OverlayNGRobust.union(WorldGeometry.FLOAT.buildGeometry(px))
        val simple = DouglasPeuckerSimplifier.simplify(joined, tolerance)
        return simple.takeIf { !it.isEmpty && it.area > 0 }
    }

    /**
     * Visits tile ([z], [x], [y]) with its pieces, then each tile under it down to level [last], depth first, each
     * child's pieces cut from its parent's. [visit] says whether to go on below a tile (false: none of its children).
     */
    fun walk(z: Int, x: Int, y: Int, last: Int, from: List<Geometry>? = null, visit: (z: Int, x: Int, y: Int, pieces: List<Geometry>) -> Boolean) {
        val mine = cut(z, x, y, from)
        if (!visit(z, x, y, mine) || z >= last) return
        for (dy in 0..1) for (dx in 0..1) walk(z + 1, 2 * x + dx, 2 * y + dy, last, mine, visit)
    }

    companion object {
        /** The pixels a tile's land reaches beyond its edges, so its simplification does not move its edges' land. */
        const val MARGIN = 2.0
    }
}
