package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale

/** Shared plumbing for the rating providers that need the user's own key. */
abstract class KeyedRatings(protected val http: Http, private val keys: Keys) : RatingProvider {
    override fun state() = stateFor(info.keyNeed, keys.key(id))

    /** The user's key, or [NeedsKeyException]. */
    protected fun key(): String = keys.key(id)?.takeIf { it.isNotBlank() } ?: throw NeedsKeyException(id)

    protected suspend fun getJson(url: HttpUrl, headers: Map<String, String>): JsonObject {
        val req = http.request(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val res = http.execute(req)
        if (res.code == 401 || res.code == 403) throw ProviderException(id, "${info.name} refused the key (HTTP ${res.code})")
        return res.requireOk().json().asObject() ?: throw ProviderException(id, "${info.name} answered something unexpected")
    }

    protected fun coord(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    protected fun parseTime(s: String?): Instant? = s?.let {
        runCatching { Instant.parse(it) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull()
            ?: runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC) }.getOrNull()
    }
}

/**
 * Foursquare Places API (places-api.foursquare.com, version 2025-06-17) with the user's Service API key.
 * Ratings, price and tips are "Places Premium" fields, billed by Foursquare from the first call.
 */
class FoursquareRatings(http: Http, keys: Keys, private val base: String = "https://places-api.foursquare.com") : KeyedRatings(http, keys) {
    override val info = INFO
    override val symbol = "foursquare"

    override suspend fun rating(place: Place): Rating? {
        val key = key()
        val url = base.toHttpUrl().newBuilder().addPathSegments("places/search")
            .addQueryParameter("ll", "${coord(place.location.lat)},${coord(place.location.lon)}")
            .addQueryParameter("radius", "150")
            .addQueryParameter("limit", "10")
            .addQueryParameter("fields", "fsq_place_id,name,latitude,longitude,distance,rating,price,stats,tips,link,date_closed,popularity")
            .apply { place.name()?.let { addQueryParameter("query", it) } }
            .build()
        val json = getJson(url, mapOf("Authorization" to "Bearer $key", "X-Places-Api-Version" to API_VERSION))
        val results = json.arr("results").mapNotNull { it.asObject() }
        val hit = NameMatch.best(place.name(), place.location, results, nameOf = { it.str("name") }, where = ::latLon) ?: return null
        val fsqId = hit.str("fsq_place_id")
        val tips = hit.arr("tips").mapNotNull { it.asObject() }.map { t ->
            Review(ID, null, t.str("text"), null, parseTime(t.str("created_at")), url = t.str("url"), language = t.str("lang"))
        }
        return Rating(
            provider = ID,
            stars = hit.dbl("rating")?.let { it / 2.0 },
            count = hit.obj("stats")?.int("total_ratings") ?: 0,
            priceLevel = hit.int("price")?.coerceIn(1, 4),
            url = fsqId?.let { "https://foursquare.com/v/$it" },
            recent = tips.sortedByDescending { it.time }.take(5),
            attribution = INFO.attribution,
        )
    }

    private fun latLon(o: JsonObject): LatLon? {
        val lat = o.dbl("latitude") ?: o.obj("geocodes")?.obj("main")?.dbl("latitude") ?: return null
        val lon = o.dbl("longitude") ?: o.obj("geocodes")?.obj("main")?.dbl("longitude") ?: return null
        return LatLon(lat, lon)
    }

    companion object {
        const val ID = "foursquare"
        const val API_VERSION = "2025-06-17"
        val INFO = ProviderInfo(
            id = ID, name = "Foursquare", licence = "Foursquare terms", attribution = "Ratings by Foursquare", url = "https://foursquare.com",
            keyNeed = KeyNeed.REQUIRED,
            note = "Your own Service API key. Ratings are a paid Premium field, billed by Foursquare from the first call.",
            hosts = listOf("places-api.foursquare.com"),
        )
    }
}

/**
 * Yelp Places API (formerly Fusion) with the user's key. Yelp's API terms forbid showing its ratings alongside other
 * reviews and keeping them more than 24 hours, so its [policy] keeps it out of the merged score and the offline cache:
 * the screens show it apart, as Yelp's own.
 */
class YelpRatings(http: Http, keys: Keys, private val base: String = "https://api.yelp.com") : KeyedRatings(http, keys) {
    override val info = INFO
    override val symbol = "yelp"
    override val policy = RatingPolicy(mixable = false, maxCacheHours = 24)

