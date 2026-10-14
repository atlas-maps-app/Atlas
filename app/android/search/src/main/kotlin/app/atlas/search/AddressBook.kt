package app.atlas.search

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.places.Text

/**
 * The read side of a store's address points: found by street and number ("prinsengracht 263", "12 rue de la gare",
 * "1016 gv 263"), by postcode, by box and by tag, as [PlaceRecord]s with the tags their element had and its OSM id (so
 * points stacked at one position keep keys of their own; [PlaceStore.get] does not find them, as no index holds the
 * ids). Used under the store's lock.
 */
internal class AddressBook(private val store: PlaceStore) {
    /** A street as its address points need it: its names and its cell's corner, which their positions are offsets from. */
    private class Street(
        val id: Long,
        val name: String,
        val kind: Int,
        val postcode: String?,
        val city: String?,
        val country: String?,
        val south: Int,
        val west: Int,
        val n: Int,
    )

    private val streets = object : LinkedHashMap<Long, Street>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Street>?) = size > STREET_CACHE
    }

    /** What every address point is: its preset and symbol, worked out once. */
    private val template: PlaceRecord by lazy {
        PlaceRecord.of(Place(null, LatLon(0.0, 0.0), mapOf("addr:street" to "Main Street", Addresses.HOUSENUMBER to "1")))
    }

    fun forget() = streets.clear()

    // --------------------------------------------------------------------------------------------- by text

    /**
     * Address points for the query [words] (folded): a house number with the words of its street, town or postcode in
     * any order, or a postcode alone. Around [near] (and anywhere), or in [box]; at most [limit].
     */
    fun search(words: List<String>, near: LatLon?, box: BBox?, limit: Int): List<PlaceRecord> {
        if (limit <= 0 || words.none { it[0].isDigit() }) return emptyList()
        val area = box?.let(::E7Box)
        val out = LinkedHashMap<String, PlaceRecord>()
        for (i in words.indices.reversed()) {
            if (!Addresses.looksLikeNumber(words[i])) continue
            // "263 h" is the number 263h
            var number = words[i]
            var lastUsed = i
            if (i + 1 < words.size && words[i + 1].length == 1 && words[i + 1][0].isLetter()) {
                number += words[i + 1]
                lastUsed = i + 1
            }
            val rest = words.filterIndexed { j, _ -> j != i && j != lastUsed }
            if (rest.isEmpty()) continue
            val typing = lastUsed == words.size - 1
            for (s in ranked(rest, prefixLast = !typing, near, box)) {
                points(s) { hn, lat, lon, osm ->
                    if (Addresses.numberFit(number, hn, typing) > 0 && (area == null || area.has(lat, lon))) {
                        val r = record(s, hn, lat, lon, osm)
                        out.putIfAbsent(r.place.key, r)
                    }
                }
                if (out.size >= limit) break
            }
            if (out.size >= limit) break
        }
        // a postcode alone: "1016 gv", "1016gv", "L-2449" (the country's letter left out)
        if (out.isEmpty() && words.size <= 3) {
            val code = if (words.size >= 2 && words[0].length == 1 && words[0][0].isLetter() && words[1][0].isDigit()) words.drop(1) else words
            val joined = code.joinToString("")
            for (id in streets(code, prefixLast = true, near, box)) {
                val s = street(id) ?: continue
                val pc = s.postcode?.let { Text.fold(it).replace(" ", "") } ?: continue
                if (!pc.startsWith(joined)) continue
                points(s) { hn, lat, lon, osm ->
                    if (out.size < limit && (area == null || area.has(lat, lon))) {
                        val r = record(s, hn, lat, lon, osm)
                        out.putIfAbsent(r.place.key, r)
                    }
                }
                if (out.size >= limit) break
            }
        }
        return out.values.take(limit)
    }

    /**
     * The streets [words] most likely name, best first: those whose name holds the words before those that hold some
     * in their town or postcode ("rue de luxembourg" is the street of that name in any town before the "rue de…" of
     * the city of Luxembourg), and around [near] before the rest.
     */
    private fun ranked(words: List<String>, prefixLast: Boolean, near: LatLon?, box: BBox?): List<Street> {
        val scored = ArrayList<Pair<Street, Double>>()
        for (id in streets(words, prefixLast, near, box)) {
            val s = street(id) ?: continue
            val name = Text.fold(s.name)
            val all = listOfNotNull(name, s.city?.let(Text::fold), s.postcode?.let(Text::fold)).joinToString(" ")
            val score = maxOf(NameMatch.score(words, name, prefixLast), 0.9 * NameMatch.score(words, all, prefixLast))
            if (score > 0) scored += s to score
        }
        // stable: around the searcher first among equals
        return scored.sortedByDescending { it.second }.take(STREETS_READ).map { it.first }
    }

    /** Streets whose line has every word of [words], around [near] and anywhere (or in [box]); typos forgiven when none has. */
    private fun streets(words: List<String>, prefixLast: Boolean, near: LatLon?, box: BBox?): Set<Long> {
        val exact = match(words, prefixLast, fuzzy = false)
        val ids = LinkedHashSet<Long>()
        collect(exact, near, box, ids)
        if (ids.isEmpty()) {
            val fuzzy = match(words, prefixLast, fuzzy = true)
            if (fuzzy != exact) collect(fuzzy, near, box, ids)
        }
        return ids
    }

    private fun collect(match: String, near: LatLon?, box: BBox?, into: MutableSet<Long>) {
        if (box != null) {
            streetIds(match, box, STREET_LIMIT, into)
        } else {
            if (near != null) streetIds(match, BBox.around(near, NEAR_METRES), STREET_LIMIT, into)
            streetIds(match, null, STREET_LIMIT, into)
        }
    }

    /** Short words ("de", "la") are taken whole unless they are being typed; longer ones as prefixes. */
    private fun match(words: List<String>, prefixLast: Boolean, fuzzy: Boolean): String = words.withIndex().joinToString(" AND ") { (i, w) ->
        val last = i == words.size - 1
        val prefix = if (last && prefixLast) w.length >= 2 else w.length >= 3
        val terms = LinkedHashSet<String>()
        terms += if (prefix) "\"$w\"*" else "\"$w\""
        if (fuzzy && w.none { it.isDigit() }) for (alt in store.fuzzyWords(w, prefix = last && prefixLast)) terms += "\"$alt\""
        if (terms.size == 1) terms.first() else terms.joinToString(" OR ", "(", ")")
    }

    private fun streetIds(match: String, box: BBox?, limit: Int, into: MutableSet<Long>) {
        val sql = if (box == null) {
            "SELECT rowid FROM street_fts WHERE street_fts MATCH ?5 LIMIT ?6"
        } else {
            // the text index drives (CROSS JOIN keeps it outer): a box probed per street would run the match per row
            "SELECT f.rowid FROM street_fts f CROSS JOIN street_rt r ON r.id = f.rowid WHERE street_fts MATCH ?5 AND ${PlaceStore.RT_WHERE} LIMIT ?6"
        }
        store.stmt(sql) { st ->
            if (box != null) store.bindRt(st, box, 1)
            st.bindText(5, match)
            st.bindLong(6, limit.toLong())
            while (st.step()) into += st.getLong(0)
        }
    }

    // --------------------------------------------------------------------------------------------- by place

    fun inBox(box: BBox, limit: Int): List<PlaceRecord> {
        val out = ArrayList<PlaceRecord>()
        if (limit <= 0) return out
        val area = E7Box(box)
        for (id in streetsIn(box)) {
            val s = street(id) ?: continue
            points(s) { hn, lat, lon, osm -> if (out.size < limit && area.has(lat, lon)) out += record(s, hn, lat, lon, osm) }
            if (out.size >= limit) break
        }
        return out
    }

    fun nearest(p: LatLon, maxDistance: Double): PlaceRecord? {
        if (maxDistance < 0) return null
        var r = minOf(60.0, maxDistance)
        while (true) {
            var best: PlaceRecord? = null
            var bestD = Double.MAX_VALUE
            for (a in inBox(BBox.around(p, r), Int.MAX_VALUE)) {
                val d = Geo.distance(p, a.location)
                if (d <= r && d < bestD) { best = a; bestD = d }
            }
            if (best != null || r >= maxDistance) return best
            r = minOf(r * 3, maxDistance)
        }
    }

    private fun streetsIn(box: BBox): LongArray = store.stmt("SELECT id FROM street_rt r WHERE ${PlaceStore.RT_WHERE}") { st ->
        store.bindRt(st, box, 1)
        val out = LongArrayBuilder()
        while (st.step()) out.add(st.getLong(0))
        out.toArray()
    }

    // --------------------------------------------------------------------------------------------- by tag

    /** Address points with [key] (one of [Addresses.KEYS]) = [value] (any when null), in [box], at most [limit]. */
    fun byTag(key: String, value: String?, box: BBox?, limit: Int): List<PlaceRecord> {
        val out = ArrayList<PlaceRecord>()
        if (limit <= 0) return out
        matching(key, value, box) { s, hn, lat, lon, osm ->
            out += record(s, hn, lat, lon, osm)
            out.size < limit
        }
        return out
    }

    /** How many address points carry [key] = [value] (or [key]) in [box]: exact. */
    fun count(key: String, value: String?, box: BBox?): Int {
        if (box == null) {
            if (value == null) {
                return store.stmt("SELECT ifnull(sum(n), 0) FROM street WHERE ${presenceWhere(key)}") { st -> if (st.step()) st.getLong(0).toInt() else 0 }
            }
            if (key == Addresses.HOUSENUMBER) return numbered(value).size
            var n = 0
            for (id in candidates(key, value, null)) street(id)?.takeIf { streetHas(it, key, value) }?.let { n += it.n }
            return n
        }
        var n = 0
        matching(key, value, box) { _, _, _, _, _ -> n++; true }
        return n
    }

    /** About how many address points carry [key] = [value]. */
    fun estimate(key: String, value: String): Int =
        if (key == Addresses.HOUSENUMBER) (total() / 200).coerceAtLeast(1) else count(key, value, null)

    private fun total(): Int = store.stmt("SELECT ifnull(sum(n), 0) FROM street") { st -> if (st.step()) st.getLong(0).toInt() else 0 }

    private fun presenceWhere(key: String): String = when (key) {
        "addr:street" -> "kind = ${Addresses.STREET}"
        "addr:place" -> "kind = ${Addresses.PLACE}"
        "addr:postcode" -> "postcode IS NOT NULL"
        "addr:city" -> "city IS NOT NULL"
        "addr:country" -> "country IS NOT NULL"
        else -> "1"
    }

    /** Calls [block] for every address point matching; it returns false to stop. */
    private inline fun matching(key: String, value: String?, box: BBox?, block: (Street, String, Int, Int, Long) -> Boolean) {
        val area = box?.let(::E7Box)
        if (key == Addresses.HOUSENUMBER && value != null && box == null) {
            // a number anywhere: the rows that hold it (or a list), read in one pass rather than street by street
            for (row in numbered(value)) {
                val s = street(row.street) ?: continue
                if (!block(s, row.hn, s.south + row.lat, s.west + row.lon, row.osm)) return
            }
            return
        }
        for (id in candidates(key, value, box)) {
            val s = street(id) ?: continue
            if (!streetHas(s, key, value)) continue
            var go = true
            points(s) { hn, lat, lon, osm ->
                if (go && (area == null || area.has(lat, lon)) && (key != Addresses.HOUSENUMBER || value == null || numberIs(hn, value))) {
                    go = block(s, hn, lat, lon, osm)
                }
            }
            if (!go) return
        }
    }

    private class Row(val street: Long, val hn: String, val lat: Int, val lon: Int, val osm: Long)

    /** The address rows whose number is [value], or a `;` list that holds it. */
    private fun numbered(value: String): List<Row> = store.stmt("SELECT street, hn, lat, lon, ifnull(osm, -1) FROM address WHERE hn = ?1 OR instr(hn, ';') > 0") { st ->
        st.bindText(1, value)
        val out = ArrayList<Row>()
        while (st.step()) {
            val hn = st.getText(1)
            if (numberIs(hn, value)) out += Row(st.getLong(0), hn, st.getLong(2).toInt(), st.getLong(3).toInt(), st.getLong(4))
        }
        out
    }

    /** The streets that may hold matches: in the box, by the text index for a name, town or postcode, else all of them. */
    private fun candidates(key: String, value: String?, box: BBox?): LongArray {
        if (box != null) return streetsIn(box)
        if (value != null && key != Addresses.HOUSENUMBER && key != "addr:country") {
            val words = SearchText.indexWords(value)
            if (words.isEmpty()) return LongArray(0)
            val ids = LinkedHashSet<Long>()
            streetIds(words.joinToString(" AND ") { "\"$it\"" }, null, Int.MAX_VALUE, ids)
            return ids.toLongArray()
        }
        return store.stmt("SELECT id FROM street WHERE ${presenceWhere(key)}") { st ->
            val out = LongArrayBuilder()
            while (st.step()) out.add(st.getLong(0))
            out.toArray()
        }
    }

    private fun streetHas(s: Street, key: String, value: String?): Boolean = when (key) {
        "addr:street" -> s.kind == Addresses.STREET && (value == null || s.name == value)
        "addr:place" -> s.kind == Addresses.PLACE && (value == null || s.name == value)
        "addr:postcode" -> s.postcode != null && (value == null || s.postcode == value)
        "addr:city" -> s.city != null && (value == null || s.city == value)
        "addr:country" -> s.country != null && (value == null || s.country == value)
        else -> true
    }

    private fun numberIs(hn: String, value: String): Boolean = hn == value || (';' in hn && hn.split(';').any { it.trim() == value })

    // --------------------------------------------------------------------------------------------- rows

    private fun street(id: Long): Street? {
        streets[id]?.let { return it }
        val s = store.stmt(
            "SELECT s.name, s.kind, s.postcode, c.v, k.v, s.cell, s.n FROM street s " +
                "LEFT JOIN tagvalue c ON c.id = s.city LEFT JOIN tagvalue k ON k.id = s.country WHERE s.id = ?1",
        ) { st ->
            st.bindLong(1, id)
            if (!st.step()) return@stmt null
            val cell = st.getLong(5)
            Street(
                id, st.getText(0), st.getLong(1).toInt(), st.getTextOrNull(2), st.getTextOrNull(3), st.getTextOrNull(4),
                Addresses.cellSouth(cell), Addresses.cellWest(cell), st.getLong(6).toInt(),
            )
        } ?: return null
        streets[id] = s
        return s
    }

    /**
     * Calls [block] with every address point of street [s]: its number, its position in 1e-7 degree and its packed OSM
     * id (-1 when it came without one).
     */
    private inline fun points(s: Street, block: (String, Int, Int, Long) -> Unit) {
        // read whole first: the block may look up other streets
        val hns = ArrayList<String>()
        val pos = LongArrayBuilder()
        store.stmt("SELECT hn, lat, lon, ifnull(osm, -1) FROM address WHERE street = ?1") { st ->
            st.bindLong(1, s.id)
            while (st.step()) {
                hns += st.getText(0)
                pos.add(st.getLong(1))
                pos.add(st.getLong(2))
                pos.add(st.getLong(3))
            }
        }
        for (i in hns.indices) block(hns[i], s.south + pos[3 * i].toInt(), s.west + pos[3 * i + 1].toInt(), pos[3 * i + 2])
    }

    private fun record(s: Street, hn: String, lat: Int, lon: Int, osm: Long): PlaceRecord {
        val tags = Addresses.tags(s.name, s.kind, hn, s.postcode, s.city, s.country)
        val id = if (osm < 0) null else StoreSchema.unpack(osm)
        return PlaceRecord(Place(id, LatLon(E7.deg(lat), E7.deg(lon)), tags), null, null, template.preset, template.symbol)
    }


    private companion object {
        const val STREET_CACHE = 4096
        const val STREET_LIMIT = 500
        const val STREETS_READ = 40
        const val NEAR_METRES = 5_000.0
    }
}
