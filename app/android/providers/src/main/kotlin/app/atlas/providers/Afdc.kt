package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.LocalDate
import java.util.Locale

/**
 * The US Alternative Fuels Data Center (AFDC, run by NLR, formerly NREL): every public charger in the United States and
 * Canada, with networks, connectors per charging unit and power. The data "may be used for any purpose whatsoever".
 * It needs a free key from developer.nlr.gov: Atlas's own ([appKey]) or the user's; "DEMO_KEY" works for a few calls.
 */
class Afdc(
    private val http: Http,
    private val keys: Keys = Keys.NONE,
    private val appKey: String? = null,
    private val base: String = "https://developer.nlr.gov/api/alt-fuel-stations/v1",
) : ChargerProvider {
    override val info = INFO.copy(keyNeed = if (appKey.isNullOrBlank()) KeyNeed.REQUIRED else KeyNeed.OPTIONAL)

    override fun state() = if (key() == null) ProviderState.NEEDS_KEY else ProviderState.READY

    private fun key(): String? = keys.key(ID)?.takeIf { it.isNotBlank() } ?: appKey?.takeIf { it.isNotBlank() }

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> {
        val url = query("nearest.json", filters)
            .addQueryParameter("latitude", c(center.lat)).addQueryParameter("longitude", c(center.lon))
            .addQueryParameter("radius", tagNumber(Math.round(radiusM / METRES_PER_MILE * 100) / 100.0))
            .build()
        return stations(url).filter(filters::accepts).filter { Geo.distance(center, it.location) <= radiusM * 1.02 }
            .sortedBy { Geo.distance(center, it.location) }.take(filters.maxResults)
    }

    /** AFDC's own corridor search: the line as WKT, the corridor in miles (POSTed, as the line can be long). */
    override suspend fun along(points: List<LatLon>, corridorM: Double, filters: ChargerFilters): List<Place> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return near(points[0], corridorM, filters)
        val line = RequestLine.of(points, corridorM / 10)
        val wkt = line.points.joinToString(", ", "LINESTRING(", ")") { "${c(it.lon)} ${c(it.lat)}" }
        val url = query("nearby-route.json", filters).build()
        // Rounded up to the hundredth of a mile, so the corridor asked for is never narrower than the one wanted.
        val miles = Math.ceil((corridorM + line.extraM) / METRES_PER_MILE * 100) / 100.0
        val form = FormBody.Builder().add("route", wkt).add("distance", tagNumber(miles)).build()
        return inCorridor(points, corridorM, stations(url, form).filter(filters::accepts))
    }

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> {
        val own = ids.filter { it.startsWith("$ID:") }
        val out = LinkedHashMap<String, ChargerStatus>()
        for (id in own) {
            val num = id.substringAfter(':').toLongOrNull() ?: continue
            val url = base.toHttpUrl().newBuilder().addPathSegment("$num.json").addQueryParameter("api_key", key() ?: throw NeedsKeyException(ID)).build()
            val res = http.execute(http.request(url).build())
            if (res.code == 404) continue
            val station = res.requireOk().json().asObject()?.obj("alt_fuel_station") ?: continue
            toPlace(station)?.let { p -> ChargerIndex.statusOf(p)?.let { out[id] = it } }
        }
        return out
    }

    private fun query(endpoint: String, filters: ChargerFilters): HttpUrl.Builder {
        val key = key() ?: throw NeedsKeyException(ID)
        return base.toHttpUrl().newBuilder().addPathSegment(endpoint)
            .addQueryParameter("api_key", key)
            .addQueryParameter("fuel_type", "ELEC")
            .addQueryParameter("status", if (filters.includeNotWorking) "E,P,T" else "E")
            .addQueryParameter("access", "public")
            .addQueryParameter("limit", filters.maxResults.coerceIn(1, 200).toString())
            .apply {
                val codes = connectorCodesFor(filters.connectors)
                if (codes.isNotEmpty()) addQueryParameter("ev_connector_type", codes.joinToString(","))
                if (filters.minKw >= 50) addQueryParameter("ev_charging_level", "dc_fast")
            }
    }

    private suspend fun stations(url: HttpUrl, form: FormBody? = null): List<Place> {
        val req = http.request(url).apply { if (form != null) post(form) }.build()
        val res = http.execute(req)
        if (res.code == 401 || res.code == 403) throw ProviderException(ID, "AFDC refused the key (HTTP ${res.code})")
        if (res.code == 429) throw ProviderException(ID, "AFDC's hourly limit for this key is used up")
        val json = res.requireOk().json().asObject() ?: return emptyList()
        return json.arr("fuel_stations").mapNotNull { it.asObject()?.let(::toPlace) }
    }

    private fun c(v: Double) = String.format(Locale.ROOT, "%.6f", v)

    companion object {
        const val ID = "afdc"
        private const val METRES_PER_MILE = 1609.344

        val INFO = ProviderInfo(
            id = ID, name = "AFDC (United States)", licence = "Public data, any purpose",
            attribution = "Charging data: Alternative Fuels Data Center, U.S. Department of Energy",
            url = "https://afdc.energy.gov/stations", keyNeed = KeyNeed.OPTIONAL, onByDefault = true,
            note = "Every public charger in the US and Canada, asked with Atlas's free key from developer.nlr.gov.",
        )

        /** AFDC connector codes that serve [connectors] (OSM socket names). */
        fun connectorCodesFor(connectors: Set<String>): List<String> =
            if (connectors.isEmpty()) emptyList() else Sockets.AFDC.filter { (_, type) -> Sockets.fits(connectors, type) }.keys.sorted()

        /** One AFDC station as a charger place in OSM's terms; null without a position. */
        fun toPlace(s: JsonObject): Place? {
            val id = s.long("id") ?: return null
            val lat = s.dbl("latitude") ?: return null
            val lon = s.dbl("longitude") ?: return null
            val tags = LinkedHashMap<String, String>()
            tags["amenity"] = "charging_station"
            s.str("station_name")?.let { tags["name"] = it.trim() }
            s.str("ev_network")?.takeUnless { it.equals("Non-Networked", true) }?.let { tags["network"] = it }
            tags["access"] = if (s.str("access_code") == "private") "private" else "yes"
            // Charging units say which connector has how many ports at what power; older records list types only.
            val sockets = ArrayList<Socket>()
            for (unit in s.arr("ev_charging_units").mapNotNull { it.asObject() }) {
                for ((code, v) in unit.obj("connectors") ?: continue) {
                    val c = v.asObject() ?: continue
                    val n = c.int("port_count") ?: 0
                    if (n <= 0) continue
                    val type = Sockets.AFDC[code] ?: continue
                    sockets.add(Socket(type, n, c.dbl("power_kw")))
                }
            }
            if (sockets.isEmpty()) {
                val types = s.arr("ev_connector_types").mapNotNull { it.asString()?.let(Sockets.AFDC::get) }
                val dc = s.int("ev_dc_fast_num") ?: 0
                val l2 = s.int("ev_level2_evse_num") ?: 0
                for (t in types) sockets.add(Socket(t, (if (t in Sockets.DC) dc else l2).coerceAtLeast(1), null))
            }
            tags.putAll(Sockets.tags(sockets))
            val ports = listOfNotNull(s.int("ev_dc_fast_num"), s.int("ev_level2_evse_num"), s.int("ev_level1_evse_num")).sum()
            if (ports > 0) tags["capacity"] = ports.toString()
            if (s.str("maximum_vehicle_class") == "HD") tags["hgv"] = "yes"
            s.str("access_days_time")?.let { t -> if (t.contains("24 hours daily", true)) tags["opening_hours"] = "24/7" }
            s.str("ev_pricing")?.let { p ->
                tags["charge"] = p
                tags["fee"] = if (p.trim().equals("free", true)) "no" else "yes"
            }
            s.str("station_phone")?.let { tags["phone"] = it }
            s.str("open_date")?.let { tags["start_date"] = it }
            s.str("ev_network_web")?.let { tags["website"] = it }
            AddressTags.put(tags, s.str("street_address"), null, s.str("zip"), s.str("city"), s.str("country"))
            val extra = LinkedHashMap<String, String>()
            extra["charger:id"] = chargerId(ID, id.toString())
            extra["attribution"] = INFO.attribution
            extra["licence"] = INFO.licence
            extra["url"] = "https://afdc.energy.gov/stations#/station/$id"
            s.str("date_last_confirmed")?.let { extra["verified"] = it }
            val state = when (s.str("status_code")) {
                "E" -> ChargerStatus.State.OPERATIONAL
                "T" -> ChargerStatus.State.OUT_OF_ORDER
                "P" -> ChargerStatus.State.PLANNED
                else -> ChargerStatus.State.UNKNOWN
            }
            s.str("expected_date")?.let { d -> if (state == ChargerStatus.State.PLANNED && runCatching { LocalDate.parse(d) }.isSuccess) tags["opening_date"] = d }
            extra.putAll(ChargerStatus(extra["charger:id"]!!, state, total = ports.takeIf { it > 0 }).toExtra())
            return Place(null, LatLon(lat, lon), tags, source = ID, extra = extra)
        }
    }
}
