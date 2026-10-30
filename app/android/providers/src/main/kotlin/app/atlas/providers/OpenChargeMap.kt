package app.atlas.providers

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.geo.PolylineCodec
import app.atlas.model.Place
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import java.util.Locale

/**
 * Open Charge Map (api.openchargemap.io/v3): the open charger registry, CC BY 4.0 for its own contributors' data.
 *
 * OCM answers only with a key (HTTP 403 without one). Atlas registers its own free app key ([appKey], from the build)
 * and the user may paste a personal key to spread the quota; with neither, the provider reports NEEDS_KEY.
 * Only data providers whose licence allows Atlas's use are kept (OCM's own, NREL/AFDC, the UK registry, NOBIL,
 * Bundesnetzagentur, data.gouv.fr and the CC0 operators); non-commercial ones such as Oplaadpalen.nl are dropped.
 * Each place carries its provider's credit in `extra["attribution"]`, which the place screen must show. Never write OCM
 * data back to OpenStreetMap.
 */
class OpenChargeMap(
    private val http: Http,
    private val keys: Keys = Keys.NONE,
    private val appKey: String? = null,
    private val base: String = "https://api.openchargemap.io/v3",
) : ChargerProvider {
    override val info = if (appKey.isNullOrBlank()) INFO else INFO.copy(keyNeed = KeyNeed.OPTIONAL, onByDefault = true)

    override fun state() = if (key() == null) ProviderState.NEEDS_KEY else ProviderState.READY

    private fun key(): String? = keys.key(ID)?.takeIf { it.isNotBlank() } ?: appKey?.takeIf { it.isNotBlank() }

    override suspend fun near(center: LatLon, radiusM: Double, filters: ChargerFilters): List<Place> {
        val url = query(filters).addQueryParameter("latitude", c(center.lat)).addQueryParameter("longitude", c(center.lon))
            .addQueryParameter("distance", tagNumber(radiusM / 1000.0)).addQueryParameter("distanceunit", "km").build()
        return fetch(url).filter(filters::accepts).filter { Geo.distance(center, it.location) <= radiusM * 1.05 }
            .sortedBy { Geo.distance(center, it.location) }.take(filters.maxResults)
    }

    /**
     * A real corridor search: OCM takes the line as an encoded polyline and a distance. The line is simplified to a
     * tenth of the corridor (and the distance widened to match), and a route still too long for one request line is
     * asked in consecutive pieces; what comes back is held to the corridor around the original line.
     */
    override suspend fun along(points: List<LatLon>, corridorM: Double, filters: ChargerFilters): List<Place> {
        if (points.isEmpty()) return emptyList()
        val line = RequestLine.of(points, corridorM / 10)
        val found = ArrayList<Place>()
        for (piece in pieces(line.points)) {
            val url = query(filters).addQueryParameter("polyline", PolylineCodec.encode(piece))
                .addQueryParameter("distance", tagNumber((corridorM + line.extraM) / 1000.0)).addQueryParameter("distanceunit", "km").build()
            fetch(url).filterTo(found, filters::accepts)
        }
        return inCorridor(points, corridorM, found)
    }

    override suspend fun status(ids: List<String>): Map<String, ChargerStatus> {
        val own = ids.filter { it.startsWith("$ID:") }.mapNotNull { it.substringAfter(':').toLongOrNull() }
        if (own.isEmpty()) return emptyMap()
        return own.chunked(100).flatMap { chunk ->
            val url = base("poi").addQueryParameter("chargepointid", chunk.joinToString(",")).addQueryParameter("maxresults", chunk.size.toString()).build()
            pois(url).mapNotNull(::statusOf)
        }.associateBy { it.id }
    }

    private fun base(path: String): HttpUrl.Builder = base.toHttpUrl().newBuilder().addPathSegment(path)
        .addQueryParameter("output", "json")
        .addQueryParameter("compact", "true")
        .addQueryParameter("verbose", "false")
        .addQueryParameter("client", "atlas")

    private fun query(filters: ChargerFilters): HttpUrl.Builder = base("poi")
        .addQueryParameter("maxresults", filters.maxResults.coerceIn(1, 5000).toString())
        .addQueryParameter("opendata", "true")
        .apply {
            val types = connectionTypesFor(filters.connectors)
            if (types.isNotEmpty()) addQueryParameter("connectiontypeid", types.joinToString(","))
            if (filters.minKw > 0) addQueryParameter("minpowerkw", tagNumber(filters.minKw))
        }

    private suspend fun fetch(url: HttpUrl): List<Place> = pois(url).mapNotNull(::toPlace)

    private suspend fun pois(url: HttpUrl): List<JsonObject> {
        val key = key() ?: throw NeedsKeyException(ID)
        val res = http.execute(http.request(url).header("X-API-Key", key).build())
        if (res.code == 403 || res.code == 401) throw ProviderException(ID, "Open Charge Map refused the key (HTTP ${res.code}): ${res.text.take(120)}")
        return (res.requireOk().json() as? JsonArray)?.mapNotNull { it.asObject() } ?: emptyList()
    }

    /**
     * The centre of a nearby search, to about 100 m: the search reaches kilometres, and the phone keeps what lies within
     * the radius of the exact centre. Until 30 October it went to the centimetre.
     */
    private fun c(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    companion object {
        const val ID = "ocm"
        val INFO = ProviderInfo(
            id = ID, name = "Open Charge Map", licence = "CC BY 4.0", attribution = "Charging data from Open Charge Map and its data providers",
            url = "https://openchargemap.org", keyNeed = KeyNeed.REQUIRED,
            note = "Open data, asked only with a key: your own free one from openchargemap.org, as Atlas builds in none.",
            hosts = listOf("api.openchargemap.io"),
        )

        /**
         * Data providers whose licence allows Atlas's use, with the credit and licence to show (OCM reference data,
         * April 2026). Left out: Oplaadpalen.nl (CC BY-NC-SA, non-commercial), and providers with no licence at all.
         */
        val OPEN_PROVIDERS: Map<Int, Pair<String, String>> = mapOf(
            1 to ("Open Charge Map contributors" to "CC BY 4.0"),
            2 to ("NLR / afdc.energy.gov" to "public data"),
            7 to ("Mobie.pt" to "public data"),
            18 to ("UK National Chargepoint Registry" to "OGL v2.0"),
            19 to ("NOBIL" to "CC BY 4.0"),
            24 to ("AddÉnergie" to "CC BY 3.0"),
            25 to ("ICAEN" to "public data"),
            28 to ("data.gouv.fr" to "Licence Ouverte"),
            29 to ("Bundesnetzagentur.de" to "CC BY 4.0"),
            30 to ("EVIO" to "CC BY"),
            31 to ("SITRONICS" to "CC0"),
            32 to ("Lithuanian Road Administration" to "CC BY 4.0"),
            33 to ("Gaia Green Tech" to "CC0"),
            34 to ("Toger.co" to "CC0"),
            35 to ("Electric Era" to "CC0"),
            36 to ("ITCharge" to "CC0"),
            37 to ("Voltrelli" to "CC0"),
            38 to ("PowerGo" to "CC0"),
            39 to ("Punkt-E" to "CC0"),
            40 to ("Easy Volt" to "CC0"),
            41 to ("ChargeSini" to "CC0"),
            42 to ("Otopriz" to "CC0"),
            43 to ("EV24" to "CC0"),
            44 to ("Zepto.pl" to "CC0"),
            45 to ("Greems Technologies" to "CC0"),
            46 to ("Voltspot" to "CC0"),
            47 to ("Stuart Energy" to "CC0"),
            48 to ("QRSOLVE" to "CC0"),
            50 to ("Techno Fusion" to "CC0"),
            51 to ("UGV Chargers" to "CC0"),
            52 to ("Floading energy infra" to "CC0"),
            53 to ("NordOCPI" to "CC0"),
            54 to ("PowerGo" to "CC0"),
            55 to ("Asynio" to "CC0"),
            56 to ("eplvs" to "CC0"),
            57 to ("YellotMob" to "CC0"),
            59 to ("Schouten Energy" to "CC BY"),
            60 to ("Tritan EV Dock" to "CC0"),
        )

        private val operators: Map<Int, String> by lazy {
            val stream = OpenChargeMap::class.java.getResourceAsStream("/app/atlas/providers/ocm-operators.tsv") ?: return@lazy emptyMap()
            stream.bufferedReader().useLines { lines ->
                lines.mapNotNull { l -> l.split('\t').takeIf { it.size >= 2 }?.let { (id, name) -> id.toIntOrNull()?.let { it to name } } }.toMap()
            }
        }

        /** The operator's name for an OCM operator id; null for the placeholder operators. */
        fun operator(id: Int?): String? = id?.let { operators[it] }?.takeUnless { it.startsWith("(") }

        /** The longest encoded polyline one request carries: servers refuse request lines over 8 KB. */
        const val MAX_POLYLINE = 4_000

        /** [line] cut into consecutive pieces, each sharing its last point with the next, that encode within [max] characters. */
        internal fun pieces(line: List<LatLon>, max: Int = MAX_POLYLINE): List<List<LatLon>> {
            if (line.size <= 2 || PolylineCodec.encode(line).length <= max) return listOf(line)
            val mid = line.size / 2
            return pieces(line.subList(0, mid + 1), max) + pieces(line.subList(mid, line.size), max)
        }

        /** OCM connection type ids that serve [connectors] (OSM socket names), including tethered and compatible ones. */
        fun connectionTypesFor(connectors: Set<String>): List<Int> =
            if (connectors.isEmpty()) emptyList() else Sockets.OCM.filter { (_, type) -> Sockets.fits(connectors, type) }.keys.sorted()

        fun stateOf(statusTypeId: Int?): ChargerStatus.State = when (statusTypeId) {
            10 -> ChargerStatus.State.AVAILABLE
            20 -> ChargerStatus.State.OCCUPIED
            30, 100 -> ChargerStatus.State.OUT_OF_ORDER
            50, 75 -> ChargerStatus.State.OPERATIONAL
            150 -> ChargerStatus.State.PLANNED
            200, 210 -> ChargerStatus.State.REMOVED
            else -> ChargerStatus.State.UNKNOWN
        }

        /** One OCM POI (compact form) as a charger place in OSM's terms; null for unlicensed data or no position. */
        fun toPlace(poi: JsonObject): Place? {
            val id = poi.long("ID") ?: return null
            val provider = OPEN_PROVIDERS[poi.int("DataProviderID") ?: 1] ?: return null
            val a = poi.obj("AddressInfo") ?: return null
            val lat = a.dbl("Latitude") ?: return null
            val lon = a.dbl("Longitude") ?: return null
            val tags = LinkedHashMap<String, String>()
            tags["amenity"] = "charging_station"
            a.str("Title")?.let { tags["name"] = it.trim() }
            operator(poi.int("OperatorID"))?.let { tags["operator"] = it }
            when (poi.int("UsageTypeID")) {
                1, 7 -> tags["access"] = "yes"
                4 -> { tags["access"] = "yes"; tags["authentication:membership_card"] = "yes" }
                5 -> { tags["access"] = "yes"; tags["fee"] = "yes" }
                6 -> tags["access"] = "customers"
                2, 3 -> tags["access"] = "private"
            }
            poi.str("UsageCost")?.trim()?.takeIf { it.isNotEmpty() }?.let { cost ->
                tags["charge"] = cost
                // "Free" with a price beside it is free parking, not free charging.
                if (FREE.containsMatchIn(cost) && !PRICE.containsMatchIn(cost)) tags["fee"] = "no" else tags.putIfAbsent("fee", "yes")
            }
            val sockets = poi.arr("Connections").mapNotNull { it.asObject() }.mapNotNull { c ->
                val type = Sockets.OCM[c.int("ConnectionTypeID") ?: 0] ?: return@mapNotNull null
                val kw = c.dbl("PowerKW")?.takeIf { it > 0 } ?: run {
                    val amps = c.dbl("Amps")
                    val volts = c.dbl("Voltage")
                    if (amps == null || volts == null) null
                    // Three-phase (CurrentTypeID 20) records give the line voltage (400 V) or the phase voltage (230 V).
                    else if (c.int("CurrentTypeID") == 20) amps * volts * (if (volts < 300) 3.0 else Math.sqrt(3.0)) / 1000.0
                    else amps * volts / 1000.0
                }
                Socket(type, (c.int("Quantity") ?: 1).coerceAtLeast(1), kw)
            }
            tags.putAll(Sockets.tags(sockets))
            (poi.int("NumberOfPoints") ?: sockets.sumOf { it.count }.takeIf { it > 0 })?.let { tags["capacity"] = it.toString() }
            AddressTags.put(tags, a.str("AddressLine1"), null, a.str("Postcode"), a.str("Town"), null)
            a.str("RelatedURL")?.let { tags["website"] = it }
            val extra = LinkedHashMap<String, String>()
            extra["charger:id"] = chargerId(ID, id.toString())
            extra["attribution"] = "Open Charge Map · ${provider.first} (${provider.second})"
            extra["licence"] = provider.second
            extra["url"] = "https://map.openchargemap.io/?id=$id"
            poi.str("DateLastVerified")?.let { extra["verified"] = it }
            statusOf(poi)?.let { extra.putAll(it.toExtra()) }
            return Place(null, LatLon(lat, lon), tags, source = ID, extra = extra)
        }

        /**
         * The status OCM records for a POI. Live counts come only from automated connection statuses (10 available,
         * 20 in use); without them the POI's own status stands, unless every connection with a status is broken.
         * One broken connector among working ones leaves the charger working.
         */
        fun statusOf(poi: JsonObject): ChargerStatus? {
            val id = poi.long("ID") ?: return null
            val conns = poi.arr("Connections").mapNotNull { it.asObject() }
            val automated = conns.any { it.int("StatusTypeID") in AUTOMATED }
            val counted = if (automated) conns.filter { it.int("StatusTypeID") in AUTOMATED || it.int("StatusTypeID") in BROKEN } else emptyList()
            val perSocket = counted.groupBy { Sockets.OCM[it.int("ConnectionTypeID") ?: 0] ?: "unknown" }.mapValues { (_, cs) ->
                cs.filter { it.int("StatusTypeID") == 10 }.sumOf { quantity(it) } to cs.sumOf { quantity(it) }
            }
            val available = if (automated) counted.filter { it.int("StatusTypeID") == 10 }.sumOf { quantity(it) } else null
            val total = poi.int("NumberOfPoints") ?: conns.sumOf { quantity(it) }.takeIf { it > 0 }
            val updated = poi.str("DateLastStatusUpdate")?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val withStatus = conns.mapNotNull { it.int("StatusTypeID") }.filter { it != 0 }
            val state = when {
                automated && available!! > 0 -> ChargerStatus.State.AVAILABLE
                automated -> ChargerStatus.State.OCCUPIED
                withStatus.isNotEmpty() && withStatus.all { it in BROKEN } -> ChargerStatus.State.OUT_OF_ORDER
                else -> stateOf(poi.int("StatusTypeID")).let { poiState ->
                    // A POI without a status of its own is as working as its working connections say.
                    if (poiState == ChargerStatus.State.UNKNOWN && withStatus.any { it == 50 || it == 75 }) ChargerStatus.State.OPERATIONAL else poiState
                }
            }
            return ChargerStatus(chargerId(ID, id.toString()), state, available, total, updated, perSocket.filterKeys { it != "unknown" })
        }

        private val FREE = Regex("""(?i)\b(free|gratis|kostenlos|gratuit)\b""")
        private val PRICE = Regex("""(?i)[€£${'$'}]\s*\d|\d\s*(€|eur\b|usd\b|gbp\b|ct\b|cents?\b|p\b)|(/|\bper\b)\s*(kwh|min|minute|h|hour|uur|stunde)\b""")

        private fun quantity(c: JsonObject) = (c.int("Quantity") ?: 1).coerceAtLeast(1)

        /** Statuses OCM's automated feeds set per connection: available, in use. */
        private val AUTOMATED = setOf(10, 20)

        /** Temporarily unavailable, not operational. */
        private val BROKEN = setOf(30, 100)
    }
}
