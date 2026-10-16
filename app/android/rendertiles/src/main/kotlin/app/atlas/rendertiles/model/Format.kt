package app.atlas.rendertiles.model

/**
 * Format 3: render tiles in D1's pack container (plan-vector Appendix A). This file is the specification the core's
 * reader (`CP/tiles/`) follows; [Schema] lists every layer's columns, [RenderHead] the `rhead` section and [Reach] the
 * `reach` section.
 *
 * **The container** is D1's, unchanged (`render/data/TiledPackWriter`): the 20-byte header (`ATLASPCK`, a zero byte,
 * the version byte 3, two zero bytes, the manifest's and the directory's lengths as big-endian u32), the JSON manifest,
 * the section directory (per section: its name as a varint length and UTF-8, then big-endian u64 offset, u64 stored
 * length, u64 raw length, u32 CRC-32 of the stored bytes, and a method byte), then the sections in the directory's
 * order: [SECTION_TILES], [SECTION_REACH], [SECTION_CHUNKS], [SECTION_HEAD], [SECTION_STYLE]. A reader finds sections by
 * name and ignores a section it does not know.
 * - `tiles`, stored: a varint chunk count, then per chunk the zoom (one byte), column and row, stored and raw lengths and
 *   feature count (varints) and the 32-byte SHA-256 of the decompressed chunk. Offsets follow from the stored lengths.
 * - `reach`, stored: how far each chunk's features reach, in the chunk table's order ([Reach]), so a reader picks the
 *   chunks a view needs without decompressing any.
 * - `chunks`, method [METHOD_ZSTD]: one zstd frame per tile, in level order (finest first) and along a Hilbert curve
 *   within a level. Concatenated frames are themselves a valid zstd stream of the section's raw length. The section's
 *   CRC-32 covers every frame; a reader that fetches chunks by ranges (D3) cannot check it and relies on each chunk's
 *   SHA-256 instead.
 * - `rhead`, stored, and `style`, one zstd frame: binary, little-endian ([RenderHead], [StyleTables]); the core needs
 *   no JSON parser.
 *
 * **Everything inside the payload is little-endian.** Varints are unsigned LEB128 of at most 10 bytes (64 bits); signed
 * values are zig-zag coded.
 *
 * **A tile** (one decompressed chunk, at most [MAX_TILE_BYTES]):
 * ```
 *  0  u32  magic TILE_MAGIC ("ATRT")
 *  4  u16  payload version (PAYLOAD)
 *  6  u8   level (the tile's zoom, at most MAX_LEVEL)
 *  7  u8   cells per side n (cellsPerSide(level): 8 at L14, 4 at L12)
 *  8  u32  x (column), 12 u32 y (row, 0 at the north), each below 2^level
 * 16  i32  south, west, north, east: the tile's bounds on the grid (Grid.tileBox)
 * 32  u32  string table offset, u32 its length
 * 40  u32  cell table offset, u32 its length
 * 48  u16  layer count L, u16 zero
 * 52  L × 16 bytes: u8 layer id (Layer), u8 zero, u16 column count, u32 feature count (at most MAX_FEATURES),
 *      u32 offset, u32 length
 * ```
 * Offsets count from the tile's first byte; every block lies inside the tile (the reader checks each before use). The
 * header's level, column and row equal the chunk table's. The reader takes the bounds from the header; recomputing them
 * (as the reference reader does, to check the writer) needs `StrictMath`'s `sinh` and `atan`.
 * - The string table: a varint count, then per string a varint byte length and its UTF-8 bytes. Columns refer to
 *   strings by index.
 * - The cell table: for each of the n × n cells, row by row from the north-west: a varint count of the features whose
 *   reference point lies in it (all layers); when not zero, the grid box of what those features draw as four zig-zag
 *   varints, south − the tile's south, west − the tile's west, north − the tile's south, east − the tile's west, the
 *   highest point they draw in centimetres above the street (zig-zag), and for each layer of the directory, in its
 *   order, the varint count of that layer's features in the cell. A layer's features are sorted by cell, then by source
 *   order, so these counts give each cell's range in every layer.
 * - A layer: its columns, each a varint string index (the column's name), a codec byte ([Codec]), a varint byte length
 *   and the column's bytes.
 *
 * **What a reader does not know** it skips, by one rule: a layer whose id it does not know (its features are counted in
 * the cells all the same); a column whose name its schema does not have, whatever its codec byte (the length skips
 * it); a section or a `style` table it does not know. A column its schema has, coded with another codec than the
 * schema's (an unknown codec byte included), is an error, as is a payload version it does not know. A missing column
 * stands for its default in every feature (null, zero, empty; [Codec.POINT] and [Codec.F64] columns are never missing).
 *
 * **Bounds** a reader checks before it allocates: a level at most [MAX_LEVEL], a column and row below 2^level, n equal to
 * [cellsPerSide], a raw chunk of at most [MAX_TILE_BYTES], at most [MAX_FEATURES] features in a layer, a count or string
 * length no larger than the bytes left in its block. Deltas ([Codec.DELTA], [Codec.POINT], [Codec.GEOM], [Codec.IDS],
 * [Codec.NDELTA], [Codec.STRN]) are summed in 64-bit two's complement, wrapping (in C++, unsigned arithmetic): a writer
 * never relies on the wrap, and a coordinate outside the i32 range is an error.
 *
 * **Coordinates** are OSM's own 1e-7 degree integers ([Grid]). The core computes a vertex's metres in the pack's frame
 * exactly as the port does: `lat = q / 1e7` (a division), `x = (lon − lon0) · kx`, `z = −(lat − lat0) · kz`, with the
 * frame's doubles from `rhead`, so a source vertex lands bit for bit where the oracle puts it.
 *
 * **Boxes and tops.** A cell's box and top, and so a chunk's reach, bound what its features draw, not only their
 * vertices: a road's ribbon and pavements, a turbine's rotor, a crane's boom, a tree's crown, a chimney on a roof (the
 * renders' own defaults where a height is not mapped). Two things fall outside them, and a reader that culls by boxes
 * pads for both: shadows, which the sun casts beyond a feature by its top over the tangent of its elevation, and
 * reflections in the water, which mirror what stands within 16 m of a quay edge below the water's level.
 *
 * **Keep codes** ([Keep]): one byte per vertex of every simplified ring: how many of the ladder's positive tolerances
 * the vertex survives `regions.py`'s `simplify` at. At a view's or band's tolerance ([RenderHead]) the renders simplify
 * buildings, parts and areas only; land cover and water they draw whole at every tolerance, and their codes serve the
 * coarser levels beyond the port's views. A pack all of whose views and bands load at ladder index 0 (the living map,
 * [RenderHead.bands]) carries none.
 */
