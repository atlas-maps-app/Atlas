package app.atlas.search

import app.atlas.model.OsmType
import app.atlas.model.Place
import app.atlas.places.Brands
import app.atlas.places.Categories
import app.atlas.places.Geometry
import app.atlas.places.Presets
import app.atlas.places.Units
import app.atlas.providers.OverpassElement
import java.io.Closeable
import java.time.Instant
import kotlin.concurrent.withLock

/**
 * Writes places into a [PlaceStore] in large transactions with prepared statements. Adding a place that is already
 * there replaces it (its tags, text and box), so an import can be repeated or resumed; an address point that is
 * already there (the same number on the same street) is replaced too. Call [finish] at the end: it commits, works out
 * the streets' boxes and text, the statistics and the typo vocabulary, and records the region's metadata.
 */
class StoreWriter internal constructor(
    private val store: PlaceStore,
    private val langs: List<String>,
    private val regionName: String?,
) : Closeable {
    private val started = System.nanoTime()
    private var pending = 0
    private var finished = false
    private val dict = store.dict
    private val streets = Streets()
    private val treeKinds = HashMap<String, Int>()
    private val sink = ByteSink(512)
    private val extraSink = ByteSink(64)

    /** True when the text index was written by another version: it is then rebuilt whole at [finish]. */
    private val rebuildText: Boolean

    var elements = 0L
        private set
    var places = 0
        private set
    var trees = 0
        private set
    var skipped = 0L
        private set

    /** Address points added (to the address table, not as places). */
    var addresses = 0
        private set

    init {
        rebuildText = store.locked {
            store.exec("BEGIN")
            store.meta("text") != StoreSchema.TEXT_VERSION.toString() && store.long("SELECT EXISTS (SELECT 1 FROM place)") != 0L
        }
    }

    /**
     * Adds one Overpass element: kept as a place or an address point when [Selection] keeps it, as a tree when it is
     * one. Its edit time, when the extract has one (`out meta`), is kept as `extra["timestamp"]` for [Query.Newer].
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
        addPlace(place, null)
    }

    /** Adds a place from anywhere (OSM or a provider); every tagged place is kept. */
    fun add(place: Place, geometry: Geometry = PlaceRecord.geometryOf(place)) {
        elements++
        if (place.tags.isEmpty()) { skipped++; return }
        if (Selection.isTree(place.tags)) addTree(place)
        addPlace(place, geometry)
    }

    /** Adds a record whose category, brand, preset and symbol are already known, as a place. */
    fun add(record: PlaceRecord) {
        elements++
        insert(record, StoredTags.filter(record.tags, record.place.source))
    }

    /** An OSM element that is only an address goes to the address table; anything else is a place. */
    private fun addPlace(place: Place, geometry: Geometry?) {
        val tags = StoredTags.filter(place.tags, place.source)
        if (place.source == "osm") Addresses.parts(tags)?.let { addAddress(it, place); return }
        val p = if (tags === place.tags) place else place.copy(tags = tags)
        insert(PlaceRecord.of(p, geometry ?: PlaceRecord.geometryOf(p)), tags)
    }

    private fun insert(r: PlaceRecord, tags: Map<String, String>) {
        val p = r.place
        val key = p.id?.let(StoreSchema::pack) ?: StoreSchema.foreignKey(p.key)
        val lat = E7.of(p.location.lat)
        val lon = E7.of(p.location.lon)
        val box = p.bbox?.takeIf { it.west <= it.east && it.south <= it.north && (it.width > 0 || it.height > 0) }
        store.locked {
            remove(key)
            val preset = r.preset?.let(dict::internValue)
            val category = r.category?.let(dict::internValue)
            val brand = r.brand?.let(dict::internValue)
            val symbol = dict.internValue(r.symbol)
            val blob = TagBlob.encode(tags, dict, sink)
            var edited: Long? = null
            var extra = p.extra
            p.extra["timestamp"]?.let { t ->
                // whole seconds as OSM writes them go to their column; anything finer stays as it came
                val seconds = runCatching { Instant.parse(t) }.getOrNull()?.takeIf { it.nano == 0 && it.toString() == t }?.epochSecond
                if (seconds != null) {
                    edited = seconds
                    extra = extra - "timestamp"
                }
            }
            // the box holds the pin too, so a lookup by box always finds the place where it is shown
            var s = lat
            var w = lon
            var n = lat
            var e = lon
            if (box != null) {
                s = minOf(s, E7.of(box.south)); w = minOf(w, E7.of(box.west))
                n = maxOf(n, E7.of(box.north)); e = maxOf(e, E7.of(box.east))
            }
            store.stmt(
                "INSERT OR REPLACE INTO place(id, lat, lon, ds, dw, dn, de, preset, category, brand, symbol, tags, extra, edited, source) " +
                    "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15)",
            ) { st ->
                st.bindLong(1, key)
                st.bindLong(2, lat.toLong())
                st.bindLong(3, lon.toLong())
                if (box == null) {
                    for (i in 4..7) st.bindNull(i)
                } else {
                    st.bindLong(4, (lat - s).toLong())
                    st.bindLong(5, (lon - w).toLong())
                    st.bindLong(6, (n - lat).toLong())
                    st.bindLong(7, (e - lon).toLong())
                }
                bindId(st, 8, preset)
                bindId(st, 9, category)
                bindId(st, 10, brand)
                st.bindLong(11, symbol.toLong())
                st.bindBlob(12, blob)
                if (extra.isEmpty()) st.bindNull(13) else st.bindBlob(13, TagBlob.encodeInline(extra, dict, extraSink))
                edited.let { if (it == null) st.bindNull(14) else st.bindLong(14, it) }
                if (p.source == "osm") st.bindNull(15) else st.bindLong(15, dict.internValue(p.source).toLong())
                st.step()
            }
            store.stmt("INSERT INTO place_rt(id, west, east, south, north) VALUES (?1, ?2, ?3, ?4, ?5)") { st ->
                st.bindLong(1, key)
                st.bindLong(2, w.toLong())
                st.bindLong(3, e.toLong())
                st.bindLong(4, s.toLong())
                st.bindLong(5, n.toLong())
                st.step()
            }
            store.stmt("INSERT OR IGNORE INTO lookup(k, v, place) VALUES (?1, ?2, ?3)") { st ->
                for ((k, v) in tags) {
                    if (!TagIndex.holds(k)) continue
                    val kid = dict.internKey(k)
                    for (vid in TagIndex.valueIds(k, v, dict)) lookupRow(st, kid, vid, key)
                }
                category?.let { lookupRow(st, StoreSchema.CATEGORY_KEY, it, key) }
                brand?.let { lookupRow(st, StoreSchema.BRAND_KEY, it, key) }
            }
            if (!rebuildText) {
                store.stmt("INSERT INTO place_fts(rowid, text) VALUES (?1, ?2)") { st ->
                    st.bindLong(1, StoreSchema.textRow(key, Importance.of(tags)))
                    st.bindText(2, SearchText.indexText(tags, KindWords.tokensOf(preset, category, tags, dict)))
                    st.step()
                }
            }
        }
        places++
        tick()
    }

    /** Takes a place out of the index tables (its row is then replaced): its lookup rows, its text and its box. */
    private fun remove(key: Long) {
        var tags: Map<String, String>? = null
        var preset: Int? = null
        var category: Int? = null
        var brand: Int? = null
        store.stmt("SELECT tags, preset, category, brand FROM place WHERE id = ?1") { st ->
            st.bindLong(1, key)
            if (st.step()) {
                tags = TagBlob.decode(st.getBlob(0), dict)
                preset = if (st.isNull(1)) null else st.getLong(1).toInt()
                category = if (st.isNull(2)) null else st.getLong(2).toInt()
                brand = if (st.isNull(3)) null else st.getLong(3).toInt()
            }
        }
        val old = tags ?: return
        store.stmt("DELETE FROM lookup WHERE k = ?1 AND v = ?2 AND place = ?3") { st ->
            for ((k, v) in old) {
                if (!TagIndex.holds(k)) continue
                val kid = dict.findKey(k)
                if (kid < 0) continue
                for (vid in TagIndex.valueIds(k, v, dict)) lookupRow(st, kid, vid, key)
            }
            category?.let { lookupRow(st, StoreSchema.CATEGORY_KEY, it, key) }
            brand?.let { lookupRow(st, StoreSchema.BRAND_KEY, it, key) }
        }
        if (!rebuildText) {
            // a contentless index forgets a row given the very text it was given
            store.stmt("INSERT INTO place_fts(place_fts, rowid, text) VALUES ('delete', ?1, ?2)") { st ->
                st.bindLong(1, StoreSchema.textRow(key, Importance.of(old)))
                st.bindText(2, SearchText.indexText(old, KindWords.tokensOf(preset, category, old, dict)))
                st.step()
            }
        }
        store.stmt("DELETE FROM place_rt WHERE id = ?1") { st -> st.bindLong(1, key); st.step() }
    }

    private fun lookupRow(st: androidx.sqlite.SQLiteStatement, k: Int, v: Int, place: Long) {
        st.bindLong(1, k.toLong())
        st.bindLong(2, v.toLong())
        st.bindLong(3, place)
        st.step()
        st.reset()
    }

    private fun addAddress(a: AddressParts, p: Place) {
        val lat = E7.of(p.location.lat)
        val lon = E7.of(p.location.lon)
        val cell = Addresses.cellOf(lat, lon)
        val osm = p.id?.let(StoreSchema::pack)
        store.locked {
            val street = streets.idOf(a, cell)
            store.stmt("INSERT OR REPLACE INTO address(street, hn, lat, lon, osm) VALUES (?1, ?2, ?3, ?4, ?5)") { st ->
                st.bindLong(1, street)
                st.bindText(2, a.housenumber)
                st.bindLong(3, (lat - Addresses.cellSouth(cell)).toLong())
                st.bindLong(4, (lon - Addresses.cellWest(cell)).toLong())
                if (osm == null) st.bindNull(5) else st.bindLong(5, osm)
                st.step()
            }
        }
        addresses++
        tick()
    }

    private fun addTree(p: Place) {
        val t = p.tags
        val lat = E7.of(p.location.lat)
        val lon = E7.of(p.location.lon)
        store.locked {
            val kind = treeKind(t["leaf_type"], t["genus"] ?: t["genus:en"])
            val height = t["height"]?.let { Units.parse(it)?.value }?.takeIf { it > 0 && it < 200 }
            store.stmt("INSERT OR REPLACE INTO tree(id, kind, height) VALUES (?1, ?2, ?3)") { st ->
                st.bindLong(1, Morton.encode(lat, lon))
                st.bindLong(2, kind.toLong())
                if (height == null) st.bindNull(3) else st.bindLong(3, Math.round(height * 10))
                st.step()
            }
        }
        trees++
        tick()
    }

    /** The id of a tree's leaf type and genus, kept in a small table of their combinations. */
    private fun treeKind(leaf: String?, genus: String?): Int {
        if (treeKinds.isEmpty()) {
            store.stmt("SELECT id, leaf_type, genus FROM treekind") { st ->
                while (st.step()) treeKinds[kindKey(st.getTextOrNull(1), st.getTextOrNull(2))] = st.getLong(0).toInt()
            }
        }
        val k = kindKey(leaf, genus)
        treeKinds[k]?.let { return it }
        val id = store.stmt("INSERT INTO treekind(leaf_type, genus) VALUES (?1, ?2) RETURNING id") { st ->
            if (leaf == null) st.bindNull(1) else st.bindText(1, leaf)
            if (genus == null) st.bindNull(2) else st.bindText(2, genus)
            st.step()
            st.getLong(0).toInt()
        }
        treeKinds[k] = id
        return id
    }

    private fun kindKey(leaf: String?, genus: String?) = "${leaf ?: ""}\u0000${genus ?: ""}"

    private fun tick() {
        if (++pending >= BATCH) commit()
    }

    private var committed = LongArray(5)

    /** Commits what was added so far and goes on in a new transaction. */
    fun commit() {
        store.locked {
            store.exec("COMMIT")
            store.exec("BEGIN")
        }
        pending = 0
        committed = longArrayOf(elements, places.toLong(), trees.toLong(), skipped, addresses.toLong())
    }

    /** Throws away everything added since the last [commit] (counts included), as if it never came. */
    fun rollback() {
        store.locked {
            if (store.inTransaction) store.exec("ROLLBACK")
            store.exec("BEGIN")
            // ids handed out in the lost transaction are gone with it, from what readers cached too (a street read
            // meanwhile may be another street under the same id later)
            dict.forget()
            streets.forget()
            treeKinds.clear()
            store.invalidate()
        }
        pending = 0
        elements = committed[0]
        places = committed[1].toInt()
        trees = committed[2].toInt()
        skipped = committed[3]
        addresses = committed[4].toInt()
    }

    /** Commits, works out streets, statistics and vocabulary, and records the metadata. */
    fun finish(): ImportStats {
        check(!finished) { "This writer has finished" }
        store.locked {
            StoreFinish(store, langs).run(rebuildText)
            putMeta("langs", langs.joinToString(","))
            regionName?.let { putMeta("name", it) }
            putMeta("imported", Instant.now().toString())
            putMeta("derived", fingerprint())
            putMeta("text", StoreSchema.TEXT_VERSION.toString())
            store.exec("COMMIT")
            store.exec("INSERT INTO place_fts(place_fts) VALUES ('optimize')")
            store.exec("INSERT INTO street_fts(street_fts) VALUES ('optimize')")
            store.exec("PRAGMA analysis_limit = 1000")
            store.exec("ANALYZE")
            store.invalidate()
        }
        finished = true
        return ImportStats(elements, places, trees, skipped, (System.nanoTime() - started) / 1_000_000, addresses)
    }

    private fun putMeta(key: String, value: String) {
        store.stmt("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)") { st -> st.bindText(1, key); st.bindText(2, value); st.step() }
    }

    override fun close() {
        if (finished) return
        finished = true
        // a store closed under the writer has already rolled back what was not committed
        store.lock.withLock {
            if (!store.closed && store.inTransaction) {
                store.exec("ROLLBACK")
                dict.forget()
                store.invalidate()
            }
        }
    }

    private fun bindId(st: androidx.sqlite.SQLiteStatement, i: Int, id: Int?) = if (id == null) st.bindNull(i) else st.bindLong(i, id.toLong())

    /** The streets of the address points, by name, kind, postcode, town, country and cell; read from the store on first use. */
    private inner class Streets {
        private val ids = HashMap<String, Long>()
        private var loaded = false

        fun idOf(a: AddressParts, cell: Long): Long {
            load()
            val city = a.city?.let(dict::internValue)
            val country = a.country?.let(dict::internValue)
            val k = key(a.street, a.kind, a.postcode, city, country, cell)
            ids[k]?.let { return it }
            val id = store.stmt("INSERT INTO street(name, kind, postcode, city, country, cell) VALUES (?1, ?2, ?3, ?4, ?5, ?6) RETURNING id") { st ->
                st.bindText(1, a.street)
                st.bindLong(2, a.kind.toLong())
                if (a.postcode == null) st.bindNull(3) else st.bindText(3, a.postcode)
                bindId(st, 4, city)
                bindId(st, 5, country)
                st.bindLong(6, cell)
                st.step()
                st.getLong(0)
            }
            ids[k] = id
            return id
        }

        fun forget() {
            ids.clear()
            loaded = false
        }

        private fun load() {
            if (loaded) return
            store.stmt("SELECT id, name, kind, postcode, city, country, cell FROM street") { st ->
                while (st.step()) {
                    val k = key(
                        st.getText(1), st.getLong(2).toInt(), st.getTextOrNull(3),
                        if (st.isNull(4)) null else st.getLong(4).toInt(), if (st.isNull(5)) null else st.getLong(5).toInt(), st.getLong(6),
                    )
                    ids[k] = st.getLong(0)
                }
            }
            loaded = true
        }

        private fun key(name: String, kind: Int, postcode: String?, city: Int?, country: Int?, cell: Long) =
            "$name\u0000$kind\u0000${postcode ?: ""}\u0000${city ?: -1}\u0000${country ?: -1}\u0000$cell"
    }

    companion object {
        private const val BATCH = 20_000

        /** Changes when the presets, categories or brands that classified the places change. */
        fun fingerprint(): String =
            "${Presets.all.size}/${Categories.leaves().joinToString("|") { "${it.id}:${it.rule}" }.hashCode()}/${Brands.all().size}"
    }
}

internal fun androidx.sqlite.SQLiteStatement.getTextOrNull(i: Int): String? = if (isNull(i)) null else getText(i)
