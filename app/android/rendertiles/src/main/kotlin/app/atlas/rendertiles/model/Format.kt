package app.atlas.rendertiles.model

/**
 * Format 3: render tiles in D1's pack container (plan-vector Appendix A). This file is the specification the core's
 * reader (`CP/tiles/`) follows; [Schema] lists every layer's columns.
 *
 * **The container** is D1's, unchanged (`render/data/TiledPackWriter`): the 20-byte header (`ATLASPCK`, a zero byte,
 * the version byte 3, two zero bytes, the manifest's and the directory's lengths as big-endian u32), the JSON manifest,
 * the section directory (per section: its name as a varint length and UTF-8, then big-endian u64 offset, u64 stored
 * length, u64 raw length, u32 CRC-32 of the stored bytes, and a method byte), then the sections in the directory's
 * order: [SECTION_TILES], [SECTION_CHUNKS], [SECTION_HEAD], [SECTION_STYLE].
 * - `tiles`, stored: a varint chunk count, then per chunk the zoom (one byte), column and row, stored and raw lengths and
 *   feature count (varints) and the 32-byte SHA-256 of the decompressed chunk. Offsets follow from the stored lengths.
 * - `chunks`, method [METHOD_ZSTD]: one zstd frame per tile, in level order (finest first) and along a Hilbert curve
 *   within a level. Concatenated frames are themselves a valid zstd stream of the section's raw length.
 * - `rhead`, stored, and `style`, one zstd frame: binary, little-endian ([RenderHead], [StyleTables]); the core needs
 *   no JSON parser.
 *
 * **Everything inside the payload is little-endian.** Varints are unsigned LEB128; signed values are zig-zag coded.
 *
 * **A tile** (one decompressed chunk):
 * ```
 *  0  u32  magic TILE_MAGIC ("ATRT")
 *  4  u16  payload version (PAYLOAD)
 *  6  u8   level (the tile's zoom)
 *  7  u8   cells per side n (8 at L14, 4 at L12)
 *  8  u32  x (column), 12 u32 y (row, 0 at the north)
 * 16  i32  south, west, north, east: the tile's bounds on the grid (Grid.tileBox)
 * 32  u32  string table offset, u32 its length
 * 40  u32  cell table offset, u32 its length
 * 48  u16  layer count L, u16 zero
 * 52  L × 16 bytes: u8 layer id (Layer), u8 zero, u16 column count, u32 feature count (at most MAX_FEATURES),
 *      u32 offset, u32 length
 * ```
 * Offsets count from the tile's first byte; every block lies inside the tile (the reader checks each before use).
 * - The string table: a varint count, then per string a varint byte length and its UTF-8 bytes. Columns refer to
 *   strings by index.
 * - The cell table: for each of the n × n cells, row by row from the north-west: a varint count of the features whose
 *   reference point lies in it (all layers); when not zero, the grid box of those features' whole extents as four
 *   zig-zag varints, south − the tile's south, west − the tile's west, north − the tile's south, east − the tile's west
 *   (a feature reaching past the tile widens its cell's box), their highest top in centimetres (zig-zag), and for each
 *   layer of the directory, in its order, the varint count of that layer's features in the cell. A layer's features are
 *   sorted by cell, then by source order, so these counts give each cell's range in every layer. A reader skips a layer
 *   whose id it does not know (its features counted in the cells all the same).
 * - A layer: its columns, each a varint string index (the column's name), a codec byte ([Codec]), a varint byte length
 *   and the column's bytes. A reader picks the columns it knows by name and skips the others.
 *
 * **Coordinates** are OSM's own 1e-7 degree integers ([Grid]). The core computes a vertex's metres in the pack's frame
 * exactly as the port does: `lat = q / 1e7` (a division), `x = (lon − lon0) · kx`, `z = −(lat − lat0) · kz`, with the
 * frame's doubles from `rhead`, so a source vertex lands bit for bit where the oracle puts it.
 *
 * **Keep codes** ([Keep]): one byte per vertex of every simplified ring: how many of the ladder's positive tolerances
 * the vertex survives `regions.py`'s `simplify` at.
 */
object Format {
    /** The container's format number (D1's container, render-tile payload). */
    const val VERSION = 3

    /** The payload's own version: `rhead`, `style` and every tile carry it; a reader refuses one it does not know. */
    const val PAYLOAD = 1

    /** "ATRT", little-endian: the first four bytes of every tile. */
    const val TILE_MAGIC = 0x54525441

    /** "ATRH": the first four bytes of `rhead`. */
    const val HEAD_MAGIC = 0x48525441

    /** "ATRS": the first four bytes of `style`. */
    const val STYLE_MAGIC = 0x53525441

    const val SECTION_TILES = "tiles"
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

    /** The tile header's fixed size, before the layer directory. */
    const val HEADER_BYTES = 52

    /** One entry of the layer directory. */
    const val LAYER_ENTRY_BYTES = 16

    /**
     * The levels a region's pack holds, finest first: L14 (8 × 8 cells of about 186 m) and L12 (4 × 4 cells). A living
     * pack holds L14 alone (`SceneToTiles.levelsOf`); `rhead` lists a pack's own.
     */
    val LEVELS: IntArray get() = intArrayOf(14, 12)

    /** Cells per side at [level]: 8 at L14, 4 at L12 and coarser. */
    fun cellsPerSide(level: Int): Int = if (level >= 14) 8 else 4
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
 * Objects (buildings, parts, trees, things, items, labels and quay edges) are stored whole in the tile under their
 * reference point; so are simplified areas, roads and lines, whose decorations run along their whole length. Land
 * cover and water, which the renders never simplify and which can span many tiles, are clipped exactly at the tile's
 * edges on the grid.
 */
enum class Layer(val id: Int, val label: String) {
    /** `S['buildings']`: footprints with keep codes and the loader's derived values. */
    BUILDING(1, "building"),

    /** `S['parts']`: building parts, the same columns as buildings. */
    PART(2, "part"),

    /** `S['areas']` (whole, keep codes) and `S['land']` (clipped at the tile's edges). */
    GROUND(3, "ground"),

    /** `S['water']` and `S['water_holes']`, clipped at the tile's edges. */
    WATER(4, "water"),

    /** The water's edges that carry a quay wall, as `water_edges` finds them, each in the tile of its midpoint. */
    QUAY(5, "quay"),

    /** `S['roads']`: centrelines with class, width and flags, whole. */
    ROAD(6, "road"),

    /** Tram tracks, rail, big roads, runways, power lines, marine works, a park's edge, gantries and conveyors, whole. */
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

    /** A feature's tags (the keys the renders read, [Schema.renderTags]): a varint pair count, then key and value string indexes. */
    TAGS(11),

    /** A nullable 64-bit integer (not `Long.MIN_VALUE`): varint 0 for null, else its zig-zag + 1. */
    NVARZ(12),

    /** A list of 64-bit integers per feature: a varint count, then zig-zag deltas (the first from 0). */
    IDS(13),

    /** A nullable list of strings per feature: varint 0 for null, else count + 1, then string indexes. */
    STRS(14),

    /** A typed value per feature ([Value]): the loader's other fields, as one map per feature (null when it has none). */
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