    override suspend fun rating(place: Place): Rating? {
        val key = key()
        val url = base.toHttpUrl().newBuilder().addPathSegments("v3/businesses/search")
            .addQueryParameter("latitude", coord(place.location.lat))
            .addQueryParameter("longitude", coord(place.location.lon))
            .addQueryParameter("radius", "200")
            .addQueryParameter("limit", "10")
            .apply { place.name()?.let { addQueryParameter("term", it) } }
            .build()
        val json = getJson(url, mapOf("Authorization" to "Bearer $key"))
        val businesses = json.arr("businesses").mapNotNull { it.asObject() }
        val hit = NameMatch.best(place.name(), place.location, businesses, nameOf = { it.str("name") }, where = { b ->
            b.obj("coordinates")?.let { c -> c.dbl("latitude")?.let { la -> c.dbl("longitude")?.let { lo -> LatLon(la, lo) } } }
        }) ?: return null
        return Rating(
            provider = ID,
            stars = hit.dbl("rating"),
            count = hit.int("review_count") ?: 0,
            priceLevel = hit.str("price")?.count { it == '$' || it == '€' || it == '£' || it == '¥' }?.takeIf { it in 1..4 },
            url = hit.str("url")?.substringBefore('?'),
            attribution = INFO.attribution,
        )
    }

    /** Up to three review excerpts for a Yelp business id, newest first (a second call; Yelp gives excerpts only). */
    suspend fun reviews(businessId: String): List<Review> {
        val key = key()
        val url = base.toHttpUrl().newBuilder().addPathSegments("v3/businesses").addPathSegment(businessId).addPathSegment("reviews")
            .addQueryParameter("limit", "3").addQueryParameter("sort_by", "newest").build()
        return getJson(url, mapOf("Authorization" to "Bearer $key")).arr("reviews").mapNotNull { it.asObject() }.map { r ->
            Review(ID, r.dbl("rating"), r.str("text"), r.obj("user")?.str("name"), parseTime(r.str("time_created")), url = r.str("url"))
        }
    }

    companion object {
        const val ID = "yelp"
        val INFO = ProviderInfo(
            id = ID, name = "Yelp", licence = "Yelp API terms", attribution = "Ratings by Yelp", url = "https://www.yelp.com",
            keyNeed = KeyNeed.REQUIRED,
            note = "Your own key. Yelp's terms forbid mixing its ratings with others and keeping them over 24 hours: shown apart, never offline.",
            hosts = listOf("api.yelp.com"),
        )
    }
}

/**
 * Tripadvisor through Terra (terra.tripadvisor.com/api), which replaced the Content API that closed on 31 August 2026.
 * The user's key; Tripadvisor bills per returned location after the first 1,000 and allows no caching beyond its ids.
 */
class TripAdvisorRatings(http: Http, keys: Keys, private val base: String = "https://terra.tripadvisor.com/api") : KeyedRatings(http, keys) {
    override val info = INFO
    override val symbol = "tripadvisor"
    override val policy = RatingPolicy(maxCacheHours = 0)

    override suspend fun rating(place: Place): Rating? {
        val key = key()
        val url = base.toHttpUrl().newBuilder().addPathSegments("locations/nearby")
            .addQueryParameter("version", "1")
            .addQueryParameter("lat", coord(place.location.lat))
            .addQueryParameter("lon", coord(place.location.lon))
            .addQueryParameter("radius", "0.2")
            .addQueryParameter("unit", "KM")
            .addQueryParameter("size", "10")
            .addQueryParameter("sort", "distance,asc")
            .build()
        val json = getJson(url, mapOf("X-API-Key" to key))
        val locations = json.arr("data").mapNotNull { it.asObject()?.obj("location") }
        val hit = NameMatch.best(place.name(), place.location, locations, nameOf = ::nameOf, where = { l ->
            l.obj("coordinates")?.let { c -> c.dbl("latitude")?.let { la -> c.dbl("longitude")?.let { lo -> LatLon(la, lo) } } }
        }) ?: return null
        val overall = hit.obj("traveler_ratings")?.obj("overall")
        return Rating(
            provider = ID,
            stars = overall?.dbl("rating"),
            count = overall?.int("count") ?: 0,
            priceLevel = when (hit.str("price_level")) {
                "Cheap Eats" -> 1
                "Mid Range" -> 2
                "Fine Dining" -> 4
                else -> null
            },
            url = hit.obj("urls")?.obj("tripadvisor")?.str("main"),
            attribution = INFO.attribution,
        )
    }

    private fun nameOf(l: JsonObject): String? {
        val names = l.arr("names").mapNotNull { it.asObject() }
        return (names.firstOrNull { it.bool("primary") == true } ?: names.firstOrNull())?.str("value")
    }

    companion object {
        const val ID = "tripadvisor"
        val INFO = ProviderInfo(
            id = ID, name = "Tripadvisor", licence = "Tripadvisor terms", attribution = "Ratings by Tripadvisor", url = "https://www.tripadvisor.com",
            keyNeed = KeyNeed.REQUIRED,
            note = "Your own Terra key. Billed per location after the first 1,000; nothing kept offline.",
            hosts = listOf("terra.tripadvisor.com"),
        )
    }
}
