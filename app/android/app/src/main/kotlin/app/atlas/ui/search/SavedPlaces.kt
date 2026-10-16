package app.atlas.ui.search

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** One saved place in a list: the place as it was saved (with its tags, so it shows offline) and when. */
data class SavedPlace(val listId: Int, val place: Place, val addedAt: Instant)

/** A list of saved places, drawn as a notebook: its name (null for the default list), its colour and its places, oldest first. */
data class SavedList(val id: Int, val name: String?, val colour: Long, val createdAt: Instant, val places: List<SavedPlace>) {
    val isDefault: Boolean get() = id == SavedPlaces.DEFAULT_LIST
}

/** A search made before: what was typed, and the place it led to when it led to one. */
data class Recent(val text: String, val place: Place?, val at: Instant)

/** A quest answered from this phone: the place, the tag asked, the answer, when, and the note it left (when known). */
data class Answer(val placeKey: String, val key: String, val yes: Boolean, val at: Instant, val noteUrl: String?)

/** A query kept as a map layer, and whether it should alert when something new matches. */
data class SavedQuery(val text: String, val alert: Boolean, val createdAt: Instant)

/**
 * What the user keeps of the search package, on the phone only, in one small SQLite file: lists of saved places,
 * recent searches, queries kept as layers and brands they hid. Every read is a [StateFlow] that changes after each
 * write. Writes are quick and blocking (call them off the main thread); one connection, guarded by a lock.
 */
class SavedPlaces private constructor(private val conn: SQLiteConnection, private val clock: Clock) : Closeable {
    private val lock = ReentrantLock()
    private val statements = HashMap<String, SQLiteStatement>()
    private var closed = false

    private val listsState = MutableStateFlow<List<SavedList>>(emptyList())
    private val recentsState = MutableStateFlow<List<Recent>>(emptyList())
    private val layersState = MutableStateFlow<List<SavedQuery>>(emptyList())
    private val hiddenState = MutableStateFlow<Set<String>>(emptySet())
    private val answersState = MutableStateFlow<List<Answer>>(emptyList())

    /** Every list, the default one first, then in the order they were made. */
    val lists: StateFlow<List<SavedList>> = listsState.asStateFlow()

    /** Recent searches, newest first. */
    val recents: StateFlow<List<Recent>> = recentsState.asStateFlow()

    /** Queries kept as layers, newest first. */
    val layers: StateFlow<List<SavedQuery>> = layersState.asStateFlow()

    /** Brand ids (name-suggestion-index) the user hid: their tiles and pins stay out of sight. */
    val hiddenBrands: StateFlow<Set<String>> = hiddenState.asStateFlow()

    /** Quests answered from this phone, newest first: so they are not asked again, and counted. */
    val answers: StateFlow<List<Answer>> = answersState.asStateFlow()

    /** True when the quest for tag [key] of the place with [placeKey] was answered from this phone. */
    fun answered(placeKey: String, key: String): Boolean = answers.value.any { it.placeKey == placeKey && it.key == key }

    /** Keeps an answer sent as a note ([noteUrl] its link): asked once, counted. */
    fun recordAnswer(placeKey: String, key: String, yes: Boolean, noteUrl: String?) = write {
        stmt("INSERT OR REPLACE INTO answer(place_key, key, yes, at, note) VALUES (?1, ?2, ?3, ?4, ?5)") { st ->
            st.bindText(1, placeKey)
            st.bindText(2, key)
            st.bindLong(3, if (yes) 1 else 0)
            st.bindLong(4, clock.millis())
            noteUrl?.let { st.bindText(5, it) } ?: st.bindNull(5)
            st.step()
        }
        loadAnswers()
    }

    /** The ids of the lists that hold the place with [key]. */
    fun listsHolding(key: String): Set<Int> = lists.value.filter { l -> l.places.any { it.place.key == key } }.mapTo(HashSet()) { it.id }

    /** True when any list holds the place with [key]. */
    fun isSaved(key: String): Boolean = lists.value.any { l -> l.places.any { it.place.key == key } }

