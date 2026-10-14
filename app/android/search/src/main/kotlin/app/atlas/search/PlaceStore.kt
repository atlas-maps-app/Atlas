package app.atlas.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.Text
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A tree for the map engine: where it stands, broadleaved or needleleaved, its genus and height when mapped. */
data class Tree(val id: Long, val location: LatLon, val leafType: String?, val genus: String?, val height: Double?)

/** What an import did, and how long it took. */
data class ImportStats(val elements: Long, val places: Int, val trees: Int, val skipped: Long, val millis: Long)

/**
 * One region's offline places: an SQLite file (through the bundled driver, so FTS5 and R*Tree are always there) with
 * the places and their tags, a tag index, an R*Tree on their boxes, a full-text index over names, kinds and addresses,
 * and a trigram vocabulary for typos. Fill it with [writer] (or [importOverpass]); read it as a [PlaceSource].
 *
 * One connection, guarded by a lock: safe from any thread, one statement at a time.
 */
class PlaceStore private constructor(val file: File?, private val conn: SQLiteConnection) : PlaceSource, Closeable {
    internal val lock = ReentrantLock()
    internal val statements = HashMap<String, SQLiteStatement>()
    internal var closed = false

    @Volatile
    private var cachedSize = -1

    @Volatile
    private var cachedBounds: BBox? = null

    @Volatile
    private var boundsKnown = false

    /** The region's name, as given at import. */
    val name: String get() = meta("name") ?: file?.nameWithoutExtension ?: "memory"

    /** The languages whose kind names went into the text index. */
    val languages: List<String> get() = meta("langs")?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    override val size: Int
        get() {
            if (cachedSize < 0) cachedSize = locked { long("SELECT count(*) FROM place").toInt() }
            return cachedSize
        }

    override val bounds: BBox?
        get() {
            if (!boundsKnown) {
                cachedBounds = locked {
                    stmt("SELECT min(south), min(west), max(north), max(east) FROM place") { st ->
                        if (st.step() && !st.isNull(0)) BBox(st.getDouble(1), st.getDouble(0), st.getDouble(3), st.getDouble(2)) else null
                    }
                }
                boundsKnown = true
            }
            return cachedBounds
        }

    // ------------------------------------------------------------------------------------------------- lookups

    override fun get(id: OsmId): PlaceRecord? = locked {
        stmt("SELECT $COLS FROM place p WHERE p.id = ?1") { st ->
            st.bindLong(1, StoreSchema.pack(id))
            if (st.step()) row(st) else null
        }
    }

