package app.atlas.packer.heights

import app.atlas.rendertiles.model.Heights

/**
 * The lattice a level's height samples lie on (rendertiles' [Heights]): node (X, Y) of level z with n samples a tile
 * side is sample (Y − y·(n − 1), X − x·(n − 1)) of every tile (x, y) that holds it, at Web Mercator's
 * (X, Y) / (2^z · (n − 1)) of the world's side. The packer computes each node once, in the window that owns it, so a
 * tile's edge and apron samples are its neighbours' own.
 */
class Lattice(val level: Int, val side: Int) {
    /** Nodes a tile side, the far edge left to the next tile. */
    val per: Int = side - 1

    /** Nodes round the world. */
    val nodes: Long = per.toLong() shl level

    /** The world's side on Web Mercator's plane (EPSG:3857), metres, and a node's step on it. */
    val step: Double = Heights.EQUATOR / nodes

    /** EPSG:3857's x of node column [x] and y of node row [y]. */
    fun mx(x: Long): Double = x * step - Heights.EQUATOR / 2
    fun my(y: Long): Double = Heights.EQUATOR / 2 - y * step

    fun lon(x: Double): Double = x / nodes * 360.0 - 180.0
    fun lat(y: Double): Double = Math.toDegrees(StrictMath.atan(StrictMath.sinh(Math.PI - 2.0 * Math.PI * y / nodes)))

    /** The node column of longitude [lon] and the node row of latitude [lat], as fractions. */
    fun x(lon: Double): Double = (lon + 180.0) / 360.0 * nodes
    fun y(lat: Double): Double {
        val s = StrictMath.sin(Math.toRadians(lat))
        return (0.5 - StrictMath.log((1 + s) / (1 - s)) / (4 * Math.PI)) * nodes
    }

    /** The nodes' spacing on the ground at node row [y], metres. */
    fun spacing(y: Double): Double = step * StrictMath.cos(Math.toRadians(lat(y)))
}

/** A window of a lattice: [width] by [height] nodes from column [x0] and row [y0]. */
class Window(val lattice: Lattice, val x0: Long, val y0: Long, val width: Int, val height: Int) {
    val size: Int get() = width * height

    /** The window grown by [margin] nodes on every side. */
    fun grown(margin: Int): Window = Window(lattice, x0 - margin, y0 - margin, width + 2 * margin, height + 2 * margin)

    /**
     * The window's bounds on EPSG:3857 for a raster whose pixels' centres are its nodes: west, south, east, north, each
     * half a step beyond the outermost nodes.
     */
    fun bounds(): DoubleArray = doubleArrayOf(
        lattice.mx(x0) - lattice.step / 2, lattice.my(y0 + height - 1) - lattice.step / 2,
        lattice.mx(x0 + width - 1) + lattice.step / 2, lattice.my(y0) + lattice.step / 2,
    )
}

/**
 * A source's heights over a window: metres per node, row by row from the north-west, NaN where it has none; null where
 * it has none in the whole window.
 */
fun interface NodeSource {
    fun heights(window: Window): FloatArray?
}
