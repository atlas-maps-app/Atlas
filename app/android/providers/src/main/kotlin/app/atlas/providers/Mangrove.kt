package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.model.OsmId
import app.atlas.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Locale

/** One Mangrove review as the server returns it. [rating] is Mangrove's 0–100; [stars] is 1–5. */
data class MangroveReview(
    val signature: String,
    val jwt: String?,
    val kid: String?,
    val sub: String,
    val rating: Int?,
    val opinion: String?,
    val iat: Long,
    val metadata: Map<String, String>,
    val images: List<String>,
) {
    val subject: GeoSubject? by lazy { GeoSubject.parse(sub) }
    val stars: Double? get() = rating?.let { Mangrove.stars(it) }
    val time: Instant get() = Instant.ofEpochSecond(iat)

    /** The OSM element the writer named in `metadata.osm_id` ("node/123/4" or "node/123"). */
    val osmId: OsmId? get() = metadata["osm_id"]?.let { OsmId.parse(it) }

    val generated: Boolean get() = metadata["is_generated"] == "true"
    val service: Service? get() = Service.of(metadata["atlas_how"])

    fun toReview(): Review = Review(
        provider = Mangrove.ID,
        stars = stars,
        text = opinion,
        author = metadata["nickname"] ?: metadata["preferred_username"],
        time = time,
        url = "https://mangrove.reviews/list?signature=$signature",
        licence = metadata["license"] ?: Mangrove.LICENCE,
        service = service,
        generated = generated,
        images = images,
    )
}

/** Mangrove's own totals for a subject. [quality] is the 0–100 average it computes; null with no ratings. */
data class MangroveSummary(val sub: String, val quality: Double?, val count: Int, val opinionCount: Int, val positiveCount: Int, val confirmedCount: Int)

/**
 * Mangrove reviews (mangrove.reviews): open, CC BY 4.0, no key. Reviews are signed by their writers and tied to a
 * place by coordinates and name; MapComplete and CoMaps read and write the same pool.
 *
 * Reading: by subject (the server matches name and distance itself), by bounding box for a whole viewport, and Mangrove's
 * own per-subject totals singly or in batches. Writing is [MangroveWriter]'s job.
 */
class Mangrove(private val http: Http, private val base: String = "https://api.mangrove.reviews", private val now: () -> Instant = Instant::now) : RatingProvider {
    override val info = INFO
    override val symbol = "mangrove"

    override fun state() = ProviderState.READY

    /** Reviews of the subject [sub], newest first. */
    suspend fun reviews(sub: String, limit: Int = 200): List<MangroveReview> {
        val url = base.toHttpUrl().newBuilder().addPathSegment("reviews")
            .addQueryParameter("sub", sub)
            .addQueryParameter("limit", limit.coerceIn(1, 3500).toString())
            .build()
        return parseReviews(http.execute(http.request(url).build()).requireOk().json().asObject()).sortedByDescending { it.iat }
    }

    /** Reviews of [place] by its geo subject; falls back to the reviews near it that name its OSM id when it has no name. */
    suspend fun reviewsFor(place: Place, uncertainty: Int = GeoSubject.UNCERTAINTY): List<MangroveReview> {
        val subject = GeoSubject.of(place, uncertainty)
        val found = if (subject != null) reviews(subject.toString()) else emptyList()
        if (found.isNotEmpty() || place.id == null) return found
        return near(BBox.around(place.location, uncertainty * 2.0)).filter { it.osmId == place.id }
    }

    /** Every review in [box]: one call for a whole viewport. */
    suspend fun near(box: BBox): List<MangroveReview> {
        val url = base.toHttpUrl().newBuilder().addPathSegment("geo")
            .addQueryParameter("xmin", c(box.west)).addQueryParameter("ymin", c(box.south))
            .addQueryParameter("xmax", c(box.east)).addQueryParameter("ymax", c(box.north))
            .build()
        return parseReviews(http.execute(http.request(url).build()).requireOk().json().asObject())
    }

    /** Mangrove's totals for one subject. */
    suspend fun summary(sub: String): MangroveSummary? {
        val url = base.toHttpUrl().newBuilder().addPathSegment("subject").addPathSegment(sub).build()
        return parseSummary(http.execute(http.request(url).build()).requireOk().json().asObject() ?: return null)
    }

    /** Mangrove's totals for many subjects in one call. */
    suspend fun summaries(subs: Collection<String>): Map<String, MangroveSummary> {
        if (subs.isEmpty()) return emptyMap()
        val body = buildJsonObject { put("subs", JsonArray(subs.map { JsonPrimitive(it) })) }.toString()
        val request = http.request("$base/batch").post(body.toRequestBody("application/json".toMediaType())).build()
        val subjects = http.execute(request).requireOk().json().asObject()?.obj("subjects") ?: return emptyMap()
        return subjects.mapNotNull { (k, v) -> v.asObject()?.let(::parseSummary)?.let { k to it } }.toMap()
    }