object Format {
    /** The container's format number (D1's container, render-tile payload). */
    const val VERSION = 3

    /** The payload's own version: `rhead`, `reach`, `style` and every tile carry it; a reader refuses one it does not know. */
    const val PAYLOAD = 1

    /** "ATRT", little-endian: the first four bytes of every tile. */
    const val TILE_MAGIC = 0x54525441

    /** "ATRH": the first four bytes of `rhead`. */
    const val HEAD_MAGIC = 0x48525441

    /** "ATRS": the first four bytes of `style`. */
    const val STYLE_MAGIC = 0x53525441

    /** "ATRR": the first four bytes of `reach`. */
    const val REACH_MAGIC = 0x52525441

    const val SECTION_TILES = "tiles"
    const val SECTION_REACH = "reach"
    const val SECTION_CHUNKS = "chunks"
    const val SECTION_HEAD = "rhead"
    const val SECTION_STYLE = "style"

    /** Section methods of the directory: stored, deflated (element packs), zstd (render tiles' chunks). */
    const val METHOD_STORED = 0
    const val METHOD_DEFLATE = 1
    const val METHOD_ZSTD = 2

    /** The manifest's `layout` for render tiles. */
    const val LAYOUT = "render-tiles"

    /** The most features a layer of one tile may hold (the densest tile of the canal ring holds about 14,000). */
    const val MAX_FEATURES = 1 shl 20

    /** The largest decompressed tile (the densest of the canal ring is under 1 MB). */
    const val MAX_TILE_BYTES = 4 shl 20

    /** The finest level a pack may hold (D1's `PackTiles.MAX_ZOOM`). */
    const val MAX_LEVEL = 24

    /** The tile header's fixed size, before the layer directory. */
    const val HEADER_BYTES = 52

    /** One entry of the layer directory. */
    const val LAYER_ENTRY_BYTES = 16

    /**
     * The levels a region's pack holds, finest first: L14 (8 × 8 cells of about 186 m) and L12 (4 × 4 cells). A living
     * pack holds L14 alone (`SceneToTiles.levelsOf`); `rhead` lists a pack's own.
     */
    val LEVELS: IntArray get() = intArrayOf(14, 12)

