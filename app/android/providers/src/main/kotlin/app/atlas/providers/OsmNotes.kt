package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One comment of an OSM note: who (null when anonymous), when, what they did and said. */
data class NoteComment(val time: Instant?, val action: String, val text: String, val user: String?)

/** An OpenStreetMap note: a message pinned on the map for mappers to read and act on. */
data class OsmNote(
    val id: Long,
    val location: LatLon,
    /** "open" or "closed". */
    val status: String,
    val created: Instant?,
    val comments: List<NoteComment>,
) {
    /** The page where anyone can read and answer it. */
    val url: String get() = "https://www.openstreetmap.org/note/$id"
}

/**
 * "Leave a note for mappers": anonymous OpenStreetMap notes. A note needs no account; it is public at once and anyone may
 * answer or close it. [request] only builds the request, so the app (and the tests) can see what would be sent; [create]
 * sends it. Notes in an area can be read to show what others reported.
 */
class OsmNotes(private val http: Http, private val base: String = "https://api.openstreetmap.org", private val signature: String? = "via Atlas") : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    /** The POST that would create an anonymous note at [at] saying [text]; nothing is sent. */
    fun request(at: LatLon, text: String): Request {
        val body = text.trim()
        require(body.isNotEmpty()) { "A note needs some text" }
        val full = if (signature.isNullOrBlank()) body else "$body\n\n$signature"
        require(full.length <= MAX_TEXT) { "A note holds at most $MAX_TEXT characters; this one has ${full.length}" }
        require(at.lat in -90.0..90.0 && at.lon in -180.0..180.0) { "Not a position on Earth: $at" }
        val form = FormBody.Builder()
            .add("lat", c(at.lat))
            .add("lon", c(at.lon))
            .add("text", full)
            .build()
        return http.request("$base/api/0.6/notes.json").post(form).build()
    }

    /** Creates the note and returns it as OSM stored it. It is public as soon as this returns. */
    suspend fun create(at: LatLon, text: String): OsmNote {
        val res = http.execute(request(at, text))
        if (res.code == 429) throw ProviderException(ID, "OpenStreetMap asks to wait before leaving another note")
        if (!res.ok) throw ProviderException(ID, "OpenStreetMap did not take the note (HTTP ${res.code}): ${res.text.take(200)}")
        return parse(res.json().asObject() ?: throw ProviderException(ID, "OpenStreetMap answered something unexpected"))
            ?: throw ProviderException(ID, "OpenStreetMap's answer has no note in it")
    }

    /** Notes in [box] (at most 25 km² by the API's rule), open ones and those closed within [closedDays]. */
    suspend fun inBox(box: BBox, limit: Int = 100, closedDays: Int = 0): List<OsmNote> {
        val url = "$base/api/0.6/notes.json".toHttpUrl().newBuilder()
            .addQueryParameter("bbox", "${c(box.west)},${c(box.south)},${c(box.east)},${c(box.north)}")
            .addQueryParameter("limit", limit.coerceIn(1, 10_000).toString())
            .addQueryParameter("closed", closedDays.toString())
            .build()
        val json = http.execute(http.request(url).build()).requireOk().json().asObject() ?: return emptyList()
        return json.arr("features").mapNotNull { it.asObject()?.let(::parse) }
    }

    companion object {
        const val ID = "osm-notes"
        const val MAX_TEXT = 2000

        val INFO = ProviderInfo(
            id = ID, name = "OpenStreetMap notes", licence = "ODbL", attribution = "© OpenStreetMap contributors",
            url = "https://www.openstreetmap.org", note = "Notes are public and anonymous; mappers nearby read them.",
        )

        private fun c(v: Double) = String.format(Locale.ROOT, "%.7f", v).trimEnd('0').trimEnd('.')

        private val OSM_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT)

        /** OSM writes note times as "2026-10-12 15:10:37 UTC". */
        internal fun time(s: String?): Instant? = s?.let { runCatching { LocalDateTime.parse(it, OSM_TIME).toInstant(ZoneOffset.UTC) }.getOrNull() }

        /** One note as the API's GeoJSON Feature. */
        fun parse(feature: JsonObject): OsmNote? {
            val p = feature.obj("properties") ?: return null
            val c = feature.obj("geometry")?.arr("coordinates") ?: return null
            val lon = c.getOrNull(0).asDouble() ?: return null
            val lat = c.getOrNull(1).asDouble() ?: return null
            val comments = p.arr("comments").mapNotNull { it.asObject() }.map { o ->
                NoteComment(time(o.str("date")), o.str("action") ?: "commented", o.str("text").orEmpty(), o.str("user"))
            }
            return OsmNote(p.long("id") ?: return null, LatLon(lat, lon), p.str("status") ?: "open", time(p.str("date_created")), comments)
        }
    }
}
