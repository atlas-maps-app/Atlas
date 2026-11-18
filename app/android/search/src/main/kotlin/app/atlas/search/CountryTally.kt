package app.atlas.search

/**
 * The countries a store's places and address points name (`addr:country`), by cell: the country of the map at a point,
 * as the store's own data tells it, is the commonest named in its cell, else in the cells around, else the country the
 * store is of, where it is one country's, else the commonest in the whole store. The app reads the regions on its phone
 * so (`HereContext` in :app), before the engine's coarse boxes: a pack of Germany names Germany in Cologne, where the
 * boxes say the Netherlands.
 */
internal class CountryTally {
    private val cells = HashMap<Long, HashMap<String, Int>>()
    private val all = HashMap<String, Int>()

    /** One more place or address point at [lat], [lon] (E7) naming [country]. */
    fun add(lat: Int, lon: Int, country: String) {
        cells.getOrPut(cell(Math.floorDiv(lat, CELL), Math.floorDiv(lon, CELL))) { HashMap(2) }.merge(country, 1, Int::plus)
        all.merge(country, 1, Int::plus)
    }

    /**
     * The commonest country named in the cell of [lat], [lon] (E7), else in the eight around it, else [region] (the
     * country the store is of), else anywhere; null when none is.
     */
    fun at(lat: Int, lon: Int, region: String? = null): String? {
        val y = Math.floorDiv(lat, CELL)
        val x = Math.floorDiv(lon, CELL)
        cells[cell(y, x)]?.let { return commonest(it) }
        val around = HashMap<String, Int>()
        for (dy in -1..1) for (dx in -1..1) cells[cell(y + dy, x + dx)]?.forEach { (c, n) -> around.merge(c, n, Int::plus) }
        return commonest(around) ?: region ?: commonest(all)
    }

    /** The cell of [lat], [lon] (E7), for [atCell]: the country at a point is its cell's. */
    fun cellOf(lat: Int, lon: Int): Long = cell(Math.floorDiv(lat, CELL), Math.floorDiv(lon, CELL))

    /** [at] a point of [cell] ([cellOf]). */
    fun atCell(cell: Long, region: String? = null): String? = at((cell shr 32).toInt() * CELL, cell.toInt() * CELL, region)

    /** The country named most; of as many, the first by its code. */
    private fun commonest(counts: Map<String, Int>): String? =
        counts.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key

    private fun cell(y: Int, x: Int): Long = (y.toLong() shl 32) or (x.toLong() and 0xFFFFFFFFL)

    companion object {
        /** A cell's side in E7: 0.05°, about 5.5 km north to south. */
        const val CELL = 500_000
    }
}