    /** Puts [place] in list [listId] (at its end); saving it again keeps its place and refreshes its tags. */
    fun save(place: Place, listId: Int = DEFAULT_LIST) = write {
        require(listExists(listId)) { "No list $listId" }
        val position = stmt("SELECT coalesce(max(position), -1) + 1 FROM saved WHERE list = ?1") { st ->
            st.bindLong(1, listId.toLong())
            if (st.step()) st.getLong(0) else 0L
        }
        stmt(
            "INSERT INTO saved(list, key, osm, lat, lon, tags, source, added, position) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9) " +
                "ON CONFLICT(list, key) DO UPDATE SET tags = excluded.tags, lat = excluded.lat, lon = excluded.lon",
        ) { st ->
            st.bindLong(1, listId.toLong())
            st.bindText(2, place.key)
            place.id?.let { st.bindText(3, it.toString()) } ?: st.bindNull(3)
            st.bindDouble(4, place.location.lat)
            st.bindDouble(5, place.location.lon)
            st.bindText(6, tagsJson(place.tags))
            st.bindText(7, place.source)
            st.bindLong(8, clock.millis())
            st.bindLong(9, position)
            st.step()
        }
        loadLists()
    }

    /** Takes the place with [key] out of list [listId]. */
    fun remove(key: String, listId: Int) = write {
        stmt("DELETE FROM saved WHERE list = ?1 AND key = ?2") { st ->
            st.bindLong(1, listId.toLong())
            st.bindText(2, key)
            st.step()
        }
        loadLists()
    }

    /** Saves [place] in [listId] when it is not there, else takes it out; true when it is saved afterwards. */
    fun toggle(place: Place, listId: Int = DEFAULT_LIST): Boolean {
        val there = listId in listsHolding(place.key)
        if (there) remove(place.key, listId) else save(place, listId)
        return !there
    }

