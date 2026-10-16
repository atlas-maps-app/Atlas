package app.atlas.rendertiles.model

/**
 * One feature of a tile: its [layer] and its [fields] by column name ([Schema]): scalars, strings, [Geom]s,
 * [GridPoint]s, tag maps, id lists and typed values. A field left out holds its column's default.
 *
 * [cell] is the cell under its reference point; [box] the grid box of its whole extent ([south, west, north, east]);
 * [heightCm] its top in centimetres (0 for what lies on the ground); [order] its place among the layer's features of
 * its cell (source order). The writer needs these four; a decoded feature has [cell] and [order] from the tile and no
 * [box].
 */
class Feature(
    val layer: Layer,
    val fields: Map<String, Any?>,
    val cell: Int,
    val box: IntArray? = null,
    val heightCm: Int = 0,
    val order: Long = 0,
) {
    operator fun get(name: String): Any? = fields[name]

    override fun equals(other: Any?): Boolean = other is Feature && layer == other.layer && fields == other.fields && cell == other.cell

    override fun hashCode(): Int = (layer.hashCode() * 31 + fields.hashCode()) * 31 + cell

    override fun toString(): String = "${layer.label}#${fields["index"]}@$cell"
}

/**
 * The cell table's entry for one cell: the box of its features' whole extents ([south, west, north, east] on the grid,
 * null for an empty cell), their highest top in centimetres, and how many features of each layer it holds.
 */
class Cell(val box: IntArray?, val maxHeightCm: Int, val counts: Map<Layer, Int>) {
    val total: Int get() = counts.values.sum()

    override fun equals(other: Any?): Boolean {
        if (other !is Cell || maxHeightCm != other.maxHeightCm) return false
        val a = box
        val b = other.box
        val sameBox = if (a == null || b == null) a == null && b == null else a.contentEquals(b)
        return sameBox && counts.filterValues { it != 0 } == other.counts.filterValues { it != 0 }
    }

    override fun hashCode(): Int = (box?.contentHashCode() ?: 0) * 31 + maxHeightCm

    override fun toString(): String = "Cell(${box?.joinToString()}, $maxHeightCm cm, $counts)"
}

/**
 * One tile: zoom [level], column [x], row [y] (0 at the north), [cellsPerSide] cells a side, its features by layer
 * (each list sorted by cell, then source order) and its cell table.
 */
class RenderTile(
    val level: Int,
    val x: Int,
    val y: Int,
    val cellsPerSide: Int,
    val layers: Map<Layer, List<Feature>>,
    val cells: List<Cell>,
) {
    /** The tile's bounds on the grid: [south, west, north, east]. */
    val box: IntArray get() = Grid.tileBox(level, x, y)

    val featureCount: Int get() = layers.values.sumOf { it.size }

    override fun toString(): String = "RenderTile($level/$x/$y, ${layers.mapKeys { it.key.label }.mapValues { it.value.size }})"

    companion object {
        /**
         * The tile of [features] (any order): sorted by cell and [Feature.order] within each layer, and its cell table made
         * from their boxes and heights.
         */
        fun of(level: Int, x: Int, y: Int, features: List<Feature>): RenderTile {
            val n = Format.cellsPerSide(level)
            val byLayer = java.util.TreeMap<Int, MutableList<Feature>>()
            for (f in features) {
                require(f.cell in 0 until n * n) { "$f: cell ${f.cell} outside the $n × $n cells" }
                byLayer.getOrPut(f.layer.id) { ArrayList() }.add(f)
            }
            val layers = LinkedHashMap<Layer, List<Feature>>()
            for ((id, list) in byLayer) layers[Layer.of(id)!!] = list.sortedWith(compareBy<Feature>({ it.cell }, { it.order }))
            val cells = (0 until n * n).map { c ->
                var box: IntArray? = null
                var top = 0
                val counts = LinkedHashMap<Layer, Int>()
                for ((layer, list) in layers) {
                    var k = 0
                    for (f in list) {
                        if (f.cell != c) continue
                        k++
                        val b = f.box
                        if (b != null) box = if (box == null) b.copyOf() else intArrayOf(minOf(box[0], b[0]), minOf(box[1], b[1]), maxOf(box[2], b[2]), maxOf(box[3], b[3]))
                        if (f.heightCm > top) top = f.heightCm
                    }
                    counts[layer] = k
                }
                Cell(box, top, counts)
            }
            return RenderTile(level, x, y, n, layers, cells)
        }
    }
}

/**
 * A ground feature's slot in the renders' ground order (plan-vector D6): [stage] (the fixed order of `render`'s ground
 * layers), then [klass] (the class order within a stage: a land cover kind's group, a road's class), then [order] (data
 * order within it). Packed as `stage << 28 | klass << 22 | order`.
 */
object GroundKey {
    /** `regions.py`'s `draw_under`: land cover, grouped by kind in the order the sorted land first shows each kind. */
    const val LAND = 1

    /** The water (its fill, clipped twice). */
    const val WATER = 2

    /** `S['areas']`, in data order, under the `mat` filter. */
    const val AREA = 3

    /** `S['roads']`: class by class (`ROAD_ORDER`), data order within a class. */
    const val ROAD = 4

    /** `regions.py`'s `draw_over`: rail, big roads by layer, runways by kind, marine works, a park's edge (class by source). */
    const val OVER = 5

    /** The marks over the ground: tram tracks. */
    const val MARKS = 6

    const val MAX_CLASS = 63
    const val MAX_ORDER = (1 shl 22) - 1

    fun of(stage: Int, klass: Int, order: Int): Long {
        require(stage in 1..15) { "stage $stage" }
        require(klass in 0..MAX_CLASS) { "class $klass" }
        require(order in 0..MAX_ORDER) { "a ground order of $order passes ${MAX_ORDER + 1}" }
        return (stage.toLong() shl 28) or (klass.toLong() shl 22) or order.toLong()
    }

    fun stage(key: Long): Int = (key ushr 28).toInt()
    fun klass(key: Long): Int = ((key ushr 22) and 63).toInt()
    fun order(key: Long): Int = (key and MAX_ORDER.toLong()).toInt()
}