    /** Cells per side at [level]: 8 at L14 and finer, 4 at L12 and coarser. */
    fun cellsPerSide(level: Int): Int = if (level >= 14) 8 else 4
}

/**
 * The `reach` section: for every chunk, in the chunk table's order, the grid box of everything its features draw (the
 * union of its cells' boxes, [Format]'s *Boxes and tops*) and the highest point they draw. Features are stored whole in
 * the tile under their reference point, so a tile's reach can pass its bounds by a feature's length: a reader asks for
 * every chunk whose reach meets the view (with the margin the renders cull by), not only the tiles under it.
 *
 * Little-endian: u32 [Format.REACH_MAGIC], u16 [Format.PAYLOAD], u16 the entry size e (at least [ENTRY_BYTES]; a reader
 * reads the fields it knows and skips the rest), u32 the entry count (the chunk table's), then per chunk i32 south,
 * west, north, east on the grid and i32 the top in centimetres above the street.
 */
class Reach(val south: Int, val west: Int, val north: Int, val east: Int, val topCm: Int) {
    val box: IntArray get() = intArrayOf(south, west, north, east)

    override fun equals(other: Any?): Boolean = other is Reach && south == other.south && west == other.west && north == other.north && east == other.east && topCm == other.topCm
    override fun hashCode(): Int = ((south * 31 + west) * 31 + north) * 31 + east
    override fun toString(): String = "Reach($south, $west, $north, $east, top $topCm cm)"

    companion object {
        /** The bytes of one entry this payload writes. */
        const val ENTRY_BYTES = 20

        /** The section's header before its entries. */
        const val HEADER_BYTES = 12

        /** The reach of [tile]: the union of its cells' boxes and their highest top. */
        fun of(tile: RenderTile): Reach {
            var s = Int.MAX_VALUE
            var w = Int.MAX_VALUE
            var n = Int.MIN_VALUE
            var e = Int.MIN_VALUE
            var top = 0
            for (c in tile.cells) {
                val b = c.box ?: continue
                if (b[0] < s) s = b[0]
                if (b[1] < w) w = b[1]
                if (b[2] > n) n = b[2]
                if (b[3] > e) e = b[3]
                if (c.maxHeightCm > top) top = c.maxHeightCm
            }
            require(s != Int.MAX_VALUE) { "tile ${tile.level}/${tile.x}/${tile.y} has no features" }
            return Reach(s, w, n, e, top)
        }
    }
}

/** The profile of a pack: which of the renders' rule sets draws it (plan-vector D3, D15). */
enum class Profile(val code: Int, val label: String) {
    /** The Westerkerk's living map: `amsterdam.load` with `layer='split'`. */
    LIVING(0, "living"),

    /** Every other region of the renders: `regions.load` with its hooks. */
    REGION(1, "region"),

    /** `real.py`'s Williamsburg (K30). */
    WILLIAMSBURG(2, "williamsburg"),

    /** The world mid-zoom base (K20b). */
    WORLD_BASE(3, "world-base");

    companion object {
        fun of(code: Int): Profile = entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("no profile $code")
    }
}

/**
 * The layers of a tile (Appendix A). Ids are stable: a reader skips a layer it does not know.
 *
 * Every feature is stored whole in the tile under its reference point (objects, areas, land cover, roads, lines), so the
 * renders' rules that look at a whole ring or line (an area threshold, a mid-vertex width, the first drawn polygon of a
 * kind) see what the port sees; the `reach` section says how far each tile reaches. The water alone, whose rings can
 * span a region and which the renders only fill and clip by, is clipped at the tile's edges on the grid.
 */
enum class Layer(val id: Int, val label: String) {
    /** `S['buildings']`: footprints with keep codes and the loader's derived values. */
    BUILDING(1, "building"),

    /** `S['parts']`: building parts, the same columns as buildings. */
    PART(2, "part"),

    /** `S['areas']` and `S['land']`, whole (`source` says which). */
    GROUND(3, "ground"),

    /**
     * `S['water']` and `S['water_holes']`, clipped at the tile's edges. The renders test each ring whole (its area in
     * pixels, `> 0.2`): a reader adds the signed areas of the pieces that share `index` and `hole`. Every piece turns the
     * way its ring turns (the packer checks), so a piece's winding is its ring's.
     */
    WATER(4, "water"),

