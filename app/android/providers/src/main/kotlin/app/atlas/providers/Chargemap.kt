package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Chargemap, honestly: it has no public read API. Chargemap Partners takes data in from operators (OCPI, roaming hubs)
 * and Chargemap Business exports a fleet's own sessions; nothing lets a third party read stations, status or reviews.
 * Scraping it is out of the question.
 *
 * So this adapter is a slot for a partner agreement. Should one ever exist, data would come the way charger data moves
 * between companies: an OCPI 2.2 Locations endpoint ([OcpiLocations]) with a partner token. Until both the endpoint and
 * the token are set, the state is [ProviderState.UNAVAILABLE] and every call throws [ProviderUnavailableException];
 * Open Charge Map, the national registers and OSM are the working defaults.
 */
class Chargemap(
    private val http: Http,
    private val keys: Keys = Keys.NONE,
    /** The partner's OCPI Locations URL, from the agreement; there is no public one. */
    private val partnerEndpoint: String? = null,
) : ChargerProvider {
    override val info = INFO

    private fun client(): OcpiLocations? {
        val endpoint = partnerEndpoint?.takeIf { it.isNotBlank() } ?: return null
        val token = keys.key(ID)?.takeIf { it.isNotBlank() } ?: return null
        return OcpiLocations(http, endpoint, token, ID)
    }

    override fun state(): ProviderState = when {
        partnerEndpoint.isNullOrBlank() -> ProviderState.UNAVAILABLE
        keys.key(ID).isNullOrBlank() -> ProviderState.NEEDS_KEY
        else -> ProviderState.READY
    }

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> =
        (client() ?: throw unavailable()).near(center, radiusM, filters)

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> =
        (client() ?: throw unavailable()).status(ids)

    private fun unavailable(): ProviderException =
        if (partnerEndpoint.isNullOrBlank()) ProviderUnavailableException(ID, REASON) else NeedsKeyException(ID)

    companion object {
        const val ID = "chargemap"
        const val REASON = "Chargemap has no public API; its data needs a partner agreement with Chargemap SAS"

        val INFO = ProviderInfo(
            id = ID, name = "Chargemap", licence = "Chargemap partner terms", attribution = "Charging data from Chargemap",
            url = "https://chargemap.com", keyNeed = KeyNeed.PARTNER, onByDefault = false,
            note = "No public API exists. Atlas can use Chargemap only through a partner agreement, which it does not have.",
        )
    }
}

/**
 * A client for an OCPI 2.2 Locations endpoint (the Credentials-module `Token` authorisation, paged with `offset` and
 * `limit`, the `Link` header pointing at the next page). Charge point operators and roaming hubs speak it; Atlas uses it
 * for partner feeds. Locations become places through [Ocpi.toPlace] with [source] as their id prefix.
 */
class OcpiLocations(
    private val http: Http,
    private val endpoint: String,
    private val token: String,
    private val source: String,
    private val pageSize: Int = 500,
    private val maxPages: Int = 200,
) {
    /** Every location the endpoint publishes, page by page. */
    suspend fun all(): List<Place> {
        val out = ArrayList<Place>()
        var next: String? = endpoint.toHttpUrl().newBuilder().setQueryParameter("offset", "0").setQueryParameter("limit", pageSize.toString()).build().toString()
        var pages = 0
        while (next != null && pages++ < maxPages) {
            val res = http.execute(http.request(next).header("Authorization", "Token $token").build())
            if (res.code == 401 || res.code == 403) throw ProviderException(source, "The OCPI endpoint refused the token (HTTP ${res.code})")
            val json = res.requireOk().json().asObject() ?: break
            val code = json.int("status_code")
            if (code != null && code !in 1000..1999) throw ProviderException(source, "OCPI error $code: ${json.str("status_message").orEmpty()}")
            json.arr("data").mapNotNull { it.asObject() }.mapNotNullTo(out) { Ocpi.toPlace(it, source) }
            next = res.headers["Link"]?.let { link -> Regex("""<([^>]+)>\s*;\s*rel="?next"?""").find(link)?.groupValues?.get(1) }
        }
        return out
    }

    /** Chargers within [radiusM] of [center]: OCPI has no geographic query, so the pages are filtered here. */
    suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> =
        all().filter { Geo.distance(center, it.location) <= radiusM && filters.accepts(it) }
            .sortedBy { Geo.distance(center, it.location) }.take(filters.maxResults)

    /** Live status for the given `charger:id`s, one Location request each (OCPI's `/locations/{id}`). */
    suspend fun status(ids: List<String>): Map<String, ChargerStatus> {
        val out = LinkedHashMap<String, ChargerStatus>()
        for (id in ids.filter { it.startsWith("$source:") }) {
            val url = endpoint.toHttpUrl().newBuilder().addPathSegment(id.substringAfter(':')).build()
            val res = http.execute(http.request(url).header("Authorization", "Token $token").build())
            if (!res.ok) continue
            val data = res.json().asObject()?.get("data") as? JsonObject ?: continue
            Ocpi.toPlace(data, source)?.let { p -> ChargerIndex.statusOf(p)?.let { out[id] = it } }
        }
        return out
    }
}
