package app.atlas.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.places.Presets
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A tree for the map engine: where it stands, broadleaved or needleleaved, its genus and height when mapped. */
data class Tree(val id: Long, val location: LatLon, val leafType: String?, val genus: String?, val height: Double?)

/** What an import did, and how long it took; [addresses] are the address points it kept apart from the places. */
data class ImportStats(val elements: Long, val places: Int, val trees: Int, val skipped: Long, val millis: Long, val addresses: Int = 0)

/** A store this version of Atlas cannot read: a pack built for another store schema. The region must be downloaded again. */
class StoreVersionException(message: String) : IOException(message)

/**
 * One region's offline places: an SQLite file (through the bundled driver, so FTS5 and R*Tree are always there) with
 * the places and their tags as compact blobs, a lookup table of the kinds and facets searches filter on, an R*Tree
 * on their boxes, a full-text index over names, kinds and addresses, and a trigram vocabulary for typos. Pure address
 * points live apart, under their streets, and are found by street and number ([text]), by tag ([byTag]) and by box
 * ([inBox], [addresses], [nearestAddress]), though not by id ([get]); trees live in a light table of their own
 * ([trees]). Fill it with [writer] (or [importOverpass]); read it as a [PlaceSource].
 *
 * One connection, guarded by a lock: safe from any thread, one statement at a time.
 */
class PlaceStore private constructor(val file: File?, private val conn: SQLiteConnection) : PlaceSource, Closeable {
    internal val lock = ReentrantLock()
    internal val statements = HashMap<String, SQLiteStatement>()
    internal var closed = false
    internal val dict = Dictionaries(this)
    internal val vocabulary = Vocabulary(this)
    private val addressBook = AddressBook(this)

    @Volatile
    private var cachedPlaces = -1

    @Volatile
    private var cachedAddresses = -1

    @Volatile
    private var cachedBounds: BBox? = null

    @Volatile
    private var boundsKnown = false

    private var kinds: KindWords? = null
    private var kindLangs: List<String>? = null
    private var treeKinds: Map<Long, Pair<String?, String?>>? = null

    /** The region's name, as given at import. */
    val name: String get() = meta("name") ?: file?.nameWithoutExtension ?: "memory"

    /** The languages whose kind names the text index answers to. */
    val languages: List<String> get() = meta("langs")?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    /** How many places it holds, its address points included (all that [inBox] finds); [addressCount] counts those. */
    override val size: Int
        get() = placeCount + addressCount

    /** How many places it holds besides its address points. */
    val placeCount: Int
        get() {
            if (cachedPlaces < 0) cachedPlaces = locked { counted("places", "SELECT count(*) FROM place") }
            return cachedPlaces
        }

    /** How many address points it holds besides its places. */
    val addressCount: Int
        get() {
            if (cachedAddresses < 0) cachedAddresses = locked { counted("addresses", "SELECT count(*) FROM address") }
            return cachedAddresses
        }

    override val bounds: BBox?
        get() {
            if (!boundsKnown) {
                cachedBounds = locked { meta("bounds")?.let(::boundsOf) ?: computedBounds() }
                boundsKnown = true
            }
            return cachedBounds
        }

    // ------------------------------------------------------------------------------------------------- lookups

