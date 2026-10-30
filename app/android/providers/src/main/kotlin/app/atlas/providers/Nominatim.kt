package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * Nominatim, the OpenStreetMap Foundation's geocoder: forward search, reverse, and lookup by OSM id.
 *
 * Its usage policy is strict and Atlas keeps it: at most one request a second (the [Http] limiter enforces it for the
 * host), a real User-Agent, results cached here, no search-as-you-type ([typeahead] is false), and the OSM credit shown.
 */
class Nominatim(private val http: Http, private val base: String = "https://nominatim.openstreetmap.org") : Geocoder {
    override val info = INFO
    override val typeahead = false

    override fun state() = ProviderState.READY

    private val cache = Lru<String, List<Place>>(256, ttlMs = 24 * 3600_000L)

    override suspend fun search(text: String, near: LatLon?, lang: String?, limit: Int, box: BBox?): List<Place> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val url = builder("search").addQueryParameter("q", q)
            .addQueryParameter("limit", limit.coerceIn(1, 40).toString())
            .apply {
                lang?.let { addQueryParameter("accept-language", it) }
                // Nominatim has no point bias; a viewbox around the point (not bounded) does the same job.
                val view = box ?: near?.let { BBox.around(it, 25_000.0) }
                view?.let { addQueryParameter("viewbox", "${coord(it.west)},${coord(it.north)},${coord(it.east)},${coord(it.south)}") }
            }.build()
        return cached(url) { parseList(it) }
    }

    override suspend fun reverse(at: LatLon, lang: String?): Place? = reverse(at, lang, 18)

    /** The place at [at]; [zoom] 18 is a building, 16 a street, 10 a city. */
    suspend fun reverse(at: LatLon, lang: String?, zoom: Int): Place? {
        val url = builder("reverse").addQueryParameter("lat", coord(at.lat)).addQueryParameter("lon", coord(at.lon))
            .addQueryParameter("zoom", zoom.coerceIn(0, 18).toString())
            .apply { lang?.let { addQueryParameter("accept-language", it) } }
            .build()
        return cached(url) { json -> listOfNotNull(json.asObject()?.takeIf { it["error"] == null }?.let(::place)) }.firstOrNull()
    }

    /** Details for up to 50 OSM elements at once. */
    suspend fun lookup(ids: List<OsmId>, lang: String? = null): List<Place> {
        if (ids.isEmpty()) return emptyList()
        return ids.chunked(50).flatMap { chunk ->
            val url = builder("lookup").addQueryParameter("osm_ids", chunk.joinToString(",") { "${it.type.letter.uppercaseChar()}${it.id}" })
                .apply { lang?.let { addQueryParameter("accept-language", it) } }
                .build()
            cached(url) { parseList(it) }
        }
    }

    private fun builder(path: String): HttpUrl.Builder = base.toHttpUrl().newBuilder().addPathSegment(path)
        .addQueryParameter("format", "jsonv2")
        .addQueryParameter("addressdetails", "1")
        .addQueryParameter("extratags", "1")
        .addQueryParameter("namedetails", "1")

    private suspend fun cached(url: HttpUrl, parse: (JsonElement) -> List<Place>): List<Place> {
        val key = url.toString()
        cache.get(key)?.let { return it }
        val result = parse(http.execute(http.request(url).build()).requireOk().json())
        cache.put(key, result)
        return result
    }

    companion object {
        val INFO = ProviderInfo(
            id = "nominatim",
            name = "Nominatim",
            licence = "ODbL",
            attribution = "Search by Nominatim · © OpenStreetMap contributors",
            url = "https://nominatim.openstreetmap.org",
            note = "Asked only for the address at a place where Photon cannot answer: one request a second at most, never while you type.",
            hosts = listOf("nominatim.openstreetmap.org"),
        )

        private fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

        fun parseList(json: JsonElement): List<Place> = (json as? JsonArray)?.mapNotNull { it.asObject()?.let(::place) } ?: emptyList()

        /** One Nominatim result (jsonv2) as a place: name details and extra tags become tags, the address becomes `addr:*`. */
        fun place(o: JsonObject): Place? {
            val lat = o.dbl("lat") ?: return null
            val lon = o.dbl("lon") ?: return null
            val type = when (o.str("osm_type")) {
                "node" -> OsmType.NODE
                "way" -> OsmType.WAY
                "relation" -> OsmType.RELATION
                else -> null
            }
            val id = type?.let { t -> o.long("osm_id")?.let { OsmId(t, it) } }
            val tags = LinkedHashMap<String, String>()
            o.obj("namedetails")?.forEach { (k, v) -> v.asString()?.let { tags[k] = it } }
            o.obj("extratags")?.forEach { (k, v) -> v.asString()?.let { tags.putIfAbsent(k, it) } }
            val cat = o.str("category")
            val value = o.str("type")
            // "place=house" is Nominatim's word for a bare address, not a tag on the element.
            if (cat != null && value != null && !(cat == "place" && value == "house")) tags.putIfAbsent(cat, value)
            o.str("name")?.let { tags.putIfAbsent("name", it) }
            o.obj("address")?.let { a ->
                AddressTags.put(
                    tags, a.str("road") ?: a.str("pedestrian") ?: a.str("footway"), a.str("house_number"), a.str("postcode"),
                    a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("municipality"), a.str("country_code"),
                )
            }
            val bb = o.arr("boundingbox").mapNotNull { it.asDouble() }
            val bbox = if (bb.size == 4) BBox(bb[2], bb[0], bb[3], bb[1]) else null
            val extra = LinkedHashMap<String, String>()
            o.str("display_name")?.let { extra["label"] = it }
            o.int("place_rank")?.let { extra["nominatim:rank"] = it.toString() }
            o.dbl("importance")?.let { extra["nominatim:importance"] = tagNumber(it) }
            return Place(id, LatLon(lat, lon), tags, bbox, source = "nominatim", extra = extra)
        }
    }
}
