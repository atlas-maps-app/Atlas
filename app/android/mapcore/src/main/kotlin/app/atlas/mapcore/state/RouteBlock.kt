package app.atlas.mapcore.state

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The map's routes and areas for the core (ENGINE.md step 6; `MapCore.routes`): each area, each route's look as the
 * board's painter sets it (its colours mixed, its width in dp, in the order they are drawn: the alternatives first)
 * with its points and their distances along it, and each route's chip as the painter draws it, written one after
 * another into a direct buffer as the core reads it (`CP/routes/Routes.hpp`'s read_route_block), little-endian: the
 * magic, the density (f32) and the counts of areas, routes and chips (u32 each); each area: its latitude and longitude
 * (f64), radius (f32, metres), fill and stroke (u32 ARGB); each route: its points' count (u32), width (f32, dp), colour,
 * faded colour and casing (u32 ARGB), dashed flag (u8) and three zero bytes, the distance travelled (f64, metres), its
 * chip (u32, [NO_CHIP] for none), and its points (f64 latitude, longitude and distance along it, true metres, each);
 * each chip: its width and height (u32), centre (i32 x, y) and pixels (width × height, premultiplied, R in the low
 * byte, rows top to bottom). Plain Kotlin on `java.nio`, so the JVM tests compile it too.
 */
object RouteBlock {
    /** The block's first word: 'RTE1' little-endian (`route_block_magic`). */
    const val MAGIC = 0x31455452

    /** A route without a chip. */
    const val NO_CHIP = -1

    /** One chip: [width] × [height] premultiplied pixels, R in the low byte, and where its centre lies in it. */
    class Image(val width: Int, val height: Int, val anchorX: Int, val anchorY: Int, val pixels: IntArray) {
        init {
            require(pixels.size == width * height) { "the pixels are width × height" }
        }
    }

    class Area(val lat: Double, val lon: Double, val radiusM: Float, val fill: Int, val stroke: Int)

    /** One route: its points as latitude, longitude and distance along it, in turn. */
    class Route(
        val points: DoubleArray,
        val widthDp: Float,
        val colour: Int,
        val faded: Int,
        val casing: Int,
        val dashed: Boolean,
        val travelled: Double,
        val chip: Int,
    ) {
        init {
            require(points.size % 3 == 0) { "a point is a latitude, a longitude and a distance along" }
        }
    }

    /** The bytes of a block. */
    fun size(areas: List<Area>, routes: List<Route>, chips: List<Image>): Int =
        20 + 28 * areas.size + routes.sumOf { 36 + 8 * it.points.size } + chips.sumOf { 16 + 4 * it.pixels.size }

    /** [areas], [routes] and their [chips], drawn at [density], as a block in a new direct buffer. */
    fun write(density: Float, areas: List<Area>, routes: List<Route>, chips: List<Image>): ByteBuffer {
        val b = ByteBuffer.allocateDirect(size(areas, routes, chips)).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC).putFloat(density).putInt(areas.size).putInt(routes.size).putInt(chips.size)
        for (a in areas) b.putDouble(a.lat).putDouble(a.lon).putFloat(a.radiusM).putInt(a.fill).putInt(a.stroke)
        for (r in routes) {
            b.putInt(r.points.size / 3).putFloat(r.widthDp).putInt(r.colour).putInt(r.faded).putInt(r.casing)
            b.put(if (r.dashed) 1 else 0).put(0).put(0).put(0)
            b.putDouble(r.travelled).putInt(r.chip)
            for (v in r.points) b.putDouble(v)
        }
        for (c in chips) {
            b.putInt(c.width).putInt(c.height).putInt(c.anchorX).putInt(c.anchorY)
            for (p in c.pixels) b.putInt(p)
        }
        b.flip()
        return b
    }
}
