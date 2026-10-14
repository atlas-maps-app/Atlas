package app.atlas.search

import androidx.sqlite.SQLiteStatement
import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Geometry
import app.atlas.places.Localise
import app.atlas.places.Presets
import app.atlas.providers.OverpassElement
import java.io.Closeable
import java.time.Instant
import kotlin.concurrent.withLock

/**
 * Writes places into a [PlaceStore] in large transactions with prepared statements. Adding a place that is already
 * there replaces it (its tags, text and box), so an import can be repeated or resumed. Call [finish] at the end: it
 * commits, rebuilds the typo vocabulary and the tag statistics, and records the region's metadata.
 */
class StoreWriter internal constructor(
    private val store: PlaceStore,
    private val langs: List<String>,
    private val regionName: String?,
) : Closeable {
    private val started = System.nanoTime()
    private var pending = 0
    private var finished = false

    var elements = 0L
        private set
    var places = 0
        private set
    var trees = 0
        private set
    var skipped = 0L
        private set

    init {
        store.locked { store.exec("BEGIN") }
    }

    /**
     * Adds one Overpass element: kept as a place when [Selection] keeps it, as a tree when it is one. Its edit time,
     * when the extract has one (`out meta`), is kept as `extra["timestamp"]` for [Query.Newer].
     */
    fun add(element: OverpassElement) {
        elements++
        if (element.tags.isEmpty()) return
        val located = element.toPlace() ?: run { skipped++; return }
        val place = element.timestamp?.let { located.copy(extra = located.extra + ("timestamp" to it)) } ?: located
        val isTree = Selection.isTree(element.tags)
        if (isTree) addTree(place)
        if (!Selection.keeps(element.tags, isRelation = element.type == OsmType.RELATION)) {
            if (!isTree) skipped++
            return
        }
        insert(PlaceRecord.of(place))
    }

    /** Adds a place from anywhere (OSM or a provider); every tagged place is kept. */
    fun add(place: Place, geometry: Geometry = PlaceRecord.geometryOf(place)) {
        elements++
        if (place.tags.isEmpty()) { skipped++; return }
        if (Selection.isTree(place.tags)) addTree(place)
        insert(PlaceRecord.of(place, geometry))
    }

    /** Adds a record whose category, brand, preset and symbol are already known. */
    fun add(record: PlaceRecord) {
        elements++
        insert(record)
    }

    private fun insert(r: PlaceRecord) {
        val p = r.place
        val key = p.id?.let(StoreSchema::pack) ?: StoreSchema.foreignKey(p.key)
        val box = PlaceRecord.boxOf(p)
        store.locked {
            val exists = store.stmt("SELECT 1 FROM place WHERE id = ?1") { st -> st.bindLong(1, key); st.step() }
            if (exists) {
                store.stmt("DELETE FROM tag WHERE place = ?1") { st -> st.bindLong(1, key); st.step() }
                store.stmt("DELETE FROM place_fts WHERE rowid = ?1") { st -> st.bindLong(1, key); st.step() }
                store.stmt("DELETE FROM place_rt WHERE id = ?1") { st -> st.bindLong(1, key); st.step() }
            }
            store.stmt(
                "INSERT OR REPLACE INTO place(id, lat, lon, south, west, north, east, name, category, brand, preset, symbol, tags, source, extra) " +
                    "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
            ) { st ->
                st.bindLong(1, key)
                st.bindDouble(2, p.location.lat)
                st.bindDouble(3, p.location.lon)
                st.bindDouble(4, box.south)
                st.bindDouble(5, box.west)
                st.bindDouble(6, box.north)
                st.bindDouble(7, box.east)
                bindOrNull(st, 8, Localise.name(p.tags))
                bindOrNull(st, 9, r.category)
                bindOrNull(st, 10, r.brand)
                bindOrNull(st, 11, r.preset)
                st.bindText(12, r.symbol)
                st.bindText(13, TagsJson.write(p.tags))
                st.bindText(14, p.source)
                bindOrNull(st, 15, if (p.extra.isEmpty()) null else TagsJson.write(p.extra))
                st.step()
            }
            store.stmt("INSERT INTO place_rt(id, south, north, west, east) VALUES (?1, ?2, ?3, ?4, ?5)") { st ->
                st.bindLong(1, key)
                st.bindDouble(2, box.south)
                st.bindDouble(3, box.north)
                st.bindDouble(4, box.west)
                st.bindDouble(5, box.east)
                st.step()
            }
            store.stmt("INSERT OR IGNORE INTO tag(key, value, place) VALUES (?1, ?2, ?3)") { st ->
                for ((k, v) in p.tags) {
                    tagRow(st, k, indexed(v), key)
                    if (';' in v) {
                        for (part in v.split(';')) {
                            val t = part.trim()
                            if (t.isNotEmpty() && t != v) tagRow(st, k, indexed(t), key)
                        }
                    }
                }
            }
            val doc = SearchText.document(p.tags, r.preset, r.category, langs)
            store.stmt("INSERT INTO place_fts(rowid, name, other, terms, addr) VALUES (?1, ?2, ?3, ?4, ?5)") { st ->
                st.bindLong(1, key)
                st.bindText(2, doc.name)
                st.bindText(3, doc.other)
                st.bindText(4, doc.terms)
                st.bindText(5, doc.addr)
                st.step()
            }
        }
        places++
        tick()
    }

    private fun tagRow(st: SQLiteStatement, k: String, v: String, place: Long) {
        st.bindText(1, k)
        st.bindText(2, v)
        st.bindLong(3, place)
        st.step()
        st.reset()
    }

    private fun indexed(v: String): String = if (v.length > StoreSchema.MAX_INDEXED_VALUE) "" else v

    private fun addTree(p: Place) {
        val key = p.id?.let(StoreSchema::pack) ?: StoreSchema.foreignKey(p.key)
        val t = p.tags
        store.locked {
            store.stmt("DELETE FROM tree_rt WHERE id = ?1") { st -> st.bindLong(1, key); st.step() }
            store.stmt("INSERT OR REPLACE INTO tree(id, lat, lon, leaf_type, genus, height) VALUES (?1, ?2, ?3, ?4, ?5, ?6)") { st ->
                st.bindLong(1, key)
                st.bindDouble(2, p.location.lat)
                st.bindDouble(3, p.location.lon)
                bindOrNull(st, 4, t["leaf_type"])
                bindOrNull(st, 5, t["genus"] ?: t["genus:en"])
                val h = t["height"]?.let { app.atlas.places.Units.parse(it)?.value }
                if (h == null) st.bindNull(6) else st.bindDouble(6, h)
                st.step()
            }
            store.stmt("INSERT INTO tree_rt(id, south, north, west, east) VALUES (?1, ?2, ?2, ?3, ?3)") { st ->
                st.bindLong(1, key)
                st.bindDouble(2, p.location.lat)
                st.bindDouble(3, p.location.lon)
                st.step()
            }
        }
        trees++
        tick()
    }

    private fun tick() {
        if (++pending >= BATCH) commit()
    }

    private var committed = longArrayOf(0, 0, 0, 0)

    /** Commits what was added so far and goes on in a new transaction. */
    fun commit() {
        store.locked {
            store.exec("COMMIT")
            store.exec("BEGIN")
        }
        pending = 0
        committed = longArrayOf(elements, places.toLong(), trees.toLong(), skipped)
    }

    /** Throws away everything added since the last [commit] (counts included), as if it never came. */
    fun rollback() {
        store.locked {
            if (store.inTransaction) store.exec("ROLLBACK")
            store.exec("BEGIN")
        }
        pending = 0
        elements = committed[0]
        places = committed[1].toInt()
        trees = committed[2].toInt()
        skipped = committed[3]
    }

    /** Commits, rebuilds the vocabulary and statistics, and records the metadata. */
    fun finish(): ImportStats {
        check(!finished) { "This writer has finished" }
        store.locked {
            rebuildVocabulary()
            store.exec("DELETE FROM tagstat")
            store.exec("INSERT INTO tagstat(key, value, n) SELECT key, value, count(*) FROM tag GROUP BY key, value")
            store.exec("DELETE FROM keystat")
            store.exec("INSERT INTO keystat(key, n) SELECT key, count(DISTINCT place) FROM tag GROUP BY key")
            putMeta("langs", langs.joinToString(","))
            regionName?.let { putMeta("name", it) }
            putMeta("imported", Instant.now().toString())
            putMeta("derived", fingerprint())
            store.exec("COMMIT")
            store.exec("INSERT INTO place_fts(place_fts) VALUES ('optimize')")
            store.exec("ANALYZE")
            store.invalidate()
        }
        finished = true
        return ImportStats(elements, places, trees, skipped, (System.nanoTime() - started) / 1_000_000)
    }

    /** The typo vocabulary: every indexed word of four letters or more, with its trigrams, streamed from the index. */
    private fun rebuildVocabulary() {
        store.exec("DROP TABLE IF EXISTS temp.vocab")
        store.exec("CREATE VIRTUAL TABLE temp.vocab USING fts5vocab(main, place_fts, row)")
        store.exec("DELETE FROM word")
        store.exec("DELETE FROM gram")
        val terms = store.prepare("SELECT term, doc FROM temp.vocab WHERE length(term) >= 4")
        val insertWord = store.prepare("INSERT INTO word(w, len, n) VALUES (?1, ?2, ?3) RETURNING id")
        val insertGram = store.prepare("INSERT OR IGNORE INTO gram(g, word) VALUES (?1, ?2)")
        try {
            while (terms.step()) {
                val w = terms.getText(0)
                if (w.any { it.isDigit() }) continue
                insertWord.bindText(1, w)
                insertWord.bindLong(2, w.length.toLong())
                insertWord.bindLong(3, terms.getLong(1))
                insertWord.step()
                val id = insertWord.getLong(0)
                insertWord.reset()
                for (g in Grams.of(w)) {
                    insertGram.bindLong(1, g)
                    insertGram.bindLong(2, id)
                    insertGram.step()
                    insertGram.reset()
                }
            }
        } finally {
            terms.close()
            insertWord.close()
            insertGram.close()
        }
        store.exec("DROP TABLE temp.vocab")
    }

    private fun putMeta(key: String, value: String) {
        store.stmt("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)") { st -> st.bindText(1, key); st.bindText(2, value); st.step() }
    }

    override fun close() {
        if (finished) return
        finished = true
        // a store closed under the writer has already rolled back what was not committed
        store.lock.withLock { if (!store.closed && store.inTransaction) store.exec("ROLLBACK") }
    }

    private fun bindOrNull(st: SQLiteStatement, i: Int, v: String?) = if (v == null) st.bindNull(i) else st.bindText(i, v)

    companion object {
        private const val BATCH = 20_000

        /** Changes when the presets, categories or brands that classified the places change. */
        fun fingerprint(): String =
            "${Presets.all.size}/${Categories.leaves().joinToString("|") { "${it.id}:${it.rule}" }.hashCode()}/${Brands.all().size}"
    }
}
