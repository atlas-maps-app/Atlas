package app.atlas.render.data

/**
 * The tile pyramid of a tiled (format 2) pack: slippy-map Web Mercator tiles at a few zoom levels.
 *
 * An element lives in exactly one chunk: the tile under its box's centre at the finest level whose 3 × 3 block (the tile
 * and its 8 neighbours) holds the whole box; failing every level, the single world tile at zoom 0. A box query
 * therefore reads, at each level, only the tiles within one tile of the box. Elements without any position (a relation
 * none of whose members is in the extract) also go into the world tile, so that every element is somewhere.
 *
 * Tile numbers are computed with [StrictMath], so the packer and the phone place and find an element identically.
 */
object PackTiles {
    /** The default levels, finest first; the last is always the world tile. */
    val LEVELS: IntArray get() = intArrayOf(14, 12, 10, 6, 0)

    /** The finest level a pack may use (tile numbers then fit 24 bits). */
    const val MAX_ZOOM = 24

    /** Web Mercator's latitude limit; tiles clamp beyond it. */
    const val MAX_LAT = 85.0511287798066

    private const val RADIANS = Math.PI / 180.0

    /** The column of the tile at zoom [z] holding longitude [lon] (clamped to the world). */
    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        val t = StrictMath.floor((lon + 180.0) / 360.0 * n)
        return if (t < 0) 0 else if (t >= n) n - 1 else t.toInt()
    }

    /** The row (0 at the north) of the tile at zoom [z] holding latitude [lat] (clamped to Mercator's limit). */
    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        val la = if (lat > MAX_LAT) MAX_LAT else if (lat < -MAX_LAT) -MAX_LAT else lat
        val r = la * RADIANS
        val y = (1.0 - StrictMath.log(StrictMath.tan(r) + 1.0 / StrictMath.cos(r)) / Math.PI) / 2.0
        val t = StrictMath.floor(y * n)
        return if (t < 0) 0 else if (t >= n) n - 1 else t.toInt()
    }

    /**
     * Where an element with the box ([minLat], [minLon], [maxLat], [maxLon]) goes: the index into [levels] and the
     * tile, packed by [place]. Corners must not be NaN.
     */
    fun place(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, levels: IntArray): Long {
        val cLat = (minLat + maxLat) / 2
        val cLon = (minLon + maxLon) / 2
        for (li in 0 until levels.size - 1) {
            val z = levels[li]
            val x = tileX(cLon, z)
            val y = tileY(cLat, z)
            if (tileX(minLon, z) >= x - 1 && tileX(maxLon, z) <= x + 1 && tileY(maxLat, z) >= y - 1 && tileY(minLat, z) <= y + 1) return pack(li, x, y)
        }
        return pack(levels.size - 1, 0, 0)
    }

    /** The world tile, for an element without a box. */
    fun world(levels: IntArray): Long = pack(levels.size - 1, 0, 0)

    /** Level index, column and row in one long. */
    fun pack(level: Int, x: Int, y: Int): Long = (level.toLong() shl 48) or (x.toLong() shl 24) or y.toLong()

    fun levelOf(packed: Long): Int = (packed ushr 48).toInt()
    fun xOf(packed: Long): Int = ((packed ushr 24) and 0xFFFFFF).toInt()
    fun yOf(packed: Long): Int = (packed and 0xFFFFFF).toInt()

    /**
     * The position of tile ([x], [y]) along the Hilbert curve through the 2^[z] × 2^[z] tiles. A tile's position at a
     * coarser zoom c is this one shifted right by 2 (z − c) bits, so the curve visits each coarse tile's children together.
     */
    fun hilbert(z: Int, x0: Int, y0: Int): Long {
        var x = x0
        var y = y0
        var d = 0L
        var s = (1 shl z) shr 1
        val last = (1 shl z) - 1
        while (s > 0) {
            val rx = if (x and s != 0) 1 else 0
            val ry = if (y and s != 0) 1 else 0
            d += s.toLong() * s.toLong() * ((3 * rx) xor ry)
            if (ry == 0) {
                if (rx == 1) {
                    x = last - x
                    y = last - y
                }
                val t = x
                x = y
                y = t
            }
            s = s shr 1
        }
        return d
    }

    /** Checks a pack's levels: finest first, strictly coarser, ending at the world tile. */
    fun checkLevels(levels: IntArray) {
        if (levels.isEmpty() || levels.last() != 0) throw PackFormatException("a tiled pack's levels must end at zoom 0")
        for (i in levels.indices) {
            if (levels[i] < 0 || levels[i] > MAX_ZOOM) throw PackFormatException("zoom ${levels[i]} is outside 0..$MAX_ZOOM")
            if (i > 0 && levels[i] >= levels[i - 1]) throw PackFormatException("a tiled pack's levels must go from fine to coarse")
        }
    }
}

/**
 * One chunk of a tiled pack: its tile, where its bytes sit in the file ([offset], [stored] bytes deflated, [raw]
 * inflated), the elements it holds, and the SHA-256 of its inflated bytes. The hash depends on the elements alone, not
 * on the compressor, so a rebuild from unchanged data gives the same hash and a phone fetches only the chunks whose hash
 * changed.
 */
class TileChunk(
    val z: Int,
    val x: Int,
    val y: Int,
    val offset: Long,
    val stored: Int,
    val raw: Int,
    val count: Int,
    private val hash: ByteArray,
) {
    /** The SHA-256 of the inflated chunk, as a copy. */
    val sha256: ByteArray get() = hash.copyOf()

    /** The SHA-256 in lower-case hex. */
    val sha256Hex: String get() = hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    internal fun hashEquals(other: ByteArray): Boolean = hash.contentEquals(other)

    override fun equals(other: Any?): Boolean = other is TileChunk && z == other.z && x == other.x && y == other.y &&
        offset == other.offset && stored == other.stored && raw == other.raw && count == other.count && hash.contentEquals(other.hash)

    override fun hashCode(): Int = ((z * 31 + x) * 31 + y) * 31 + hash.contentHashCode()

    override fun toString(): String = "$z/$x/$y ($count elements, $stored bytes)"
}