    /** Places by their store keys, in no particular order. */
    internal fun byKeys(keys: Collection<Long>): List<PlaceRecord> {
        if (keys.isEmpty()) return emptyList()
        return locked {
            stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT value FROM json_each(?1))") { st ->
                st.bindText(1, keys.joinToString(",", "[", "]"))
                rows(st, Int.MAX_VALUE)
            }
        }
    }

    override fun inBox(box: BBox, limit: Int): List<PlaceRecord> = locked {
        stmt(
            "SELECT $COLS FROM place_rt r JOIN place p ON p.id = r.id " +
                "WHERE r.south <= ?1 AND r.north >= ?2 AND r.west <= ?3 AND r.east >= ?4 LIMIT ?5",
        ) { st ->
            bindBox(st, box, 1)
            st.bindLong(5, limit.toLong())
            rows(st, limit)
        }
    }

    override fun byTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> = locked {
        val b = box ?: WORLD
        if (value == null) {
            stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT t.place FROM tag t WHERE t.key = ?5) AND $BOX_WHERE LIMIT ?6") { st ->
                bindBox(st, b, 1)
                st.bindText(5, key)
                st.bindLong(6, limit.toLong())
                rows(st, limit)
            }
        } else if (value.length > StoreSchema.MAX_INDEXED_VALUE) {
            // indexed as '' (a long value): every long one, checked on the row
            stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT t.place FROM tag t WHERE t.key = ?5 AND t.value = '') AND $BOX_WHERE") { st ->
                bindBox(st, b, 1)
                st.bindText(5, key)
                rows(st, Int.MAX_VALUE).filter { r -> r.tags[key]?.let { v -> v == value || v.split(';').any { it.trim() == value } } == true }.take(limit)
            }
        } else {
            stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT t.place FROM tag t WHERE t.key = ?5 AND t.value = ?6) AND $BOX_WHERE LIMIT ?7") { st ->
                bindBox(st, b, 1)
                st.bindText(5, key)
                st.bindText(6, value)
                st.bindLong(7, limit.toLong())
                rows(st, limit)
            }
        }
    }

    override fun byCategory(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> = byColumn("category", ids, box, limit)

    override fun byBrand(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> = byColumn("brand", ids, box, limit)

    /** Places whose preset is one of [ids]. */
    fun byPreset(ids: Collection<String>, box: BBox? = null, limit: Int = PlaceSource.UNLIMITED): List<PlaceRecord> = byColumn("preset", ids, box, limit)

    private fun byColumn(column: String, ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> {
        if (ids.isEmpty()) return emptyList()
        return locked {
            stmt("SELECT $COLS FROM place p WHERE p.$column IN (SELECT value FROM json_each(?5)) AND $BOX_WHERE LIMIT ?6") { st ->
                bindBox(st, box ?: WORLD, 1)
                st.bindText(5, ids.joinToString(",", "[", "]") { jsonString(it) })
                st.bindLong(6, limit.toLong())
                rows(st, limit)
            }
        }
    }

    override fun estimate(key: String, value: String?): Int = locked {
        if (value == null) {
            stmt("SELECT n FROM keystat WHERE key = ?1") { st -> st.bindText(1, key); if (st.step()) st.getLong(0).toInt() else 0 }
        } else {
            val v = if (value.length > StoreSchema.MAX_INDEXED_VALUE) "" else value
            stmt("SELECT n FROM tagstat WHERE key = ?1 AND value = ?2") { st ->
                st.bindText(1, key); st.bindText(2, v)
                if (st.step()) st.getLong(0).toInt() else 0
            }
        }
    }

    override fun estimate(box: BBox): Int {
        val all = bounds ?: return 0
        if (!all.intersects(box)) return 0
        val w = (minOf(all.east, box.east) - maxOf(all.west, box.west)).coerceAtLeast(0.0)
        val h = (minOf(all.north, box.north) - maxOf(all.south, box.south)).coerceAtLeast(0.0)
        val area = all.width * all.height
        if (area <= 0) return size
        return (size * (w * h / area)).toInt().coerceAtLeast(1)
    }

    /** How many places carry [key]=[value] (or [key] at all) inside [box]: exact, for the "all 23" lines. */
    fun count(key: String, value: String?, box: BBox? = null): Int = locked {
        val b = box ?: WORLD
        // long values are indexed as '' (present): those are checked on the row
        if (value != null && value.length > StoreSchema.MAX_INDEXED_VALUE) return@locked byTag(key, value, box).size
        val sql = if (value == null) {
            "SELECT count(*) FROM place p WHERE p.id IN (SELECT t.place FROM tag t WHERE t.key = ?5) AND $BOX_WHERE"
        } else {
            "SELECT count(*) FROM place p WHERE p.id IN (SELECT t.place FROM tag t WHERE t.key = ?5 AND t.value = ?6) AND $BOX_WHERE"
        }
        stmt(sql) { st ->
            bindBox(st, b, 1)
            st.bindText(5, key)
            if (value != null) st.bindText(6, value)
            if (st.step()) st.getLong(0).toInt() else 0
        }
    }

    override fun text(text: String, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> {
        val words = SearchText.words(text)
        if (words.isEmpty()) return emptyList()
        val exact = match(words, fuzzy = false)
        val ids = LinkedHashSet<Long>()
        collect(exact, near, box, limit, ids)
        if (ids.size < FUZZY_BELOW) {
            val fuzzy = match(words, fuzzy = true)
            if (fuzzy != exact) collect(fuzzy, near, box, limit, ids)
        }
        return byKeys(ids)
    }

    private fun collect(match: String, near: LatLon?, box: BBox?, limit: Int, into: MutableSet<Long>) {
        locked {
            if (box != null) {
                textIds(match, box, limit, into)
            } else {
                textIds(match, null, limit, into)
                if (near != null) textIds(match, BBox.around(near, NEAR_TEXT_METRES), limit, into)
            }
        }
    }

    private fun textIds(match: String, box: BBox?, limit: Int, into: MutableSet<Long>) {
        val sql = if (box == null) {
            "SELECT rowid FROM place_fts WHERE place_fts MATCH ?1 ORDER BY rank LIMIT ?2"
        } else {
            "SELECT f.rowid FROM place_fts f JOIN place p ON p.id = f.rowid WHERE place_fts MATCH ?1 " +
                "AND p.south <= ?3 AND p.north >= ?4 AND p.west <= ?5 AND p.east >= ?6 ORDER BY f.rank LIMIT ?2"
        }
        stmt(sql) { st ->
            st.bindText(1, match)
            st.bindLong(2, limit.toLong())
            if (box != null) bindBox(st, box, 3)
            while (st.step()) into += st.getLong(0)
        }
    }

    /** The FTS5 expression for [words]: every word as a prefix, or with its near spellings from the vocabulary. */
    internal fun match(words: List<String>, fuzzy: Boolean): String = words.withIndex().joinToString(" AND ") { (i, w) ->
        val last = i == words.size - 1
        val self = if (w.length >= 2 || last) "\"$w\"*" else "\"$w\""
        if (!fuzzy) return@joinToString self
        val alternatives = fuzzyWords(w, prefix = last)
        if (alternatives.isEmpty()) self else "($self OR ${alternatives.joinToString(" OR ") { "\"$it\"" + if (last) "*" else "" }})"
    }

    /**
     * Words of the vocabulary within Damerau–Levenshtein distance 1 of [word] from 4 letters and 2 from 8 (or, while
     * [prefix], whose beginning is): found through shared trigrams, then checked exactly. Common words first.
     */
    fun fuzzyWords(word: String, prefix: Boolean = false, max: Int = 12): List<String> {
        val k = Text.typosAllowed(word.length)
        if (k == 0) return emptyList()
        val grams = Grams.of(word, prefix)
        if (grams.isEmpty()) return emptyList()
        val needed = (grams.size - 3 * k).coerceAtLeast(1)
        val maxLen = if (prefix) Int.MAX_VALUE else word.length + k
        val found = ArrayList<Triple<String, Int, Long>>()
        locked {
            stmt(
                "SELECT w.w, w.n, count(*) AS c FROM gram g JOIN word w ON w.id = g.word " +
                    "WHERE g.g IN (SELECT value FROM json_each(?1)) AND w.len BETWEEN ?2 AND ?3 " +
                    "GROUP BY g.word HAVING c >= ?4 ORDER BY c DESC, abs(w.len - ?5) LIMIT 1000",
            ) { st ->
                st.bindText(1, grams.joinToString(",", "[", "]"))
                st.bindLong(2, (word.length - k).toLong())
                st.bindLong(3, maxLen.toLong())
                st.bindLong(4, needed.toLong())
                st.bindLong(5, word.length.toLong())
                while (st.step()) {
                    val w = st.getText(0)
                    // the word itself, and while typing every word it begins, the plain query finds already
                    if (w == word || (prefix && w.startsWith(word))) continue
                    val d = if (prefix) Text.prefixDistance(word, w, k) else Text.distance(word, w, k)
                    if (d <= k) found += Triple(w, d, st.getLong(1))
                }
            }
        }
        // nearest spelling first, then the same first letter (rarely the one mistyped), then the closest length (a typo
        // rarely adds a syllable), then the commonest
        return found.sortedWith(
            compareBy<Triple<String, Int, Long>> { it.second }
                .thenBy { if (it.first[0] == word[0]) 0 else 1 }
                .thenBy { kotlin.math.abs(it.first.length - word.length) }
                .thenByDescending { it.third },
        ).take(max).map { it.first }
    }

    /** Whether [word] (folded) is in the vocabulary, or begins a word of it when [prefix]. */
    fun knows(word: String, prefix: Boolean = false): Boolean = locked {
        if (prefix) {
            stmt("SELECT 1 FROM word WHERE w >= ?1 AND w < ?2 LIMIT 1") { st ->
                st.bindText(1, word); st.bindText(2, word + '￿')
                st.step()
            }
        } else {
            stmt("SELECT 1 FROM word WHERE w = ?1") { st -> st.bindText(1, word); st.step() }
        }
    }

    /** Trees in [box], for the map engine. */
    fun trees(box: BBox, limit: Int = PlaceSource.UNLIMITED): List<Tree> = locked {
        stmt(
            "SELECT t.id, t.lat, t.lon, t.leaf_type, t.genus, t.height FROM tree_rt r JOIN tree t ON t.id = r.id " +
                "WHERE r.south <= ?1 AND r.north >= ?2 AND r.west <= ?3 AND r.east >= ?4 LIMIT ?5",
        ) { st ->
            bindBox(st, box, 1)
            st.bindLong(5, limit.toLong())
            val out = ArrayList<Tree>()
            while (st.step()) {
                out += Tree(
                    st.getLong(0), LatLon(st.getDouble(1), st.getDouble(2)),
                    if (st.isNull(3)) null else st.getText(3), if (st.isNull(4)) null else st.getText(4),
                    if (st.isNull(5)) null else st.getDouble(5),
                )
            }
            out
        }
    }

    /** A stored setting: "name", "langs", "imported", "schema"… */
    fun meta(key: String): String? = locked {
        stmt("SELECT value FROM meta WHERE key = ?1") { st -> st.bindText(1, key); if (st.step()) st.getText(0) else null }
    }

    // ------------------------------------------------------------------------------------------------- writing

    /**
     * A writer for this store: add elements or places, then [StoreWriter.finish] to rebuild the vocabulary and the
     * statistics. Adding an element again replaces it, so importing the same data twice leaves one copy.
     * [langs] are the languages whose preset and category names go into the text index besides the names themselves.
     */
    fun writer(langs: List<String> = DEFAULT_LANGS, regionName: String? = null): StoreWriter = StoreWriter(this, langs, regionName)

    /** Streams an Overpass JSON file into the store (see [OverpassImport]); returns what it did. */
    fun importOverpass(file: File, langs: List<String> = DEFAULT_LANGS, regionName: String? = null): ImportStats =
        writer(langs, regionName).use { w ->
            file.bufferedReader(Charsets.UTF_8, 1 shl 16).use { OverpassImport.read(it, w) }
            w.finish()
        }

    /** Adds [places] (from any source) and finishes. */
    fun importPlaces(places: Iterable<Place>, langs: List<String> = DEFAULT_LANGS, regionName: String? = null): ImportStats =
        writer(langs, regionName).use { w ->
            for (p in places) w.add(p)
            w.finish()
        }

    /**
     * True when the presets, categories or brands that classified the places have changed since the import (an app
     * update): [refresh] then brings the categories, brands, presets, symbols and kind names up to date.
     */
    val isStale: Boolean get() = size > 0 && meta("derived") != StoreWriter.fingerprint()

    /** Works out every place's category, brand, preset, symbol and indexed kind names again, in batches. */
    fun refresh(langs: List<String> = languages.ifEmpty { DEFAULT_LANGS }): ImportStats = writer(langs).use { w ->
        var after = Long.MIN_VALUE
        while (true) {
            val keys = locked {
                stmt("SELECT id FROM place WHERE id > ?1 ORDER BY id LIMIT ?2") { st ->
                    st.bindLong(1, after)
                    st.bindLong(2, REFRESH_BATCH.toLong())
                    val out = ArrayList<Long>(REFRESH_BATCH)
                    while (st.step()) out += st.getLong(0)
                    out
                }
            }
            if (keys.isEmpty()) break
            for (r in byKeys(keys)) w.add(PlaceRecord.of(r.place))
            after = keys.last()
        }
        w.finish()
    }

    /** Empties the store. */
    fun clear() = locked {
        for (t in listOf("place", "tag", "tagstat", "keystat", "place_rt", "word", "gram", "tree", "tree_rt")) exec("DELETE FROM $t")
        exec("DELETE FROM place_fts")
        invalidate()
    }

    internal fun invalidate() {
        cachedSize = -1
        boundsKnown = false
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        for (st in statements.values) runCatching { st.close() }
        statements.clear()
        conn.close()
    }

    // ------------------------------------------------------------------------------------------------- internals

    internal inline fun <T> locked(block: () -> T): T = lock.withLock {
        check(!closed) { "The store is closed" }
        block()
    }

    internal inline fun <T> stmt(sql: String, block: (SQLiteStatement) -> T): T {
        val st = statements.getOrPut(sql) { prepare(sql) }
        try {
            return block(st)
        } finally {
            st.reset()
            st.clearBindings()
        }
    }

    internal fun exec(sql: String) = conn.execSQL(sql)

    internal fun prepare(sql: String): SQLiteStatement = conn.prepare(sql)

    internal fun long(sql: String): Long = stmt(sql) { st -> if (st.step()) st.getLong(0) else 0L }

    internal val inTransaction: Boolean get() = conn.inTransaction()

    private fun rows(st: SQLiteStatement, limit: Int): List<PlaceRecord> {
        val out = ArrayList<PlaceRecord>()
        while (out.size < limit && st.step()) out += row(st)
        return out
    }

    private fun row(st: SQLiteStatement): PlaceRecord {
        val key = st.getLong(0)
        val lat = st.getDouble(1)
        val lon = st.getDouble(2)
        val south = st.getDouble(3)
        val west = st.getDouble(4)
        val north = st.getDouble(5)
        val east = st.getDouble(6)
        val tags = TagsJson.read(st.getText(7))
        val source = st.getText(12)
        val extra = if (st.isNull(13)) emptyMap() else TagsJson.read(st.getText(13))
        val box = if (south == north && west == east) null else BBox(west, south, east, north)
        val place = Place(StoreSchema.unpack(key), LatLon(lat, lon), tags, box, source, extra)
        return PlaceRecord(
            place,
            category = if (st.isNull(8)) null else st.getText(8),
            brand = if (st.isNull(9)) null else st.getText(9),
            preset = if (st.isNull(10)) null else st.getText(10),
            symbol = st.getText(11),
        )
    }

    private fun bindBox(st: SQLiteStatement, b: BBox, from: Int) {
        st.bindDouble(from, b.north)
        st.bindDouble(from + 1, b.south)
        st.bindDouble(from + 2, b.east)
        st.bindDouble(from + 3, b.west)
    }

    private fun setUp() {
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")
        exec("PRAGMA temp_store=MEMORY")
        exec("PRAGMA cache_size=-16000")
        val version = runCatching { stmt("SELECT value FROM meta WHERE key = 'schema'") { st -> if (st.step()) st.getText(0).toIntOrNull() else null } }.getOrNull()
        if (version != null && version != StoreSchema.VERSION) {
            for (s in statements.values) runCatching { s.close() }
            statements.clear()
            for (t in StoreSchema.DROP) exec("DROP TABLE IF EXISTS $t")
        }
        for (sql in StoreSchema.CREATE) exec(sql)
        exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '${StoreSchema.VERSION}')")
        // ORDER BY rank: names weigh most, then operator and cuisine, then addresses, then kinds.
        exec("INSERT INTO place_fts(place_fts, rank) VALUES ('rank', 'bm25(10.0, 4.0, 1.5, 3.0)')")
    }

    companion object {
        /** The languages a store indexes kind names in when the app does not say: Atlas's four search languages. */
        val DEFAULT_LANGS = listOf("en", "nl", "de", "fr")

        private const val FUZZY_BELOW = 5
        private const val REFRESH_BATCH = 5_000
        private const val NEAR_TEXT_METRES = 5_000.0
        private val WORLD = BBox(-180.0, -90.0, 180.0, 90.0)

        internal const val COLS = "p.id, p.lat, p.lon, p.south, p.west, p.north, p.east, p.tags, p.category, p.brand, p.preset, p.symbol, p.source, p.extra"
        private const val BOX_WHERE = "p.south <= ?1 AND p.north >= ?2 AND p.west <= ?3 AND p.east >= ?4"

        private val driver by lazy { BundledSQLiteDriver() }

        /** Opens (or creates) the store in [file]. */
        fun open(file: File): PlaceStore {
            file.absoluteFile.parentFile?.mkdirs()
            return PlaceStore(file, driver.open(file.absolutePath)).also { s -> s.locked { s.setUp() } }
        }

        /** A store that lives in memory only, for tests and online results. */
        fun inMemory(): PlaceStore = PlaceStore(null, driver.open(":memory:")).also { s -> s.locked { s.setUp() } }

        private fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
            append('"')
        }
    }
}