    /** The water's edges that carry a quay wall, as `water_edges` finds them, each in the tile of its midpoint. */
    QUAY(5, "quay"),

    /** `S['roads']`: centrelines with class, width and flags, whole. */
    ROAD(6, "road"),

    /** Tram tracks, rail, big roads, runways, power lines, marine works, a park's edge, whole. */
    LINE(7, "line"),

    /** `S['trees']`. */
    TREE(8, "tree"),

    /** The other nodes the renders draw from: lamps, furniture, bollards, doors (addresses), shops and cafés. */
    THING(9, "thing"),

    /** A region's specials: cranes, masts, towers, pylons, turbines. */
    ITEM(10, "item"),

    /** Canal names in source order, place nodes with their rank. */
    LABEL(11, "label"),

    /** Williamsburg's markings (K30). */
    MARKING(12, "marking"),

    /** The world mid-zoom base's built-up cells (K20b). */
    BUILT(13, "built"),

    /** A terrain tile's 65 × 65 heights (K28). */
    DEM(14, "dem"),

    /**
     * Buildings and parts whose loaded values differ at another of the ladder's tolerances: the loader derives some looks
     * from the simplified footprint (the land use under its centroid, the outline a part stands in), so a region's views
     * and bands at their own tolerance take these values instead. Each holds every attribute of its base feature.
     */
    VARIANT(15, "variant");

    companion object {
        fun of(id: Int): Layer? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Column codecs. Every column holds one value per feature of its layer, except where a codec says otherwise; a
 * nullable value is coded so that null is distinct from every value.
 */
enum class Codec(val id: Int) {
    /** One unsigned byte per feature. */
    U8(1),

    /** An unsigned varint per feature. */
    VARU(2),

    /** A zig-zag varint per feature (signed 64-bit). */
    VARZ(3),

    /** Zig-zag varint deltas from the previous feature's value (the first from 0). */
    DELTA(4),

    /**
     * A decimal per feature, metres and the like: a varint whose two low bits say what it is. 0: the value is the rest
     * (zig-zag) in hundredths, exactly (`v == n / 100.0`, and not −0); 1: null; 2: an 8-byte little-endian double follows
     * (any other value, NaN and −0 included).
     */
    CENTI(5),

    /** A nullable string: varint 0 for null, else the string's index + 1. */
    STR(6),

    /** A nullable boolean as one byte: 0 absent, 1 false, 2 true. */
    BOOL(7),

    /** Polygons and lines, each vertex shared with a neighbour written once: [app.atlas.rendertiles.codec.Geometry]. */
    GEOM(8),

    /**
     * Keep codes ([Keep]) of the [GEOM] column named like this one without `.keep`: a byte per vertex of every ring of at
     * least [Keep.MIN_POINTS] vertices, in order; a smaller ring's codes are not written, as `simplify` never touches it
     * (each is [Keep.ALL]).
     */
    KEEP(9),

    /**
     * One grid point per feature: two sub-blocks (each a varint length and its bytes), latitudes then longitudes, as
     * zig-zag varint deltas from the previous feature's point (the first from 0).
     */
    POINT(10),

    /** A feature's tags: a varint pair count, then key and value string indexes (items keep every tag). */
    TAGS(11),

    /** A nullable 64-bit integer (not `Long.MIN_VALUE`): varint 0 for null, else its zig-zag + 1. */
    NVARZ(12),

    /** A list of 64-bit integers per feature: a varint count, then zig-zag deltas (the first from 0). */
    IDS(13),

    /** A nullable list of strings per feature: varint 0 for null, else count + 1, then string indexes. */
    STRS(14),

    /**
     * A typed value per feature ([app.atlas.rendertiles.codec.Values]). No layer of this payload has such a column (a
     * field the schema lacks fails the build); `rhead` and `style` use the same typed values, with strings inline.
     */
    VALUE(15),

    /** One little-endian double per feature, its exact bits. */
    F64(16),

    /**
     * A nullable string that is often a number (house numbers): a varint, 0 for null, 2i + 1 for string i of the table,
     * and for a canonical decimal integer n (no sign, no leading zero) 2z + 2, z the zig-zag of n minus the column's
     * previous number (0 at the start).
     */
    STRN(17),

    /** A nullable 64-bit integer as a delta: varint 0 for null, else 1 + the zig-zag of it minus the column's previous non-null value (0 at the start). */
    NDELTA(18);

    companion object {
        fun of(id: Int): Codec? = entries.firstOrNull { it.id == id }
    }
}
