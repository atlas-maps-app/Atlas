package app.atlas.search

import app.atlas.places.Presets

/**
 * Files a store's places anew under [filing], the category rules having changed since they were filed: each place's
 * category and symbol again from its stored tags, as an import works them out ([PlaceRecord.of]); where the category
 * changed, its lookup row and the kind token on its line of the text index too; then the categories' counts, where
 * places moved, and the typo vocabulary, which holds their names. Nothing is downloaded; tags, presets and brands stay
 * as they are.
 *
 * Takes the store's lock for [batch] places at a time, each batch a transaction, so an open store answers between them
 * (partly filed), and records the fingerprint last, so a re-file cut short (the app killed) goes on at the next open: a
 * place filed already comes out the same and is passed over.
 */
internal class StoreRefile(private val store: PlaceStore, private val filing: Filing, private val batch: Int = BATCH) {
    private val dict = store.dict

    /** A place the rules file otherwise: its key, tags and preset id, its category id as filed, and its new category and symbol. */
    private class Change(val key: Long, val tags: Map<String, String>, val preset: Int?, val old: Int?, val category: String?, val symbol: String)

    /** Files every place anew; returns how many changed their category or symbol. */
    fun run(): Int {
        var changed = 0
        var after = Long.MIN_VALUE
        while (true) {
            after = store.locked {
                val (changes, last) = read(after) ?: return@locked null
                if (changes.isNotEmpty()) transaction { write(changes) }
                changed += changes.size
                last
            } ?: break
        }
        store.locked {
            transaction {
                // the counts once a category changed, in this re-file or one cut short; the typo vocabulary always, as it
                // holds the categories' names, which may be all that changed
                StoreFinish(store, store.languages.ifEmpty { PlaceStore.DEFAULT_LANGS }).refiled(counts = store.meta(RECOUNT) != null)
                store.stmt("DELETE FROM meta WHERE key = ?1") { st -> st.bindText(1, RECOUNT); st.step() }
                putMeta(FILED, filing.fingerprint)
            }
            store.invalidate()
            // what the filing wrote goes into the file now and the log beside it is emptied: Android ends the app with the
            // store open, and an update that puts another file in this one's place must find no pages of this one there
            runCatching { store.exec("PRAGMA wal_checkpoint(TRUNCATE)") }
        }
        return changed
    }

    /** The places after key [after] (at most [batch]) that the rules file otherwise, and the last key read; null past the last place. */
    private fun read(after: Long): Pair<List<Change>, Long>? = store.stmt("SELECT id, tags, preset, category, symbol FROM place WHERE id > ?1 ORDER BY id LIMIT ?2") { st ->
        st.bindLong(1, after)
        st.bindLong(2, batch.toLong())
        val changes = ArrayList<Change>()
        var last: Long? = null
        while (st.step()) {
            val key = st.getLong(0)
            last = key
            val tags = TagBlob.decode(st.getBlob(1), dict)
            val preset = if (st.isNull(2)) null else st.getLong(2).toInt()
            val old = if (st.isNull(3)) null else st.getLong(3).toInt()
            val category = filing.categoryOf(tags)
            val geometry = PlaceRecord.geometryOf(StoreSchema.unpack(key)?.type, tags)
            val symbol = PlaceRecord.symbolOf(preset?.let { Presets.byId(dict.value(it)) }, category, tags, geometry)
            if (category?.id != old?.let(dict::value) || symbol != dict.value(st.getLong(4).toInt())) {
                changes += Change(key, tags, preset, old, category?.id, symbol)
            }
        }
        last?.let { changes to it }
    }

    private fun write(changes: List<Change>) {
        var moved = false
        for (c in changes) {
            val category = c.category?.let(dict::internValue)
            store.stmt("UPDATE place SET category = ?2, symbol = ?3 WHERE id = ?1") { st ->
                st.bindLong(1, c.key)
                if (category == null) st.bindNull(2) else st.bindLong(2, category.toLong())
                st.bindLong(3, dict.internValue(c.symbol).toLong())
                st.step()
            }
            if (category == c.old) continue
            moved = true
            c.old?.let { lookup("DELETE FROM lookup WHERE k = ?1 AND v = ?2 AND place = ?3", it, c.key) }
            category?.let { lookup("INSERT OR IGNORE INTO lookup(k, v, place) VALUES (?1, ?2, ?3)", it, c.key) }
            // a contentless index forgets a line given the very text it was given: the one with the old kind token
            val row = StoreSchema.textRow(c.key, Importance.of(c.tags))
            store.stmt("INSERT INTO place_fts(place_fts, rowid, text) VALUES ('delete', ?1, ?2)") { st ->
                st.bindLong(1, row)
                st.bindText(2, SearchText.indexText(c.tags, KindWords.tokensOf(c.preset, c.old, c.tags, dict)))
                st.step()
            }
            store.stmt("INSERT INTO place_fts(rowid, text) VALUES (?1, ?2)") { st ->
                st.bindLong(1, row)
                st.bindText(2, SearchText.indexText(c.tags, KindWords.tokensOf(c.preset, category, c.tags, dict)))
                st.step()
            }
        }
        if (moved) putMeta(RECOUNT, filing.fingerprint)
    }

    private fun lookup(sql: String, category: Int, place: Long) = store.stmt(sql) { st ->
        st.bindLong(1, StoreSchema.CATEGORY_KEY.toLong())
        st.bindLong(2, category.toLong())
        st.bindLong(3, place)
        st.step()
    }

    private fun putMeta(key: String, value: String) {
        store.stmt("INSERT OR REPLACE INTO meta(key, value) VALUES (?1, ?2)") { st -> st.bindText(1, key); st.bindText(2, value); st.step() }
    }

    private inline fun transaction(block: () -> Unit) {
        store.exec("BEGIN")
        try {
            block()
            store.exec("COMMIT")
        } catch (e: Throwable) {
            if (store.inTransaction) store.exec("ROLLBACK")
            // ids handed out in the lost transaction are gone with it
            dict.forget()
            store.invalidate()
            throw e
        }
    }

    companion object {
        /** The metadata that holds the fingerprint of the rules a store's places are filed under ([Filing]). */
        const val FILED = "filed"

        /** Set while a re-file that moved places has yet to count them again: one cut short counts them when it goes on. */
        private const val RECOUNT = "recount"

        /** Places to a batch: the lock is held for milliseconds where none moves, a fraction of a second where all do. */
        private const val BATCH = 2_000
    }
}
