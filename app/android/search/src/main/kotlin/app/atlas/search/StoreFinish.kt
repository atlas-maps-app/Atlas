package app.atlas.search

/**
 * What a writer does when it finishes, in its transaction under the store's lock: the streets' boxes, counts and text
 * from their address points, the statistics (with the store's place count and bounds), and the typo vocabulary.
 */
internal class StoreFinish(private val store: PlaceStore, private val langs: List<String>) {
    private val dict = store.dict

    fun run() {
        streets()
        stats()
        vocabulary()
    }

    private fun streets() {
        store.exec("DELETE FROM street_rt")
        store.exec("INSERT INTO street_fts(street_fts) VALUES ('delete-all')")
        val boxes = store.prepare(
            "SELECT g.street, s.cell, g.n, g.s, g.n2, g.w, g.e FROM " +
                "(SELECT street, count(*) AS n, min(lat) AS s, max(lat) AS n2, min(lon) AS w, max(lon) AS e FROM address GROUP BY street) g " +
                "JOIN street s ON s.id = g.street",
        )
        val count = store.prepare("UPDATE street SET n = ?2 WHERE id = ?1")
        val box = store.prepare("INSERT INTO street_rt(id, west, east, south, north) VALUES (?1, ?2, ?3, ?4, ?5)")
        try {
            while (boxes.step()) {
                val id = boxes.getLong(0)
                val cell = boxes.getLong(1)
                val south = Addresses.cellSouth(cell).toLong()
                val west = Addresses.cellWest(cell).toLong()
                count.bindLong(1, id)
                count.bindLong(2, boxes.getLong(2))
                count.step()
                count.reset()
                box.bindLong(1, id)
                box.bindLong(2, west + boxes.getLong(5))
                box.bindLong(3, west + boxes.getLong(6))
                box.bindLong(4, south + boxes.getLong(3))
                box.bindLong(5, south + boxes.getLong(4))
                box.step()
                box.reset()
            }
        } finally {
            boxes.close()
            count.close()
            box.close()
        }
        val lines = store.prepare("SELECT s.id, s.name, s.postcode, v.v FROM street s LEFT JOIN tagvalue v ON v.id = s.city WHERE s.n > 0")
        val text = store.prepare("INSERT INTO street_fts(rowid, text) VALUES (?1, ?2)")
        try {
            while (lines.step()) {
                text.bindLong(1, lines.getLong(0))
                text.bindText(2, Addresses.streetText(lines.getText(1), lines.getTextOrNull(3), lines.getTextOrNull(2)))
                text.step()
                text.reset()
            }
        } finally {
            lines.close()
            text.close()
        }
    }

    /** The text index again from every place, for a store whose index another version wrote. */
    private fun stats() {
        store.exec("DELETE FROM stat")
        store.exec("INSERT INTO stat(k, v, n) SELECT k, v, count(*) FROM lookup GROUP BY k, v")
        val keys = HashMap<Int, Int>()
        val presets = HashMap<Int, Int>()
        var places = 0L
        var south = Long.MAX_VALUE
        var west = Long.MAX_VALUE
        var north = Long.MIN_VALUE
        var east = Long.MIN_VALUE
        val rows = store.prepare("SELECT preset, tags, lat, lon, ifnull(ds, 0), ifnull(dw, 0), ifnull(dn, 0), ifnull(de, 0) FROM place")
        try {
            while (rows.step()) {
                places++
                if (!rows.isNull(0)) presets.merge(rows.getLong(0).toInt(), 1, Int::plus)
                TagBlob.forEachCode(rows.getBlob(1)) { k, _ -> keys.merge(k, 1, Int::plus) }
                val lat = rows.getLong(2)
                val lon = rows.getLong(3)
                south = minOf(south, lat - rows.getLong(4))
                west = minOf(west, lon - rows.getLong(5))
                north = maxOf(north, lat + rows.getLong(6))
                east = maxOf(east, lon + rows.getLong(7))
            }
        } finally {
            rows.close()
        }
        // address points count under the keys they carry
        var addresses = 0L
        store.stmt("SELECT kind, postcode IS NOT NULL, city IS NOT NULL, country IS NOT NULL, sum(n) FROM street GROUP BY 1, 2, 3, 4") { st ->
            while (st.step()) {
                val n = st.getLong(4).toInt()
                if (n == 0) continue
                addresses += n
                fun add(key: String) = keys.merge(dict.internKey(key), n, Int::plus)
                add(Addresses.HOUSENUMBER)
                when (st.getLong(0).toInt()) {
                    Addresses.STREET -> add("addr:street")
                    Addresses.PLACE -> add("addr:place")
                }
                if (st.getLong(1) != 0L) add("addr:postcode")
                if (st.getLong(2) != 0L) add("addr:city")
                if (st.getLong(3) != 0L) add("addr:country")
            }
        }
        store.stmt("SELECT min(west), min(south), max(east), max(north) FROM street_rt") { st ->
            if (st.step() && !st.isNull(0)) {
                west = minOf(west, st.getLong(0)); south = minOf(south, st.getLong(1))
                east = maxOf(east, st.getLong(2)); north = maxOf(north, st.getLong(3))
            }
        }
        val insert = store.prepare("INSERT OR REPLACE INTO stat(k, v, n) VALUES (?1, ?2, ?3)")
        try {
            fun row(k: Int, v: Int, n: Int) {
                insert.bindLong(1, k.toLong())
                insert.bindLong(2, v.toLong())
                insert.bindLong(3, n.toLong())
                insert.step()
                insert.reset()
            }
            for ((k, n) in keys) row(k, StoreSchema.ANY_VALUE, n)
            for ((p, n) in presets) row(StoreSchema.PRESET_KEY, p, n)
        } finally {
            insert.close()
        }
        putMeta("places", places.toString())
        putMeta("addresses", addresses.toString())
        putMeta("trees", store.long("SELECT count(*) FROM tree").toString())
        if (south <= north) putMeta("bounds", "$west,$south,$east,$north") else store.stmt("DELETE FROM meta WHERE key = 'bounds'") { it.step() }
    }

    private fun vocabulary() {
        // the kinds are read again from the statistics just written
        store.invalidate()
        val words = HashMap<String, Long>()
        for (table in listOf("place_fts", "street_fts")) {
            store.exec("DROP TABLE IF EXISTS temp.vocab")
            store.exec("CREATE VIRTUAL TABLE temp.vocab USING fts5vocab(main, $table, row)")
            val terms = store.prepare("SELECT term, doc FROM temp.vocab")
            try {
                while (terms.step()) {
                    val w = terms.getText(0)
                    if (Vocabulary.keeps(w)) words.merge(w, terms.getLong(1), Long::plus)
                }
            } finally {
                terms.close()
            }
            store.exec("DROP TABLE temp.vocab")
        }
        for ((w, n) in store.kindWords(langs).vocabulary) words.merge(w, n, Long::plus)
        Vocabulary.write(store, words)
    }

    private fun putMeta(key: String, value: String) {
        store.stmt("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)") { st -> st.bindText(1, key); st.bindText(2, value); st.step() }
    }
}