    override fun get(id: OsmId): PlaceRecord? {
        if (id.id !in 0..StoreSchema.MAX_OSM_ID) return null
        return locked {
            stmt("SELECT $COLS FROM place p WHERE p.id = ?1") { st ->
                st.bindLong(1, StoreSchema.pack(id))
                if (st.step()) row(st) else null
            }
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

    /** Places whose box meets [box], then the address points in it ([addresses]), at most [limit] in all. */
    override fun inBox(box: BBox, limit: Int): List<PlaceRecord> = locked {
        val places = stmt("SELECT $COLS FROM place_rt r JOIN place p ON p.id = r.id WHERE $RT_WHERE LIMIT ?5") { st ->
            bindRt(st, box, 1)
            st.bindLong(5, limit.toLong())
            rows(st, limit)
        }
        if (places.size >= limit) places else places + addressBook.inBox(box, limit - places.size)
    }

    override fun byTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> {
        val places = placesByTag(key, value, box, limit)
        if (!Addresses.isAddressKey(key) || places.size >= limit) return places
        return places + locked { addressBook.byTag(key, value, box, limit - places.size) }
    }

    private fun placesByTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> = locked {
        val kid = dict.findKey(key)
        if (kid < 0) return@locked emptyList()
        if (TagIndex.holds(key)) {
            if (value == null) return@locked lookup(kid, null, box, limit)
            if (TagIndex.holdsValue(key, value)) {
                val vid = dict.findValue(value)
                return@locked if (vid < 0) emptyList() else lookup(kid, vid, box, limit)
            }
            // a long value, or a key held as present only: its present rows, checked on the place
            return@locked lookup(kid, TagIndex.PRESENT, box, Int.MAX_VALUE).asSequence().filter { has(it.tags, key, value) }.take(limit).toList()
        }
        if (value != null && SearchText.isTextKey(key)) {
            val words = SearchText.indexWords(value)
            if (words.isNotEmpty()) {
                val ids = LinkedHashSet<Long>()
                textIds(words.joinToString(" AND ") { "\"$it\"" }, box, Int.MAX_VALUE, ids)
                return@locked byKeys(ids).asSequence().filter { has(it.tags, key, value) }.take(limit).toList()
            }
        }
        scan(kid, box, limit) { has(it.tags, key, value) }
    }

    /**
     * True when places with [key] (= [value] when given) are found through an index: the lookup table, or the text
     * indexes for a name or an address's street, town or postcode. Any other tag is found by reading every place of
     * the box (or of the store); so is a house number, which no index of the address points holds.
     */
    internal fun indexes(key: String, value: String?): Boolean = TagIndex.holds(key) ||
        (value != null && key != Addresses.HOUSENUMBER && SearchText.isTextKey(key) && SearchText.indexWords(value).isNotEmpty())

    override fun byCategory(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> =
        byPseudoKey(StoreSchema.CATEGORY_KEY, ids, box, limit)

    override fun byBrand(ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> =
        byPseudoKey(StoreSchema.BRAND_KEY, ids, box, limit)

    private fun byPseudoKey(k: Int, ids: Collection<String>, box: BBox?, limit: Int): List<PlaceRecord> {
        if (ids.isEmpty()) return emptyList()
        return locked {
            val vids = ids.map { dict.findValue(it) }.filter { it >= 0 }
            if (vids.isEmpty()) return@locked emptyList()
            val where = if (box == null) "" else " AND $BOX_WHERE"
            stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT place FROM lookup WHERE k = ?5 AND v IN (SELECT value FROM json_each(?6)))$where LIMIT ?7") { st ->
                if (box != null) bindBox(st, box, 1)
                st.bindLong(5, k.toLong())
                st.bindText(6, vids.joinToString(",", "[", "]"))
                st.bindLong(7, limit.toLong())
                rows(st, limit)
            }
        }
    }

    /** Places whose preset is one of [ids]: found through the presets' own tags, then checked. */
    fun byPreset(ids: Collection<String>, box: BBox? = null, limit: Int = PlaceSource.UNLIMITED): List<PlaceRecord> {
        val want = ids.toSet()
        val out = LinkedHashMap<Long, PlaceRecord>()
        for (id in want) {
            val tag = Presets.byId(id)?.tags?.entries?.firstOrNull { (k, v) -> v != "*" && TagIndex.holds(k) && TagIndex.holdsValue(k, v) }
            val found = if (tag != null) placesByTag(tag.key, tag.value, box, Int.MAX_VALUE) else locked { scan(-1, box, Int.MAX_VALUE) { it.preset in want } }
            for (r in found) if (r.preset in want) out[keyOf(r)] = r
            if (out.size >= limit) break
        }
        return out.values.take(limit)
    }

    override fun estimate(key: String, value: String?): Int = locked {
        val kid = dict.findKey(key)
        if (kid < 0) return@locked 0
        if (value == null) return@locked stat(kid, StoreSchema.ANY_VALUE)
        val address = if (Addresses.isAddressKey(key)) addressBook.estimate(key, value) else 0
        val places = when {
            TagIndex.holds(key) && TagIndex.holdsValue(key, value) -> dict.findValue(value).let { if (it < 0) 0 else stat(kid, it) }
            TagIndex.holds(key) -> stat(kid, TagIndex.PRESENT)
            SearchText.isTextKey(key) -> SearchText.indexWords(value).takeIf { it.isNotEmpty() }?.let { words ->
                stmt("SELECT count(*) FROM place_fts WHERE place_fts MATCH ?1") { st ->
                    st.bindText(1, words.joinToString(" AND ") { "\"$it\"" })
                    if (st.step()) st.getLong(0).toInt() else 0
                }
            } ?: 0
            else -> stat(kid, StoreSchema.ANY_VALUE)
        }
        places + address
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

    /** How many places and address points carry [key]=[value] (or [key] at all) inside [box]: exact, for the "all 23" lines. */
    fun count(key: String, value: String?, box: BBox? = null): Int = locked {
        val address = if (Addresses.isAddressKey(key)) addressBook.count(key, value, box) else 0
        val kid = dict.findKey(key)
        if (kid < 0) return@locked address
        val places = if (TagIndex.holds(key) && (value == null || TagIndex.holdsValue(key, value))) {
            val vid = value?.let { dict.findValue(it) }
            if (vid != null && vid < 0) {
                0
            } else {
                val v = if (vid == null) "" else " AND v = ?6"
                val sql = if (box == null) {
                    "SELECT count(DISTINCT place) FROM lookup WHERE k = ?5$v"
                } else {
                    "SELECT count(*) FROM place p WHERE p.id IN (SELECT place FROM lookup WHERE k = ?5$v) AND $BOX_WHERE"
                }
                stmt(sql) { st ->
                    if (box != null) bindBox(st, box, 1)
                    st.bindLong(5, kid.toLong())
                    if (vid != null) st.bindLong(6, vid.toLong())
                    if (st.step()) st.getLong(0).toInt() else 0
                }
            }
        } else {
            placesByTag(key, value, box, Int.MAX_VALUE).size
        }
        places + address
    }

    override fun text(text: String, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> {
        val words = SearchText.indexWords(text)
        if (words.isEmpty()) return emptyList()
        val exact = match(words, fuzzy = false)
        val ids = LinkedHashSet<Long>()
        collect(exact, near, box, limit, ids)
        val addresses = locked { addressBook.search(words, near, box, limit) }
        // an address that answers is a match like a name: no need for near spellings
        if (ids.size + addresses.size < FUZZY_BELOW) {
            val fuzzy = match(words, fuzzy = true)
            if (fuzzy != exact) collect(fuzzy, near, box, limit, ids)
        }
        val places = byKeys(ids)
        return if (addresses.isEmpty()) places else places + addresses
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

    /** The keys of the places [match] finds, the most important first, at most [limit] (and those in [box]). */
    private fun textIds(match: String, box: BBox?, limit: Int, into: MutableSet<Long>) {
        val sql = if (box == null) {
            "SELECT rowid FROM place_fts WHERE place_fts MATCH ?1 ORDER BY rowid LIMIT ?2"
        } else {
            "SELECT f.rowid FROM place_fts f CROSS JOIN place p ON p.id = (f.rowid & ${StoreSchema.KEY_MASK}) " +
                "WHERE place_fts MATCH ?1 AND (p.lat - ifnull(p.ds, 0)) <= ?3 AND (p.lat + ifnull(p.dn, 0)) >= ?4 " +
                "AND (p.lon - ifnull(p.dw, 0)) <= ?5 AND (p.lon + ifnull(p.de, 0)) >= ?6 ORDER BY f.rowid LIMIT ?2"
        }
        stmt(sql) { st ->
            st.bindText(1, match)
            st.bindLong(2, limit.toLong())
            if (box != null) bindBox(st, box, 3)
            while (st.step()) into += StoreSchema.keyOfTextRow(st.getLong(0))
        }
    }

    /**
     * The FTS5 expression for [words]: every word as a prefix (one letter as itself), or with the kinds it names, or,
     * when [fuzzy], with its near spellings from the vocabulary.
     */
    internal fun match(words: List<String>, fuzzy: Boolean): String {
        val kinds = kindWords()
        return words.withIndex().joinToString(" AND ") { (i, w) ->
            val last = i == words.size - 1
            val terms = LinkedHashSet<String>()
            terms += term(w, w.length >= 2)
            for (t in kinds.tokens(w, prefix = last)) terms += "\"$t\""
            if (fuzzy) {
                for (alt in fuzzyWords(w, prefix = last)) {
                    terms += term(alt, last)
                    for (t in kinds.tokens(alt, prefix = false, max = FUZZY_KIND_TOKENS)) terms += "\"$t\""
                }
            }
            if (terms.size == 1) terms.first() else terms.joinToString(" OR ", "(", ")")
        }
    }

    private fun term(w: String, prefix: Boolean) = if (prefix) "\"$w\"*" else "\"$w\""

    /**
     * Words of the vocabulary within Damerau–Levenshtein distance 1 of [word] from 4 letters and 2 from 8 (or, while
     * [prefix], whose beginning is): found through shared trigrams, then checked exactly. Nearest first.
     */
    fun fuzzyWords(word: String, prefix: Boolean = false, max: Int = 12): List<String> = locked { vocabulary.near(word, prefix, max) }

    /** Whether [word] (folded) is a word of its places' names, streets or kinds, or begins one when [prefix]. */
    fun knows(word: String, prefix: Boolean = false): Boolean = locked {
        if (word.isEmpty() || word.any { !it.isLetterOrDigit() }) return@locked false
        val q = term(word, prefix)
        fun has(table: String) = stmt("SELECT 1 FROM $table WHERE $table MATCH ?1 LIMIT 1") { st -> st.bindText(1, q); st.step() }
        has("place_fts") || has("street_fts") || kindWords().knows(word, prefix)
    }

    /** The words that name the kinds of places this store holds, in [langs]. */
    internal fun kindWords(langs: List<String> = languages.ifEmpty { DEFAULT_LANGS }): KindWords = locked {
        kinds?.takeIf { kindLangs == langs } ?: run {
            val list = ArrayList<Kind>()
            val cuisine = dict.findKey("cuisine")
            stmt("SELECT k, v, n FROM stat WHERE k IN (?1, ?2, ?3) AND v > 0") { st ->
                st.bindLong(1, StoreSchema.PRESET_KEY.toLong())
                st.bindLong(2, StoreSchema.CATEGORY_KEY.toLong())
                st.bindLong(3, cuisine.toLong())
                val rows = ArrayList<IntArray>()
                while (st.step()) rows += intArrayOf(st.getLong(0).toInt(), st.getLong(1).toInt(), st.getLong(2).toInt())
                rows
            }.forEach { (k, v, n) ->
                val type = when (k) {
                    StoreSchema.PRESET_KEY -> Kind.PRESET
                    StoreSchema.CATEGORY_KEY -> Kind.CATEGORY
                    else -> Kind.CUISINE
                }
                list += Kind(type, v, dict.value(v), n)
            }
            KindWords(list, langs).also { kinds = it; kindLangs = langs }
        }
    }

    /** Trees in [box], for the map engine. */
    fun trees(box: BBox, limit: Int = PlaceSource.UNLIMITED): List<Tree> = locked {
        val s = E7.atLeast(box.south)
        val w = E7.atLeast(box.west)
        val n = E7.atMost(box.north)
        val e = E7.atMost(box.east)
        val ranges = Morton.ranges(s, w, n, e)
        val kinds = treeKinds()
        val out = ArrayList<Tree>()
        var i = 0
        while (i < ranges.size && out.size < limit) {
            stmt("SELECT id, kind, height FROM tree WHERE id BETWEEN ?1 AND ?2") { st ->
                st.bindLong(1, ranges[i])
                st.bindLong(2, ranges[i + 1])
                while (out.size < limit && st.step()) {
                    val code = st.getLong(0)
                    val lat = Morton.latE7(code)
                    val lon = Morton.lonE7(code)
                    if (lat < s || lat > n || lon < w || lon > e) continue
                    val kind = kinds[st.getLong(1)]
                    out += Tree(code, LatLon(E7.deg(lat), E7.deg(lon)), kind?.first, kind?.second, if (st.isNull(2)) null else st.getLong(2) / 10.0)
                }
            }
            i += 2
        }
        out
    }

    private fun treeKinds(): Map<Long, Pair<String?, String?>> = treeKinds ?: stmt("SELECT id, leaf_type, genus FROM treekind") { st ->
        val m = HashMap<Long, Pair<String?, String?>>()
        while (st.step()) m[st.getLong(0)] = st.getTextOrNull(1) to st.getTextOrNull(2)
        m
    }.also { treeKinds = it }

    /** The address points in [box] alone: elements that are only an address (a house number on a street). */
    fun addresses(box: BBox, limit: Int = PlaceSource.UNLIMITED): List<PlaceRecord> = locked { addressBook.inBox(box, limit) }

    /** The address point nearest [p] within [maxDistance] metres, for "what is the address here". */
    fun nearestAddress(p: LatLon, maxDistance: Double = 100.0): PlaceRecord? = locked { addressBook.nearest(p, maxDistance) }

    /** A stored setting: "name", "langs", "imported", "schema", "places", "addresses"… */
    fun meta(key: String): String? = locked {
        stmt("SELECT value FROM meta WHERE key = ?1") { st -> st.bindText(1, key); if (st.step()) st.getText(0) else null }
    }

    // ------------------------------------------------------------------------------------------------- writing

    /**
     * A writer for this store: add elements or places, then [StoreWriter.finish] to work out the streets, statistics
     * and vocabulary. Adding an element again replaces it, so importing the same data twice leaves one copy.
     * [langs] are the languages whose preset and category names the text index answers to besides the names themselves.
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
     * update): [refresh] then brings the categories, brands, presets, symbols and kind tokens up to date.
     */
    val isStale: Boolean get() = placeCount > 0 && meta("derived") != StoreWriter.fingerprint()

    /** Works out every place's category, brand, preset, symbol and indexed kinds again, in batches. */
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
        for (t in StoreSchema.TABLES) {
            if (t.endsWith("_fts")) exec("INSERT INTO $t($t) VALUES ('delete-all')") else exec("DELETE FROM $t")
        }
        exec("DELETE FROM meta WHERE key IN ('places', 'addresses', 'trees', 'bounds')")
        dict.forget()
        dict.seed()
        invalidate()
    }

    /**
     * Rewrites the file without slack (SQLite's VACUUM): smaller, and faster to read. It needs room for a copy, so it
     * is skipped (false) when the disk is short of it.
     */
    fun compact(): Boolean = locked {
        check(!inTransaction) { "A writer is still open" }
        val f = file
        if (f != null) {
            val free = f.absoluteFile.parentFile?.usableSpace ?: 0L
            if (free < f.length() + f.length() / 10 + (64L shl 20)) return@locked false
        }
        exec("VACUUM")
        if (f != null) exec("PRAGMA wal_checkpoint(TRUNCATE)")
        true
    }

    internal fun invalidate() {
        cachedPlaces = -1
        cachedAddresses = -1
        boundsKnown = false
        kinds = null
        treeKinds = null
        vocabulary.forget()
        addressBook.forget()
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

    private fun counted(metaKey: String, sql: String): Int =
        if (inTransaction) long(sql).toInt() else meta(metaKey)?.toIntOrNull() ?: long(sql).toInt()

    private fun stat(k: Int, v: Int): Int = stmt("SELECT n FROM stat WHERE k = ?1 AND v = ?2") { st ->
        st.bindLong(1, k.toLong())
        st.bindLong(2, v.toLong())
        if (st.step()) st.getLong(0).toInt() else 0
    }

    /** Places of the lookup table's rows for key id [k] (and value id [v]), in [box]. */
    private fun lookup(k: Int, v: Int?, box: BBox?, limit: Int): List<PlaceRecord> {
        val value = if (v == null) "" else " AND v = ?6"
        val where = if (box == null) "" else " AND $BOX_WHERE"
        return stmt("SELECT $COLS FROM place p WHERE p.id IN (SELECT place FROM lookup WHERE k = ?5$value)$where LIMIT ?7") { st ->
            if (box != null) bindBox(st, box, 1)
            st.bindLong(5, k.toLong())
            if (v != null) st.bindLong(6, v.toLong())
            st.bindLong(7, limit.toLong())
            rows(st, limit)
        }
    }

    /** Places in [box] (or every place) that carry key id [key] (any when -1) and that [accept] keeps, at most [limit]. */
    private inline fun scan(key: Int, box: BBox?, limit: Int, accept: (PlaceRecord) -> Boolean): List<PlaceRecord> {
        val sql = if (box == null) "SELECT $COLS FROM place p" else "SELECT $COLS FROM place_rt r JOIN place p ON p.id = r.id WHERE $RT_WHERE"
        return stmt(sql) { st ->
            if (box != null) bindRt(st, box, 1)
            val out = ArrayList<PlaceRecord>()
            while (out.size < limit && st.step()) {
                if (key >= 0 && !TagBlob.hasKey(st.getBlob(11), key)) continue
                val r = row(st)
                if (accept(r)) out += r
            }
            out
        }
    }

    private fun has(tags: Map<String, String>, key: String, value: String?): Boolean {
        val v = tags[key] ?: return false
        if (value == null || v == value) return true
        return ';' in v && v.split(';').any { it.trim() == value }
    }

    private fun keyOf(r: PlaceRecord): Long = r.place.id?.let(StoreSchema::pack) ?: StoreSchema.foreignKey(r.place.key)

    private fun rows(st: SQLiteStatement, limit: Int): List<PlaceRecord> {
        val out = ArrayList<PlaceRecord>()
        while (out.size < limit && st.step()) out += row(st)
        return out
    }

    private fun row(st: SQLiteStatement): PlaceRecord {
        val key = st.getLong(0)
        val lat = st.getLong(1)
        val lon = st.getLong(2)
        val box = if (st.isNull(3)) null else BBox(E7.deg(lon - st.getLong(4)), E7.deg(lat - st.getLong(3)), E7.deg(lon + st.getLong(6)), E7.deg(lat + st.getLong(5)))
        val tags = TagBlob.decode(st.getBlob(11), dict)
        var extra = if (st.isNull(12)) emptyMap() else TagBlob.decode(st.getBlob(12), dict)
        if (!st.isNull(13)) extra = extra + ("timestamp" to Instant.ofEpochSecond(st.getLong(13)).toString())
        val source = if (st.isNull(14)) "osm" else dict.value(st.getLong(14).toInt())
        val place = Place(StoreSchema.unpack(key), LatLon(E7.deg(lat), E7.deg(lon)), tags, box, source, extra)
        return PlaceRecord(
            place,
            category = if (st.isNull(8)) null else dict.value(st.getLong(8).toInt()),
            brand = if (st.isNull(9)) null else dict.value(st.getLong(9).toInt()),
            preset = if (st.isNull(7)) null else dict.value(st.getLong(7).toInt()),
            symbol = dict.value(st.getLong(10).toInt()),
        )
    }

    /** Binds a box as (north, south, east, west) in 1e-7 degree, for [BOX_WHERE]. */
    internal fun bindBox(st: SQLiteStatement, b: BBox, from: Int) {
        st.bindLong(from, E7.atMost(b.north).toLong())
        st.bindLong(from + 1, E7.atLeast(b.south).toLong())
        st.bindLong(from + 2, E7.atMost(b.east).toLong())
        st.bindLong(from + 3, E7.atLeast(b.west).toLong())
    }

    /** Binds a box as (east, west, north, south) in 1e-7 degree, for [RT_WHERE]. */
    internal fun bindRt(st: SQLiteStatement, b: BBox, from: Int) {
        st.bindLong(from, E7.atMost(b.east).toLong())
        st.bindLong(from + 1, E7.atLeast(b.west).toLong())
        st.bindLong(from + 2, E7.atMost(b.north).toLong())
        st.bindLong(from + 3, E7.atLeast(b.south).toLong())
    }

    private fun boundsOf(s: String): BBox? {
        val v = s.split(',').mapNotNull { it.toLongOrNull() }
        if (v.size != 4) return null
        return BBox(E7.deg(v[0]), E7.deg(v[1]), E7.deg(v[2]), E7.deg(v[3]))
    }

    private fun computedBounds(): BBox? {
        var b: BBox? = stmt("SELECT min(lat - ifnull(ds, 0)), min(lon - ifnull(dw, 0)), max(lat + ifnull(dn, 0)), max(lon + ifnull(de, 0)) FROM place") { st ->
            if (st.step() && !st.isNull(0)) BBox(E7.deg(st.getLong(1)), E7.deg(st.getLong(0)), E7.deg(st.getLong(3)), E7.deg(st.getLong(2))) else null
        }
        stmt("SELECT min(west), min(south), max(east), max(north) FROM street_rt") { st ->
            if (st.step() && !st.isNull(0)) {
                val a = BBox(E7.deg(st.getLong(0)), E7.deg(st.getLong(1)), E7.deg(st.getLong(2)), E7.deg(st.getLong(3)))
                b = b?.union(a) ?: a
            }
        }
        return b
    }

    private fun setUp() {
        val version = runCatching { stmt("SELECT value FROM meta WHERE key = 'schema'") { st -> if (st.step()) st.getText(0).toIntOrNull() else null } }.getOrNull()
        if (version != null && version != StoreSchema.VERSION) {
            val pack = runCatching { meta("packer") }.getOrNull()
            if (pack != null) {
                throw StoreVersionException("${file?.name ?: "This store"} is a pack of store schema $version ($pack); Atlas reads schema ${StoreSchema.VERSION}: download the region again")
            }
            // an Overpass region of an older version: emptied, to be imported again
            for (s in statements.values) runCatching { s.close() }
            statements.clear()
            for (t in StoreSchema.TABLES + StoreSchema.OLD_TABLES + "meta") exec("DROP TABLE IF EXISTS $t")
            exec("VACUUM")
        }
        exec("PRAGMA page_size = $PAGE_SIZE")
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")
        exec("PRAGMA temp_store=MEMORY")
        exec("PRAGMA cache_size=-16000")
        for (sql in StoreSchema.CREATE) exec(sql)
        exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '${StoreSchema.VERSION}')")
        dict.seed()
    }

    companion object {
        /** The languages a store indexes kind names in when the app does not say: Atlas's four search languages. */
        val DEFAULT_LANGS = listOf("en", "nl", "de", "fr")

        /** The store schema this version reads and writes; a pack's manifest names the schema it was built with. */
        const val SCHEMA: Int = StoreSchema.VERSION

        private const val PAGE_SIZE = 4096
        private const val FUZZY_BELOW = 5
        private const val FUZZY_KIND_TOKENS = 8
        private const val REFRESH_BATCH = 5_000
        private const val NEAR_TEXT_METRES = 5_000.0

        internal const val COLS = "p.id, p.lat, p.lon, p.ds, p.dw, p.dn, p.de, p.preset, p.category, p.brand, p.symbol, p.tags, p.extra, p.edited, p.source"

        /** A place's box meets the bound box (north, south, east, west in 1e-7 degree, from parameter 1). */
        private const val BOX_WHERE = "(p.lat - ifnull(p.ds, 0)) <= ?1 AND (p.lat + ifnull(p.dn, 0)) >= ?2 AND (p.lon - ifnull(p.dw, 0)) <= ?3 AND (p.lon + ifnull(p.de, 0)) >= ?4"

        /** The R*Tree's box meets the bound box (east, west, north, south, from parameter 1). */
        internal const val RT_WHERE = "r.west <= ?1 AND r.east >= ?2 AND r.south <= ?3 AND r.north >= ?4"

        private val driver by lazy { BundledSQLiteDriver() }

        /** Opens (or creates) the store in [file]; a pack of another store schema is refused ([StoreVersionException]). */
        fun open(file: File): PlaceStore {
            file.absoluteFile.parentFile?.mkdirs()
            return create(file, driver.open(file.absolutePath))
        }

        /** A store that lives in memory only, for tests and online results. */
        fun inMemory(): PlaceStore = create(null, driver.open(":memory:"))

        private fun create(file: File?, conn: SQLiteConnection): PlaceStore {
            val s = PlaceStore(file, conn)
            try {
                s.locked { s.setUp() }
            } catch (e: Throwable) {
                s.close()
                throw e
            }
            return s
        }
    }
}
