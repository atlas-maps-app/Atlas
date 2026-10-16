package app.atlas.rendertiles.compile

import app.atlas.rendertiles.model.Format
import app.atlas.rendertiles.model.Grid

/**
 * Which tile and which cell a feature belongs to: the tile whose grid box holds its reference point ([Grid.col],
 * [Grid.row]), and within it the cell under the point, cells counted row by row from the north-west (8 × 8 at L14,
 * 4 × 4 at L12, about 186 m at the canal ring). A feature is written once, in that tile, whatever it reaches into.
 */
object Cells {
    /** A tile at a level: zoom, column, row. */
    data class TileKey(val z: Int, val x: Int, val y: Int)

    /** The tile at zoom [z] holding grid point ([lat], [lon]). */
    fun tileOf(z: Int, lat: Int, lon: Int): TileKey = TileKey(z, Grid.col(lon, z), Grid.row(lat, z))

    /** The cell of grid point ([lat], [lon]) within [tile] (a point on the box's east or south edge goes to the last cell). */
    fun cellOf(tile: TileKey, lat: Int, lon: Int): Int {
        val n = Format.cellsPerSide(tile.z)
        val b = Grid.tileBox(tile.z, tile.x, tile.y)
        val col = (((lon.toLong() - b[1]) * n) / (b[3].toLong() - b[1])).toInt().coerceIn(0, n - 1)
        val row = (((b[2].toLong() - lat) * n) / (b[2].toLong() - b[0])).toInt().coerceIn(0, n - 1)
        return row * n + col
    }

    /** The centre of a grid box [south, west, north, east], rounded down: the reference point of a ring or a line. */
    fun centre(box: IntArray): IntArray = intArrayOf(Math.floorDiv(box[0].toLong() + box[2], 2L).toInt(), Math.floorDiv(box[1].toLong() + box[3], 2L).toInt())
}
