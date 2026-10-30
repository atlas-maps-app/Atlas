package app.atlas.ui.mine

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.atlas.Services
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.Place
import app.atlas.ui.search.SavedPlaces
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant

/** The file is not a backup this Atlas can read: [newer] when it comes from a later version, [tooBig] when it cannot be one. */
class BackupException(message: String, val newer: Boolean = false, val tooBig: Boolean = false) : IllegalArgumentException(message)

/**
 * Everything personal on this phone as one file, for "Move to a new phone" and "Export all": the settings (units, map
 * look, home and work, vehicles, the car), the lists with their places, the queries kept as layers and the brands
 * hidden. The provider keys stay behind (they are secrets: enter them again on the new phone), and so do the trail and
 * the barometer's log unless asked for. The file is plain JSON a person can read.
 */
object Backup {
    const val FORMAT = "atlas-backup"
    const val VERSION = 1

    /** The largest file taken for a backup: a real one is some kilobytes; a wrong choice in the file picker may be gigabytes. */
    const val MAX_BYTES = 32 * 1024 * 1024

    /** Reads a backup file's text from [stream], refusing one over [MAX_BYTES] before it fills the memory. */
    fun read(stream: java.io.InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw BackupException("That file is too big to be a backup.", tooBig = true)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** What an import did, for the screen to say. */
    data class Summary(val settings: Int, val lists: Int, val places: Int, val layers: Int)

    /** The settings that are not carried over: where the camera last looked is the new phone's own business. */
    private val SKIP = setOf("camera.lat", "camera.lon", "camera.zoom", "camera.bearing", "camera.pitch")

    /** The backup as JSON text, made from [services] at its clock's now. */
    suspend fun export(services: Services, trail: Boolean = false): String {
        val prefs = services.settings.data.first()
        val saved = services.saved
        return buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exported", services.clock.instant().toString())
            put("settings", settings(prefs))
            put("lists", buildJsonArray {
                for (l in saved.lists.value) add(buildJsonObject {
                    put("name", l.name?.let { JsonPrimitive(it) } ?: JsonNull)
                    put("colour", l.colour)
                    put("created", l.createdAt.toString())
                    put("places", buildJsonArray { for (p in l.places) add(place(p.place, p.addedAt)) })
                })
            })
            put("layers", buildJsonArray { for (q in saved.layers.value) add(buildJsonObject { put("text", q.text); put("alert", q.alert) }) })
            put("hiddenBrands", buildJsonArray { for (b in saved.hiddenBrands.value.sorted()) add(JsonPrimitive(b)) })
            if (trail) put("trail", buildJsonArray { for (p in services.mine.trail.points()) add(buildJsonArray { add(JsonPrimitive(p.time.epochSecond)); add(JsonPrimitive(p.at.lat)); add(JsonPrimitive(p.at.lon)) }) })
        }.toString()
    }

