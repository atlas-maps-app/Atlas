package app.atlas.tiles

import app.atlas.geo.LatLon
import app.atlas.geo.Mercator
import app.atlas.geo.TileId

/** Converts tile units of [tile] (0..[extent], y down) to unit Mercator and to latitude and longitude, and back. */
class TileProjection(val tile: TileId, val extent: Int) {
    private val k = tile.unitSize / extent

    fun unitX(x: Double): Double = tile.unitX + x * k
    fun unitY(y: Double): Double = tile.unitY + y * k
    fun lon(x: Double): Double = Mercator.lon(unitX(x))
    fun lat(y: Double): Double = Mercator.lat(unitY(y))
    fun latLon(x: Double, y: Double): LatLon = LatLon(lat(y), lon(x))

    /** Tile units of a unit-Mercator x or y. */
    fun tileX(unitX: Double): Double = (unitX - tile.unitX) / k
    fun tileY(unitY: Double): Double = (unitY - tile.unitY) / k

    /** Tile units of a point on Earth, as [x, y]. */
    fun toTile(p: LatLon): DoubleArray = doubleArrayOf(tileX(Mercator.x(p.lon)), tileY(Mercator.y(p.lat)))
}

/**
 * How [target] is drawn from the data of [source], itself or one of its ancestors (overzoom, or a missing tile's fallback):
 * the target covers the square [left], [top], [size] of the source (as fractions 0..1 of it), magnified by [scale].
 */
class TileTransform(val source: TileId, val target: TileId) {
    init {
        require(target.z >= source.z && target.ancestor(source.z) == source) { "$source is not $target or an ancestor of it" }
    }

    /** Zoom levels between the source data and the target. */
    val dz: Int = target.z - source.z
    val scale: Double = (1L shl dz).toDouble()
    val size: Double = 1.0 / scale
    val left: Double = (target.x - (source.x.toLong() shl dz)) / scale
    val top: Double = (target.y - (source.y.toLong() shl dz)) / scale
    val isIdentity: Boolean get() = dz == 0

    /** The target's square in source tile units for a layer of [extent]: [x0, y0, x1, y1]. */
    fun clip(extent: Int): DoubleArray =
        doubleArrayOf(left * extent, top * extent, (left + size) * extent, (top + size) * extent)

    /** A source x in tile units, in the target tile's units of the same extent. */
    fun x(sourceX: Double, extent: Int): Double = (sourceX - left * extent) * scale
    fun y(sourceY: Double, extent: Int): Double = (sourceY - top * extent) * scale

    override fun equals(other: Any?) = other is TileTransform && other.source == source && other.target == target
    override fun hashCode() = source.hashCode() * 31 + target.hashCode()
    override fun toString() = "TileTransform($source -> $target)"
}