    override suspend fun rating(place: Place): Rating? {
        val reviews = reviewsFor(place)
        return if (reviews.isEmpty()) null else ratingOf(reviews, place, now())
    }

    companion object {
        const val ID = "mangrove"
        const val LICENCE = "CC-BY-4.0"
        val INFO = ProviderInfo(
            id = ID,
            name = "Mangrove",
            licence = "CC BY 4.0",
            attribution = "Reviews from Mangrove.reviews, CC BY 4.0",
            url = "https://mangrove.reviews",
            note = "Reviews read and written in one open pool, signed by their writers; no account.",
        )

        /** Mangrove's 0–100 as 1–5 stars (0, 25, 50, 75, 100 are 1 to 5 stars). */
        fun stars(rating: Int): Double = 1.0 + rating.coerceIn(0, 100) / 25.0

        /** 1–5 stars as Mangrove's 0–100. */
        fun ratingOf(stars: Double): Int {
            require(stars in 1.0..5.0) { "Stars go from 1 to 5" }
            return Math.round((stars - 1) * 25).toInt()
        }

        private fun c(v: Double) = String.format(Locale.ROOT, "%.6f", v)

        /**
         * The rating for [place] from its [reviews]: the average of the human-written ones, the newest five as `recent`,
         * the trend at [now], and separate scores for eaten there and delivered. Machine-written reviews never count, as
         * [Review.generated] promises: a place with only those has no stars. One reviewer is one voice: when the same
         * key reviewed the place several times (edits, or an importer replaying someone's history), only their newest
         * review counts towards the score and the trend.
         */
        fun ratingOf(reviews: List<MangroveReview>, place: Place, now: Instant): Rating {
            val voices = reviews.groupBy { it.kid ?: it.signature }.values.map { same -> same.maxBy { it.iat } }.map { it.toReview() }
            val all = reviews.map { it.toReview() }
            val human = voices.filter { !it.generated }
            val scored = human.mapNotNull { it.stars }
            val byService = human.filter { it.service != null && it.stars != null }.groupBy { it.service!! }
                .mapValues { (_, rs) -> ServiceRating(rs.map { it.stars!! }.average(), rs.size) }
            return Rating(
                provider = ID,
                stars = if (scored.isEmpty()) null else scored.average(),
                count = scored.size,
                url = GeoSubject.of(place)?.let { "https://mangrove.reviews/list?sub=" + GeoSubject.encodeComponent(it.toString()) },
                recent = all.filter { !it.generated }.sortedByDescending { it.time }.take(5),
                trend = Trends.fromReviews(voices, now),
                byService = byService,
                attribution = INFO.attribution,
            )
        }

        /**
         * Assigns reviews from a viewport ([near]) to [places]: by the OSM id the writer recorded first, then by Mangrove's
         * own rule (same name, within the uncertainties). Returns the reviews per place key.
         */
        fun match(reviews: List<MangroveReview>, places: List<Place>): Map<String, List<MangroveReview>> {
            val byId = places.filter { it.id != null }.associateBy { it.id!! }
            val out = HashMap<String, MutableList<MangroveReview>>()
            for (r in reviews) {
                val target = r.osmId?.let { byId[it] } ?: r.subject?.let { s ->
                    places.filter { p -> p.name() != null && Geo.distance(p.location, s.at) <= s.uncertainty + GeoSubject.UNCERTAINTY }
                        .firstOrNull { p -> GeoSubject.of(p)?.sameAs(s) == true }
                }
                if (target != null) out.getOrPut(target.key) { ArrayList() }.add(r)
            }
            return out
        }

        internal fun parseReviews(json: JsonObject?): List<MangroveReview> = json?.arr("reviews")?.mapNotNull { it.asObject()?.let(::parseReview) } ?: emptyList()

        internal fun parseReview(o: JsonObject): MangroveReview? {
            val payload = o.obj("payload") ?: return null
            val sub = payload.str("sub") ?: return null
            val meta = payload.obj("metadata")?.mapNotNull { (k, v) -> v.asString()?.let { k to it } }?.toMap() ?: emptyMap()
            return MangroveReview(
                signature = o.str("signature") ?: return null,
                jwt = o.str("jwt"),
                kid = o.str("kid"),
                sub = sub,
                rating = payload.int("rating"),
                opinion = payload.str("opinion"),
                iat = payload.long("iat") ?: 0L,
                metadata = meta,
                images = payload.arr("images").mapNotNull { it.asObject()?.str("src") },
            )
        }

        internal fun parseSummary(o: JsonObject): MangroveSummary? {
            val sub = o.str("sub") ?: return null
            // The server reports quality as the 0–100 average times 100: reviews of 100 and 72 arrive as 8600.
            val q = o.dbl("quality")?.let { it / 100.0 }
            return MangroveSummary(sub, q, o.int("count") ?: 0, o.int("opinion_count") ?: 0, o.int("positive_count") ?: 0, o.int("confirmed_count") ?: 0)
        }
    }
}
