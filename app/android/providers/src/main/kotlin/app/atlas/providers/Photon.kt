package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.OsmId
import app.atlas.model.OsmType
import app.atlas.model.Place
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.Locale

/**
 * Photon (komoot), the OpenStreetMap geocoder built for search-as-you-type: forward search biased to a location, and reverse.
 * Results map to [Place]s with the OSM id, the main tag, the name and `addr:*` tags; `extra["label"]` is a ready one-line address.
 */
class Photon(private val http: Http, private val base: String = "https://photon.komoot.io") : Geocoder {
    override val info = INFO
    override val typeahead = true

    override fun state() = ProviderState.READY

    override suspend fun search(text: String, near: LatLon?, lang: String?, limit: Int, box: BBox?): List<Place> {
        val q = text.trim()
        if (q.isEmpty()) return emptyList()
        val url = base.toHttpUrl().newBuilder().addPathSegments("api/")
            .addQueryParameter("q", q)
            .addQueryParameter("limit", limit.coerceIn(1, 50).toString())
            .apply {
                near?.let {
                    addQueryParameter("lat", coord(it.lat))
                    addQueryParameter("lon", coord(it.lon))
                }
                box?.let { addQueryParameter("bbox", "${coord(it.west)},${coord(it.south)},${coord(it.east)},${coord(it.north)}") }
                language(lang)?.let { addQueryParameter("lang", it) }
            }.build()
        return parse(get(url))
    }

    override suspend fun reverse(at: LatLon, lang: String?): Place? {
        val url = base.toHttpUrl().newBuilder().addPathSegment("reverse")
            .addQueryParameter("lat", coord(at.lat))
            .addQueryParameter("lon", coord(at.lon))
            .addQueryParameter("limit", "1")
            .apply { language(lang)?.let { addQueryParameter("lang", it) } }
            .build()
        return parse(get(url)).firstOrNull()
    }

    private suspend fun get(url: HttpUrl): JsonObject =
        http.execute(http.request(url).build()).requireOk().json().asObject() ?: throw ProviderException(id, "Photon answered something that is not GeoJSON")

    companion object {
        val INFO = ProviderInfo(
            id = "photon",
            name = "Photon (komoot)",
            licence = "ODbL",
            attribution = "Search by Photon (komoot) · © OpenStreetMap contributors",
            url = "https://photon.komoot.io",
            note = "Used only when your downloaded places have too little for a search.",
        )

        /** The public instance speaks these languages (it answers 400 to others); anything else falls back to local names. */
        private val LANGUAGES = setOf("en", "de", "fr")

        private fun language(lang: String?): String? = lang?.lowercase(Locale.ROOT)?.substringBefore('-')?.takeIf { it in LANGUAGES }

        private fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

        /** Parses a Photon GeoJSON FeatureCollection into places. */
        fun parse(json: JsonObject): List<Place> = json.arr("features").mapNotNull { f -> feature(f.asObject() ?: return@mapNotNull null) }

        private fun feature(f: JsonObject): Place? {
            val p = f.obj("properties") ?: return null
            val c = f.obj("geometry")?.arr("coordinates") ?: return null
            val lon = c.getOrNull(0).asDouble() ?: return null
            val lat = c.getOrNull(1).asDouble() ?: return null
            val type = when (p.str("osm_type")) {
                "N" -> OsmType.NODE
                "W" -> OsmType.WAY
                "R" -> OsmType.RELATION
                else -> null
            }
            val id = type?.let { t -> p.long("osm_id")?.let { OsmId(t, it) } }
            val tags = LinkedHashMap<String, String>()
            val key = p.str("osm_key")
            val value = p.str("osm_value")
            if (key != null && value != null) tags[key] = value
            p.str("name")?.let { tags["name"] = it }
            AddressTags.put(tags, p.str("street"), p.str("housenumber"), p.str("postcode"), p.str("city") ?: p.str("county"), p.str("countrycode"))
            val extent = p.arr("extent").mapNotNull { it.asDouble() }
            val bbox = if (extent.size == 4) BBox(minOf(extent[0], extent[2]), minOf(extent[1], extent[3]), maxOf(extent[0], extent[2]), maxOf(extent[1], extent[3])) else null
            val extra = LinkedHashMap<String, String>()
            p.str("type")?.let { extra["photon:type"] = it }
            label(p).takeIf { it.isNotEmpty() }?.let { extra["label"] = it }
            return Place(id, LatLon(lat, lon), tags, bbox, source = "photon", extra = extra)
        }

        /** "Westerkerk, Westermarkt, Amsterdam, Netherlands" from Photon's fields. */
        private fun label(p: JsonObject): String {
            val street = listOfNotNull(p.str("street"), p.str("housenumber")).joinToString(" ")
            return listOf(p.str("name"), street.ifEmpty { null }, p.str("city") ?: p.str("county"), p.str("country"))
                .filterNotNull().distinct().joinToString(", ")
        }
    }
}