    /** Makes a new list called [name] and returns its id; its colour is the next of the notebook colours. */
    fun createList(name: String, colour: Long? = null): Int = write {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "A list needs a name" }
        val count = stmt("SELECT count(*) FROM list") { st -> if (st.step()) st.getLong(0).toInt() else 0 }
        stmt("INSERT INTO list(name, colour, created, position) VALUES (?1, ?2, ?3, ?4)") { st ->
            st.bindText(1, clean)
            st.bindLong(2, colour ?: COLOURS[count % COLOURS.size])
            st.bindLong(3, clock.millis())
            st.bindLong(4, count.toLong())
            st.step()
        }
        val id = stmt("SELECT last_insert_rowid()") { st -> if (st.step()) st.getLong(0).toInt() else -1 }
        loadLists()
        id
    }

    fun renameList(id: Int, name: String) = write {
        val clean = name.trim().take(MAX_NAME)
        require(clean.isNotEmpty()) { "A list needs a name" }
        stmt("UPDATE list SET name = ?2 WHERE id = ?1") { st ->
            st.bindLong(1, id.toLong())
            st.bindText(2, clean)
            st.step()
        }
        loadLists()
    }

    /** Deletes list [id] and what it holds; the default list stays (it is emptied instead). */
    fun deleteList(id: Int) = write {
        stmt("DELETE FROM saved WHERE list = ?1") { st -> st.bindLong(1, id.toLong()); st.step() }
        if (id != DEFAULT_LIST) stmt("DELETE FROM list WHERE id = ?1") { st -> st.bindLong(1, id.toLong()); st.step() }
        loadLists()
    }

    /** Remembers a search: [text] as typed, and the [place] it led to; the same search again moves to the top. */
    fun addRecent(text: String, place: Place? = null) = write {
        val t = text.trim().take(MAX_RECENT_TEXT)
        if (t.isEmpty() && place == null) return@write
        stmt("DELETE FROM recent WHERE lower(text) = lower(?1) OR (place_key IS NOT NULL AND place_key = ?2)") { st ->
            st.bindText(1, t)
            place?.let { st.bindText(2, it.key) } ?: st.bindNull(2)
            st.step()
        }
        stmt("INSERT INTO recent(text, place_key, osm, lat, lon, tags, at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)") { st ->
            st.bindText(1, t)
            if (place != null) {
                st.bindText(2, place.key)
                place.id?.let { st.bindText(3, it.toString()) } ?: st.bindNull(3)
                st.bindDouble(4, place.location.lat)
                st.bindDouble(5, place.location.lon)
                st.bindText(6, tagsJson(place.tags))
            } else {
                for (i in 2..6) st.bindNull(i)
            }
            st.bindLong(7, clock.millis())
            st.step()
        }
        exec("DELETE FROM recent WHERE rowid NOT IN (SELECT rowid FROM recent ORDER BY at DESC, rowid DESC LIMIT $MAX_RECENTS)")
        loadRecents()
    }

    fun clearRecents() = write {
        exec("DELETE FROM recent")
        loadRecents()
    }

    /** Keeps the query [text] as a layer ([alert]: tell me when something new matches). */
    fun keepLayer(text: String, alert: Boolean = false) = write {
        val t = text.trim()
        require(t.isNotEmpty()) { "A layer needs a query" }
        stmt("INSERT INTO layer(text, alert, created) VALUES (?1, ?2, ?3) ON CONFLICT(text) DO UPDATE SET alert = excluded.alert") { st ->
            st.bindText(1, t)
            st.bindLong(2, if (alert) 1 else 0)
            st.bindLong(3, clock.millis())
            st.step()
        }
        loadLayers()
    }

    fun removeLayer(text: String) = write {
        stmt("DELETE FROM layer WHERE text = ?1") { st -> st.bindText(1, text.trim()); st.step() }
        loadLayers()
    }

    /** Hides brand [id] (its tile and its pins), or shows it again. */
    fun hideBrand(id: String, hidden: Boolean) = write {
        if (hidden) stmt("INSERT OR IGNORE INTO hidden_brand(id) VALUES (?1)") { st -> st.bindText(1, id); st.step() }
        else stmt("DELETE FROM hidden_brand WHERE id = ?1") { st -> st.bindText(1, id); st.step() }
        loadHidden()
    }

    override fun close() = lock.withLock {
        if (closed) return@withLock
        closed = true
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
        conn.close()
    }

    // ------------------------------------------------------------------------------------------------- internals

    private inline fun <T> write(block: () -> T): T = lock.withLock {
        check(!closed) { "The saved places are closed" }
        block()
    }

    private inline fun <T> stmt(sql: String, block: (SQLiteStatement) -> T): T {
        val st = statements.getOrPut(sql) { conn.prepare(sql) }
        try {
            return block(st)
        } finally {
            st.reset()
            st.clearBindings()
        }
    }

    private fun exec(sql: String) = conn.execSQL(sql)

    private fun listExists(id: Int): Boolean = stmt("SELECT 1 FROM list WHERE id = ?1") { st -> st.bindLong(1, id.toLong()); st.step() }

    private fun setUp() {
        exec("PRAGMA journal_mode=WAL")
        exec("PRAGMA synchronous=NORMAL")
        exec("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT)")
        exec("CREATE TABLE IF NOT EXISTS list(id INTEGER PRIMARY KEY, name TEXT, colour INTEGER NOT NULL, created INTEGER NOT NULL, position INTEGER NOT NULL)")
        exec(
            "CREATE TABLE IF NOT EXISTS saved(list INTEGER NOT NULL, key TEXT NOT NULL, osm TEXT, lat REAL NOT NULL, lon REAL NOT NULL, " +
                "tags TEXT NOT NULL, source TEXT NOT NULL, added INTEGER NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(list, key))",
        )
        exec("CREATE TABLE IF NOT EXISTS recent(text TEXT NOT NULL, place_key TEXT, osm TEXT, lat REAL, lon REAL, tags TEXT, at INTEGER NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS layer(text TEXT PRIMARY KEY, alert INTEGER NOT NULL, created INTEGER NOT NULL)")
        exec("CREATE TABLE IF NOT EXISTS hidden_brand(id TEXT PRIMARY KEY)")
        exec("CREATE TABLE IF NOT EXISTS answer(place_key TEXT NOT NULL, key TEXT NOT NULL, yes INTEGER NOT NULL, at INTEGER NOT NULL, note TEXT, PRIMARY KEY(place_key, key))")
        exec("INSERT OR REPLACE INTO meta(key, value) VALUES ('schema', '$SCHEMA')")
        // The default list always exists, first.
        stmt("INSERT OR IGNORE INTO list(id, name, colour, created, position) VALUES (?1, NULL, ?2, ?3, -1)") { st ->
            st.bindLong(1, DEFAULT_LIST.toLong())
            st.bindLong(2, COLOURS[0])
            st.bindLong(3, clock.millis())
            st.step()
        }
        loadLists()
        loadRecents()
        loadLayers()
        loadHidden()
        loadAnswers()
    }

    private fun loadAnswers() {
        val out = ArrayList<Answer>()
        stmt("SELECT place_key, key, yes, at, note FROM answer ORDER BY at DESC") { st ->
            while (st.step()) out += Answer(st.getText(0), st.getText(1), st.getLong(2) != 0L, Instant.ofEpochMilli(st.getLong(3)), if (st.isNull(4)) null else st.getText(4))
        }
        answersState.value = out
    }

    private fun loadLists() {
        val places = HashMap<Int, MutableList<SavedPlace>>()
        stmt("SELECT list, osm, lat, lon, tags, source, added, key FROM saved ORDER BY position, added") { st ->
            while (st.step()) {
                val list = st.getLong(0).toInt()
                val place = placeOf(st, osm = 1, lat = 2, lon = 3, tags = 4, source = st.getText(5)) ?: continue
                places.getOrPut(list) { ArrayList() } += SavedPlace(list, place, Instant.ofEpochMilli(st.getLong(6)))
            }
        }
        val out = ArrayList<SavedList>()
        stmt("SELECT id, name, colour, created FROM list ORDER BY position, id") { st ->
            while (st.step()) {
                val id = st.getLong(0).toInt()
                out += SavedList(id, if (st.isNull(1)) null else st.getText(1), st.getLong(2), Instant.ofEpochMilli(st.getLong(3)), places[id].orEmpty())
            }
        }
        listsState.value = out
    }

    private fun loadRecents() {
        val out = ArrayList<Recent>()
        stmt("SELECT text, osm, lat, lon, tags, at, place_key FROM recent ORDER BY at DESC, rowid DESC") { st ->
            while (st.step()) {
                val place = if (st.isNull(2)) null else placeOf(st, osm = 1, lat = 2, lon = 3, tags = 4, source = "osm")
                out += Recent(st.getText(0), place, Instant.ofEpochMilli(st.getLong(5)))
            }
        }
        recentsState.value = out
    }

    private fun loadLayers() {
        val out = ArrayList<SavedQuery>()
        stmt("SELECT text, alert, created FROM layer ORDER BY created DESC") { st ->
            while (st.step()) out += SavedQuery(st.getText(0), st.getLong(1) != 0L, Instant.ofEpochMilli(st.getLong(2)))
        }
        layersState.value = out
    }

    private fun loadHidden() {
        val out = HashSet<String>()
        stmt("SELECT id FROM hidden_brand") { st -> while (st.step()) out += st.getText(0) }
        hiddenState.value = out
    }

    private fun placeOf(st: SQLiteStatement, osm: Int, lat: Int, lon: Int, tags: Int, source: String): Place? {
        val id = if (st.isNull(osm)) null else OsmId.parse(st.getText(osm))
        val t = runCatching { tagsOf(st.getText(tags)) }.getOrNull() ?: return null
        return Place(id, LatLon(st.getDouble(lat), st.getDouble(lon)), t, source = source)
    }

    companion object {
        /** The list the bookmark button saves to: "Saved". */
        const val DEFAULT_LIST = 1

        private const val SCHEMA = 1
        private const val MAX_RECENTS = 20
        private const val MAX_RECENT_TEXT = 200
        private const val MAX_NAME = 60

        /** The notebook covers, in turn, exactly as the Lists board paints them: blue, red, purple, orange, green, taupe. */
        val COLOURS = longArrayOf(0xFF3E5C8C, 0xFFC8322A, 0xFF7B4DFF, 0xFFE8922A, 0xFF2F7A3A, 0xFF6B6358)

        private val driver by lazy { BundledSQLiteDriver() }

        /**
         * Opens (or creates) the store in [file]; null keeps it in memory (tests). A file that cannot be read (damaged,
         * or not a database) is put aside as `.broken`, never deleted, and a fresh store takes its place: the search
         * screens must open whatever happened to the file. If even that fails the store lives in memory this run.
         */
        fun open(file: File?, clock: Clock = Clock.systemUTC()): SavedPlaces {
            try {
                return openAt(file, clock)
            } catch (e: Exception) {
                if (file != null) {
                    for (suffix in listOf("", "-wal", "-shm")) runCatching { File(file.path + suffix).renameTo(File(file.path + suffix + ".broken")) }
                    runCatching { return openAt(file, clock) }
                }
                return openAt(null, clock)
            }
        }

        private fun openAt(file: File?, clock: Clock): SavedPlaces {
            file?.absoluteFile?.parentFile?.mkdirs()
            val conn = driver.open(file?.absolutePath ?: ":memory:")
            try {
                return SavedPlaces(conn, clock).also { s -> s.lock.withLock { s.setUp() } }
            } catch (e: Exception) {
                runCatching { conn.close() }
                throw e
            }
        }

        internal fun tagsJson(tags: Map<String, String>): String = JsonObject(tags.mapValues { JsonPrimitive(it.value) }).toString()

        internal fun tagsOf(json: String): Map<String, String> =
            Json.parseToJsonElement(json).jsonObject.entries.associateTo(LinkedHashMap()) { (k, v) -> k to v.jsonPrimitive.content }
    }
}
