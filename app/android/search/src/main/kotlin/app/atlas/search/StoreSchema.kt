package app.atlas.search

import app.atlas.model.OsmId
import app.atlas.model.OsmType

/**
 * The tables of a region's store file. Bump [VERSION] when they change: an older store is then rebuilt when it is
 * opened (an Overpass region is imported again), or refused when it is a pack ([PlaceStore.open] says so).
 *
 * Coordinates are integers of 1e-7 degree ([E7]). Tags are varint blobs over two dictionaries ([TagBlob]); the lookup
 * table holds only the keys searches filter on ([TagIndex]); pure address points live in a compact table of their
 * own under deduplicated streets ([Addresses]); trees are keyed by their position's Morton code ([Morton]).
 */
internal object StoreSchema {
    const val VERSION = 3

    /**
     * Changes when what goes into the text index changes (its words, kind tokens or importance): the index of a store
     * written by another version is then rebuilt from the places when a writer finishes.
     */
    const val TEXT_VERSION = 1

    /** The tokenizer of both text indexes: Atlas folds the text itself, so it only splits; `_` starts a kind token. */
    private const val TOKENIZE = "tokenize=\"unicode61 remove_diacritics 0 tokenchars '_'\""

    val CREATE = listOf(
        "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
        // The dictionaries: every tag key, and the values that repeat (kinds, operators, cities; preset, category,
        // brand and symbol ids).
        "CREATE TABLE IF NOT EXISTS tagkey(id INTEGER PRIMARY KEY, k TEXT NOT NULL UNIQUE)",
        "CREATE TABLE IF NOT EXISTS tagvalue(id INTEGER PRIMARY KEY, v TEXT NOT NULL UNIQUE)",
        // One row per place: its pin, its box as offsets from the pin (null for a point), what Atlas worked out from
        // its tags (dictionary ids), its tags blob, and what it carries besides (edit time, provider fields).
        """CREATE TABLE IF NOT EXISTS place(
            id INTEGER PRIMARY KEY,
            lat INTEGER NOT NULL, lon INTEGER NOT NULL,
            ds INTEGER, dw INTEGER, dn INTEGER, de INTEGER,
            preset INTEGER, category INTEGER, brand INTEGER, symbol INTEGER NOT NULL,
            tags BLOB NOT NULL, extra BLOB, edited INTEGER, source INTEGER)""",
        "CREATE VIRTUAL TABLE IF NOT EXISTS place_rt USING rtree_i32(id, west, east, south, north)",
        // The kinds and facets searches filter on, as dictionary ids; category and brand under negative keys.
        "CREATE TABLE IF NOT EXISTS lookup(k INTEGER NOT NULL, v INTEGER NOT NULL, place INTEGER NOT NULL, PRIMARY KEY(k, v, place)) WITHOUT ROWID",
        // Counts: places per (key, value) of the lookup table and per preset; places per key (v = -1) for every key.
        "CREATE TABLE IF NOT EXISTS stat(k INTEGER NOT NULL, v INTEGER NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(k, v)) WITHOUT ROWID",
        // Names in every language, operators and refs, the kind tokens, the address: folded, one column, rowids only.
        """CREATE VIRTUAL TABLE IF NOT EXISTS place_fts USING fts5(text, content='', columnsize=0, detail=none, prefix='2', $TOKENIZE)""",
        // Streets of the address points: a name (or `addr:place`) with its postcode, town and grid cell.
        """CREATE TABLE IF NOT EXISTS street(
            id INTEGER PRIMARY KEY, name TEXT NOT NULL, kind INTEGER NOT NULL, postcode TEXT, city INTEGER, country INTEGER,
            cell INTEGER NOT NULL, n INTEGER NOT NULL DEFAULT 0)""",
        "CREATE VIRTUAL TABLE IF NOT EXISTS street_rt USING rtree_i32(id, west, east, south, north)",
        """CREATE VIRTUAL TABLE IF NOT EXISTS street_fts USING fts5(text, content='', columnsize=0, detail=none, $TOKENIZE)""",
        // An address point: its street, its number, its position as offsets from its street's cell corner, and its
        // element's packed OSM id (its key: points stacked at one position are told apart, and keep their key across
        // imports).
        "CREATE TABLE IF NOT EXISTS address(street INTEGER NOT NULL, hn TEXT NOT NULL, lat INTEGER NOT NULL, lon INTEGER NOT NULL, osm INTEGER, PRIMARY KEY(street, hn)) WITHOUT ROWID",
        // The typo vocabulary: distinct words ordered by length, and per trigram the words that have it.
        "CREATE TABLE IF NOT EXISTS word(id INTEGER PRIMARY KEY, w TEXT NOT NULL, n INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS gram(g INTEGER PRIMARY KEY, words BLOB NOT NULL)",
        // Trees for the map engine: keyed by the Morton code of their position, with a tiny kind id.
        "CREATE TABLE IF NOT EXISTS treekind(id INTEGER PRIMARY KEY, leaf_type TEXT, genus TEXT)",
        "CREATE TABLE IF NOT EXISTS tree(id INTEGER PRIMARY KEY, kind INTEGER NOT NULL, height INTEGER)",
    )

    /** Every table of this version, for [PlaceStore.clear] and for dropping. */
    val TABLES = listOf(
        "place", "place_rt", "lookup", "stat", "place_fts", "street", "street_rt", "street_fts", "address", "word", "gram",
        "treekind", "tree", "tagkey", "tagvalue",
    )

    /** The tables of version 1 (version 2 has this version's names), dropped when such a store is rebuilt. */
    val OLD_TABLES = listOf("tag", "tagstat", "keystat", "tree_rt")

    /** Longest tag value held by its id in the lookup table; longer ones are held as present and checked on the row. */
    const val MAX_INDEXED_VALUE = 40

    /** Pseudo-keys of the lookup and statistics tables, below every tag key id. */
    const val CATEGORY_KEY = -1
    const val BRAND_KEY = -2
    const val PRESET_KEY = -3

    /** The value of a statistics row that counts a key's places, whatever their values. */
    const val ANY_VALUE = -1

    /** Store keys fit in 40 bits: OSM ids below 2^38 (about 2.7e11; today's node ids are near 1.3e10). */
    const val KEY_BITS = 40
    const val KEY_MASK = (1L shl KEY_BITS) - 1
    const val MAX_OSM_ID = (1L shl (KEY_BITS - 2)) - 1

    // An OSM id packs into one integer: id × 4 + type (0 node, 1 way, 2 relation); 3 marks a place from elsewhere.
    fun pack(id: OsmId): Long {
        require(id.id in 0..MAX_OSM_ID) { "OSM id ${id.id} is beyond what the store keys hold" }
        return (id.id shl 2) or id.type.ordinal.toLong()
    }

    fun unpack(key: Long): OsmId? {
        val t = (key and 3L).toInt()
        if (t == 3) return null
        return OsmId(OsmType.entries[t], key ushr 2)
    }

    /** The key of a place without an OSM id: a stable 38-bit hash of its source and position, tagged as foreign. */
    fun foreignKey(sourceKey: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in sourceKey) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return ((h ushr (64 - (KEY_BITS - 2))) shl 2) or 3L
    }

    /**
     * The text index's rowid of a place: its importance (0 to 255) inverted in the high bits, its key below, so the
     * index hands out the most important matches first and the key is the rowid's low bits.
     */
    fun textRow(key: Long, importance: Int): Long = ((255 - importance.coerceIn(0, 255)).toLong() shl KEY_BITS) or (key and KEY_MASK)

    fun keyOfTextRow(row: Long): Long = row and KEY_MASK
}
