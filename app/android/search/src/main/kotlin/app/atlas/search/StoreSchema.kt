package app.atlas.search

import app.atlas.model.OsmId
import app.atlas.model.OsmType

/** The tables of a region's store file. Bump [VERSION] when they change: an older file is then rebuilt on import. */
internal object StoreSchema {
    const val VERSION = 1

    val CREATE = listOf(
        "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID",
        // One row per place; the box is the point itself for nodes. Tags are a JSON object.
        """CREATE TABLE IF NOT EXISTS place(
            id INTEGER PRIMARY KEY,
            lat REAL NOT NULL, lon REAL NOT NULL,
            south REAL NOT NULL, west REAL NOT NULL, north REAL NOT NULL, east REAL NOT NULL,
            name TEXT, category TEXT, brand TEXT, preset TEXT, symbol TEXT NOT NULL,
            tags TEXT NOT NULL, source TEXT NOT NULL DEFAULT 'osm', extra TEXT)""",
        "CREATE INDEX IF NOT EXISTS place_category ON place(category) WHERE category IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS place_brand ON place(brand) WHERE brand IS NOT NULL",
        "CREATE INDEX IF NOT EXISTS place_preset ON place(preset) WHERE preset IS NOT NULL",
        // Every tag of every place, `;` lists also by their parts; values over 40 characters are kept as ''.
        "CREATE TABLE IF NOT EXISTS tag(key TEXT NOT NULL, value TEXT NOT NULL, place INTEGER NOT NULL, PRIMARY KEY(key, value, place)) WITHOUT ROWID",
        "CREATE INDEX IF NOT EXISTS tag_place ON tag(place)",
        "CREATE TABLE IF NOT EXISTS tagstat(key TEXT NOT NULL, value TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY(key, value)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS keystat(key TEXT PRIMARY KEY, n INTEGER NOT NULL) WITHOUT ROWID",
        "CREATE VIRTUAL TABLE IF NOT EXISTS place_rt USING rtree(id, south, north, west, east)",
        // Text already folded by Atlas (lower case, no accents), so the tokenizer only splits it.
        """CREATE VIRTUAL TABLE IF NOT EXISTS place_fts USING fts5(name, other, terms, addr,
            content='', contentless_delete=1, tokenize="unicode61 remove_diacritics 2", prefix='2 3')""",
        // The vocabulary and its trigrams, for typos.
        "CREATE TABLE IF NOT EXISTS word(id INTEGER PRIMARY KEY, w TEXT NOT NULL UNIQUE, len INTEGER NOT NULL, n INTEGER NOT NULL)",
        "CREATE TABLE IF NOT EXISTS gram(g INTEGER NOT NULL, word INTEGER NOT NULL, PRIMARY KEY(g, word)) WITHOUT ROWID",
        // Trees, light, for the map engine.
        "CREATE TABLE IF NOT EXISTS tree(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL, leaf_type TEXT, genus TEXT, height REAL)",
        "CREATE VIRTUAL TABLE IF NOT EXISTS tree_rt USING rtree(id, south, north, west, east)",
    )

    val DROP = listOf("meta", "place", "tag", "tagstat", "keystat", "place_rt", "place_fts", "word", "gram", "tree", "tree_rt")

    /** Longest tag value kept in the tag index; longer ones are kept as '' (present) and checked on the row. */
    const val MAX_INDEXED_VALUE = 40

    // An OSM id packs into one integer: id × 4 + type (0 node, 1 way, 2 relation); 3 marks a place from elsewhere.
    fun pack(id: OsmId): Long = (id.id shl 2) or id.type.ordinal.toLong()

    fun unpack(key: Long): OsmId? {
        val t = (key and 3L).toInt()
        if (t == 3) return null
        return OsmId(OsmType.entries[t], key ushr 2)
    }

    /** The key of a place without an OSM id: a stable hash of its source and position, tagged as foreign. */
    fun foreignKey(sourceKey: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (c in sourceKey) {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return ((h ushr 3) shl 2) or 3L
    }
}
