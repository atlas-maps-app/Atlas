package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.geo.PolylineCodec
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.Locale

/** A stop or the start or end of a journey. */
data class TransitStop(
    val name: String,
    val location: LatLon,
    val stopId: String? = null,
    val track: String? = null,
    val arrival: Instant? = null,
    val departure: Instant? = null,
    val scheduledArrival: Instant? = null,
    val scheduledDeparture: Instant? = null,
)

/** One leg: a walk, or a ride on one vehicle from boarding to alighting. */
data class TransitLeg(
    /** WALK, BIKE, TRAM, BUS, SUBWAY, RAIL, REGIONAL_RAIL, HIGHSPEED_RAIL, FERRY … as MOTIS names them. */
    val mode: String,
    val from: TransitStop,
    val to: TransitStop,
    val start: Instant,
    val end: Instant,
    val scheduledStart: Instant?,
    val scheduledEnd: Instant?,
    /** True when the times come from live data. */
    val realtime: Boolean,
    /** The line as passengers know it: "2", "IC 3500". */
    val line: String?,
    val headsign: String?,
    val agency: String?,
    /** The line's colour as "#RRGGBB", when the operator publishes one. */
    val colour: String?,
    val textColour: String?,
    val distanceM: Double?,
    val stops: List<TransitStop>,
    val geometry: List<LatLon>,
    val cancelled: Boolean,
) {
    val transit: Boolean get() = mode != "WALK" && mode != "BIKE" && mode != "CAR"
    val delaySeconds: Long? get() = scheduledStart?.let { java.time.Duration.between(it, start).seconds }
}

/** A door-to-door public transport journey. */
data class Journey(val start: Instant, val end: Instant, val durationSeconds: Long, val transfers: Int, val legs: List<TransitLeg>)

/**
 * Transitous (transitous.org): a community-run, open public transport router over the world's open timetables
 * (GTFS and live feeds), speaking the MOTIS 2 API. Free for open-source, non-commercial apps that send an identifying
 * User-Agent and credit its sources (transitous.org/sources); routing is a heavy endpoint, so Transitous asks apps to
 * get in touch before sending many users. It is therefore off until the app turns it on.
 */
class Transitous(private val http: Http, private val base: String = "https://api.transitous.org") : Provider {
    override val info = INFO

    override fun state() = ProviderState.READY

    /** Journeys from [from] to [to] leaving at [time] (or arriving by it with [arriveBy]), best first; at most [count]. */
    suspend fun plan(from: LatLon, to: LatLon, time: Instant? = null, arriveBy: Boolean = false, count: Int = 5, wheelchair: Boolean = false): List<Journey> {
        val url = base.toHttpUrl().newBuilder().addPathSegments("api/v6/plan")
            .addQueryParameter("fromPlace", "${c(from.lat)},${c(from.lon)}")
            .addQueryParameter("toPlace", "${c(to.lat)},${c(to.lon)}")
            .addQueryParameter("numItineraries", count.coerceIn(1, 20).toString())
            .addQueryParameter("detailedTransfers", "false")
            .apply {
                time?.let { addQueryParameter("time", it.toString()) }
                if (arriveBy) addQueryParameter("arriveBy", "true")
                if (wheelchair) addQueryParameter("pedestrianProfile", "WHEELCHAIR")
            }.build()
        val json = http.execute(http.request(url).build()).requireOk().json().asObject() ?: throw ProviderException(ID, "Transitous answered something unexpected")
        return parse(json)
    }

    companion object {
        const val ID = "transitous"

        val INFO = ProviderInfo(
            id = ID, name = "Transitous", licence = "Open timetables (per source); © OpenStreetMap contributors",
            attribution = "Public transport by Transitous · transitous.org/sources", url = "https://transitous.org",
            onByDefault = false,
            note = "Community-run and free for open-source apps; Atlas should tell Transitous before turning it on for everyone.",
        )

        private fun c(v: Double) = String.format(Locale.ROOT, "%.6f", v)

        private fun time(s: String?): Instant? = s?.let { runCatching { Instant.parse(it) }.getOrNull() }

        private fun colour(s: String?): String? = s?.trim()?.removePrefix("#")?.takeIf { Regex("[0-9A-Fa-f]{6}").matches(it) }?.let { "#" + it.uppercase() }

        /** The itineraries of a MOTIS `plan` answer; the walk-only direct one is added when there is no transit at all. */
        fun parse(json: JsonObject): List<Journey> {
            val journeys = json.arr("itineraries").mapNotNull { it.asObject()?.let(::journey) }
            return journeys.ifEmpty { json.arr("direct").mapNotNull { it.asObject()?.let(::journey) } }
        }

        private fun journey(o: JsonObject): Journey? {
            val legs = o.arr("legs").mapNotNull { it.asObject()?.let(::leg) }
            if (legs.isEmpty()) return null
            val start = time(o.str("startTime")) ?: legs.first().start
            val end = time(o.str("endTime")) ?: legs.last().end
            return Journey(start, end, o.long("duration") ?: java.time.Duration.between(start, end).seconds, o.int("transfers") ?: 0, legs)
        }

        private fun stop(o: JsonObject?): TransitStop? {
            o ?: return null
            val lat = o.dbl("lat") ?: return null
            val lon = o.dbl("lon") ?: return null
            val name = o.str("name").let { if (it == null || it == "START" || it == "END") "" else it }
            return TransitStop(
                name, LatLon(lat, lon), o.str("stopId"), o.str("track"),
                time(o.str("arrival")), time(o.str("departure")), time(o.str("scheduledArrival")), time(o.str("scheduledDeparture")),
            )
        }

        private fun leg(o: JsonObject): TransitLeg? {
            val from = stop(o.obj("from")) ?: return null
            val to = stop(o.obj("to")) ?: return null
            val start = time(o.str("startTime")) ?: return null
            val end = time(o.str("endTime")) ?: return null
            val geometry = o.obj("legGeometry")?.let { g ->
                g.str("points")?.let { PolylineCodec.decodeOrNull(it, g.int("precision") ?: 5) }
            } ?: listOf(from.location, to.location)
            return TransitLeg(
                mode = o.str("mode") ?: "WALK", from = from, to = to, start = start, end = end,
                scheduledStart = time(o.str("scheduledStartTime")), scheduledEnd = time(o.str("scheduledEndTime")),
                realtime = o.bool("realTime") == true,
                line = o.str("displayName") ?: o.str("routeShortName") ?: o.str("tripShortName"),
                headsign = o.str("headsign"), agency = o.str("agencyName"),
                colour = colour(o.str("routeColor")), textColour = colour(o.str("routeTextColor")),
                distanceM = o.dbl("distance"),
                stops = o.arr("intermediateStops").mapNotNull { stop(it.asObject()) },
                geometry = geometry,
                cancelled = o.bool("cancelled") == true,
            )
        }
    }
}