    /** Reads a backup into [services]: settings are set, lists are merged by name, places and layers are added. */
    suspend fun import(services: Services, text: String): Summary {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw BackupException("This is not an Atlas backup.")
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) throw BackupException("This is not an Atlas backup.")
        if (((root["version"] as? JsonPrimitive)?.intOrNull ?: 1) > VERSION) throw BackupException("This backup is from a newer Atlas.", newer = true)
        var settings = 0
        val entries = (root["settings"] as? JsonObject).orEmpty()
        services.settings.edit { p -> for ((k, v) in entries) if (k !in SKIP && put(p, k, v as? JsonObject ?: continue)) settings++ }
        val saved = services.saved
        var lists = 0
        var places = 0
        val existing = saved.lists.value
        for (e in (root["lists"] as? JsonArray).orEmpty()) {
            val o = e as? JsonObject ?: continue
            val name = (o["name"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val have = if (name == null) existing.firstOrNull { it.isDefault } else existing.firstOrNull { it.name.equals(name, true) }
            val id = have?.id ?: if (name == null) SavedPlaces.DEFAULT_LIST else saved.createList(name, (o["colour"] as? JsonPrimitive)?.longOrNull)
            if (have == null) lists++
            for (pe in (o["places"] as? JsonArray).orEmpty()) {
                val p = placeOf(pe as? JsonObject ?: continue) ?: continue
                if (id in saved.listsHolding(p.key)) continue
                saved.save(p, id)
                places++
            }
        }
        var layers = 0
        for (q in (root["layers"] as? JsonArray).orEmpty()) {
            val o = q as? JsonObject ?: continue
            val t = (o["text"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (saved.layers.value.none { it.text == t.trim() }) { saved.keepLayer(t, (o["alert"] as? JsonPrimitive)?.booleanOrNull ?: false); layers++ }
        }
        for (b in (root["hiddenBrands"] as? JsonArray).orEmpty()) (b as? JsonPrimitive)?.contentOrNull?.let { saved.hideBrand(it, true) }
        for (pt in (root["trail"] as? JsonArray).orEmpty()) {
            val a = pt as? JsonArray ?: continue
            if (a.size < 3) continue
            val t = (a[0] as? JsonPrimitive)?.longOrNull ?: continue
            services.mine.trail.record(LatLon((a[1] as? JsonPrimitive)?.doubleOrNull ?: continue, (a[2] as? JsonPrimitive)?.doubleOrNull ?: continue), Instant.ofEpochSecond(t))
        }
        return Summary(settings, lists, places, layers)
    }

    /**
     * Forgets everything personal: settings (home and work, vehicles, the car, the look), keys, every list, searches, kept
     * layers, hidden brands, the trail, the barometer's log, kept forecasts and the HTTP cache. Offline regions stay
     * (they hold no personal data); delete them in Offline.
     */
    suspend fun wipe(services: Services) {
        services.settings.edit { it.clear() }
        services.keys.clear()
        val saved = services.saved
        for (l in saved.lists.value) saved.deleteList(l.id)
        saved.clearRecents()
        for (q in saved.layers.value) saved.removeLayer(q.text)
        for (b in saved.hiddenBrands.value) saved.hideBrand(b, false)
        services.mine.trail.clear()
        services.mine.pressure.clear()
        services.dirs.providers.deleteRecursively()
        services.dirs.http.deleteRecursively()
    }

    private fun settings(p: Preferences): JsonObject = buildJsonObject {
        for ((k, v) in p.asMap().entries.sortedBy { it.key.name }) {
            if (k.name in SKIP) continue
            val (t, value) = when (v) {
                is Boolean -> "b" to JsonPrimitive(v)
                is Int -> "i" to JsonPrimitive(v)
                is Long -> "l" to JsonPrimitive(v)
                is Float -> "f" to JsonPrimitive(v)
                is Double -> "d" to JsonPrimitive(v)
                is String -> "s" to JsonPrimitive(v)
                is Set<*> -> "ss" to buildJsonArray { v.filterIsInstance<String>().sorted().forEach { add(JsonPrimitive(it)) } }
                else -> continue
            }
            put(k.name, buildJsonObject { put("t", t); put("v", value) })
        }
    }

    /**
     * Sets one imported setting. A value of another type than the one this phone already holds under that name is skipped
     * (DataStore would throw when the app next read it), and so is a value that does not read as its type.
     */
    private fun put(p: MutablePreferences, name: String, o: JsonObject): Boolean {
        val v = o["v"] as? JsonPrimitive ?: (o["v"] as? JsonArray)
        if (v == null) return false
        val type = (o["t"] as? JsonPrimitive)?.contentOrNull
        val value: Any = when (type) {
            "b" -> (v as? JsonPrimitive)?.booleanOrNull
            "i" -> (v as? JsonPrimitive)?.intOrNull
            "l" -> (v as? JsonPrimitive)?.longOrNull
            "f" -> (v as? JsonPrimitive)?.doubleOrNull?.toFloat()
            "d" -> (v as? JsonPrimitive)?.doubleOrNull
            "s" -> (v as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            "ss" -> (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
            else -> null
        } ?: return false
        val have = p.asMap().entries.firstOrNull { it.key.name == name }?.value
        if (have != null && have::class != value::class && !(have is Set<*> && value is Set<*>)) return false
        @Suppress("UNCHECKED_CAST")
        when (value) {
            is Boolean -> p[booleanPreferencesKey(name)] = value
            is Int -> p[intPreferencesKey(name)] = value
            is Long -> p[longPreferencesKey(name)] = value
            is Float -> p[floatPreferencesKey(name)] = value
            is Double -> p[doublePreferencesKey(name)] = value
            is String -> p[stringPreferencesKey(name)] = value
            is Set<*> -> p[stringSetPreferencesKey(name)] = value as Set<String>
        }
        return true
    }

    private fun place(p: Place, at: Instant): JsonObject = buildJsonObject {
        p.id?.let { put("id", it.toString()) }
        put("lat", p.location.lat)
        put("lon", p.location.lon)
        put("source", p.source)
        put("added", at.toString())
        put("tags", JsonObject(p.tags.mapValues { JsonPrimitive(it.value) }))
    }

    private fun placeOf(o: JsonObject): Place? {
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -180.0..180.0 } ?: return null
        val tags = (o["tags"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        return Place(runCatching { (o["id"] as? JsonPrimitive)?.contentOrNull?.let(OsmId::parse) }.getOrNull(), LatLon(lat, lon), tags, source = (o["source"] as? JsonPrimitive)?.contentOrNull ?: "osm")
    }
}
