package app.atlas.ui.mine.pump

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.Http
import app.atlas.providers.Keys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Locale
import kotlin.math.roundToInt

/** The fuels of the pump's buttons, with OSM's `fuel:*` key, the nozzle colour of the board and the price key of Tankerkönig. */
enum class Fuel(val label: String, val osm: String, val colour: Long, val tanker: String?) {
    E10("E10", "e10", 0xFF2E9E4A, "e10"),
    S95("95", "octane_95", 0xFF2E9E4A, "e5"),
    S98("98", "octane_98", 0xFF1F5FA8, null),
    DIESEL("Diesel", "diesel", 0xFF1B1F24, "diesel"),
    LPG("LPG", "lpg", 0xFFF2A71B, null),
    ADBLUE("AdBlue", "adblue", 0xFF2E7BC4, null);

    companion object {
        fun of(id: String?): Fuel? = entries.firstOrNull { it.name.equals(id, true) }
    }
}

/** A petrol station as the Pump screen shows it. */
data class Station(
    val place: Place,
    val name: String,
    val brand: String?,
    val street: String?,
    val fuels: Set<Fuel>,
    /** Prices per litre in the local currency, from a source that has them (Tankerkönig in Germany); empty elsewhere. */
    val prices: Map<Fuel, Double>,
    val amenities: List<Amenity>,
    val distanceM: Double?,
    val open24h: Boolean,
) {
    enum class Amenity(val symbol: String) { SHOP("place.shop-front"), CAR_WASH("place.car-wash"), AIR("weather.wind"), TOILETS("need.toilet.4"), WATER("place.tap"), OPEN_24H("ui.clock.2") }

    val key: String get() = place.key
    fun sells(f: Fuel) = f in fuels
    fun price(f: Fuel): Double? = prices[f]
}

/** What the Pump screen works out: stations from places, the choice for a fuel, the figures of the display. */
object PumpModel {
    fun station(place: Place, from: LatLon?, lang: String? = null, prices: Map<Fuel, Double> = emptyMap()): Station {
        val t = place.tags
        val fuels = Fuel.entries.filter { t["fuel:${it.osm}"] == "yes" || (it == Fuel.S95 && t["fuel:octane_95"] == "yes") }.toSet() + prices.keys
        val amenities = buildList {
            if (t["shop"] != null || t["convenience"] == "yes") add(Station.Amenity.SHOP)
            if (t["car_wash"] == "yes" || t["fuel:car_wash"] == "yes") add(Station.Amenity.CAR_WASH)
            if (t["compressed_air"] == "yes") add(Station.Amenity.AIR)
            if (t["toilets"] == "yes") add(Station.Amenity.TOILETS)
            if (t["drinking_water"] == "yes") add(Station.Amenity.WATER)
            if (t["opening_hours"] == "24/7") add(Station.Amenity.OPEN_24H)
        }
        return Station(
            place = place,
            name = place.name(lang) ?: t["brand"] ?: t["operator"] ?: "",
            brand = t["brand"] ?: t["operator"],
            street = t["addr:street"]?.let { s -> t["addr:housenumber"]?.let { "$s $it" } ?: s },
            fuels = fuels,
            prices = prices,
            amenities = amenities,
            distanceM = from?.let { Geo.distance(it, place.location) },
            open24h = t["opening_hours"] == "24/7",
        )
    }

    /** The stations that sell [fuel], with the cheapest first when prices are known, else the nearest first. */
    fun ranked(stations: List<Station>, fuel: Fuel): List<Station> {
        val sells = stations.filter { it.sells(fuel) }
        return if (sells.any { it.price(fuel) != null }) sells.sortedWith(compareBy<Station> { it.price(fuel) ?: Double.MAX_VALUE }.thenBy { it.distanceM ?: Double.MAX_VALUE })
        else sells.sortedBy { it.distanceM ?: Double.MAX_VALUE }
    }

    /** How many of the stations sell each fuel. */
    fun counts(stations: List<Station>): Map<Fuel, Int> = Fuel.entries.associateWith { f -> stations.count { it.sells(f) } }

    /** The tag of a station against the others for [fuel]: "save 2.80" against the average of those with a price, "cheapest", "avg", or null. */
    fun saving(station: Station, all: List<Station>, fuel: Fuel, litres: Double): Pair<Double, Double>? {
        val mine = station.price(fuel) ?: return null
        val others = all.mapNotNull { it.price(fuel) }
        if (others.size < 2) return null
        val avg = others.average()
        return avg to (avg - mine) * litres
    }

    /** The litres to fill: the tank's size less what is in it. */
    fun litresToFill(tankL: Int, level: Float): Double = (tankL * (1f - level.coerceIn(0f, 1f))).toDouble()

    /** The gauge's words: "¼ tank", "½ tank", "¾ tank", "full", "empty". */
    fun tankWord(level: Float): String = when {
        level <= 0.05f -> "empty"
        level < 0.375f -> "¼"
        level < 0.625f -> "½"
        level < 0.9f -> "¾"
        else -> "full"
    }

    /** A price as the pump writes it: "1.67" and the tenth of a cent, 9, raised: ("1.67", "9"). */
    fun lcd(price: Double): Pair<String, String> {
        val s = String.format(Locale.ROOT, "%.3f", price)
        return s.dropLast(1) to s.takeLast(1)
    }

    /** Euros with two decimals. */
    fun money(v: Double) = String.format(Locale.ROOT, "%.2f", v)
}

/**
 * Tankerkönig, Germany's fuel prices (the Markttransparenzstelle's data, CC BY 4.0): the stations within a radius with
 * what E5, E10 and diesel cost now. It needs the person's own key (free at creativecommons.tankerkoenig.de), kept in
 * Data sources & keys; without one it is never asked. No other country has an open price feed Atlas can use.
 */
class Tankerkoenig(private val http: Http, private val keys: Keys, private val base: String = "https://creativecommons.tankerkoenig.de") {
    /** One station of the answer. */
    data class Priced(val id: String, val name: String, val brand: String?, val street: String?, val at: LatLon, val prices: Map<Fuel, Double>, val open: Boolean)

    /** True when a key is set. */
    val ready: Boolean get() = !keys.key(ID).isNullOrBlank()

    suspend fun near(at: LatLon, radiusKm: Double = 5.0): List<Priced> = withContext(Dispatchers.IO) {
        val key = keys.key(ID)?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val res = http.execute(http.request(listUrl(base, at, radiusKm, key)).build())
        if (!res.ok) throw java.io.IOException("Tankerkönig answered ${res.code}")
        parse(res.text)
    }

    companion object {
        const val ID = "tankerkoenig"

        /** Germany, roughly: where a key can help. */
        fun inGermany(p: LatLon): Boolean = p.lat in 47.2..55.1 && p.lon in 5.8..15.1

        /**
         * The stations within [radiusKm] of [at], which is sent to about 100 m: the search reaches kilometres. Until
         * 30 October it went to the metre.
         */
        fun listUrl(base: String, at: LatLon, radiusKm: Double, key: String): String =
            "$base/json/list.php?lat=${String.format(Locale.ROOT, "%.3f", at.lat)}&lng=${String.format(Locale.ROOT, "%.3f", at.lon)}" +
                "&rad=${radiusKm.coerceIn(1.0, 25.0)}&sort=dist&type=all&apikey=$key"

        fun parse(text: String): List<Priced> {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            if ((root["ok"] as? JsonPrimitive)?.booleanOrNull == false) throw java.io.IOException((root["message"] as? JsonPrimitive)?.content ?: "Tankerkönig refused the request")
            val stations = root["stations"] as? JsonArray ?: return emptyList()
            return stations.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                fun d(k: String) = (o[k] as? JsonPrimitive)?.doubleOrNull
                fun s(k: String) = (o[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
                val lat = d("lat") ?: return@mapNotNull null
                val lon = d("lng") ?: return@mapNotNull null
                val prices = buildMap { for (f in Fuel.entries) f.tanker?.let { k -> d(k)?.takeIf { it > 0 }?.let { put(f, it) } } }
                Priced(s("id") ?: return@mapNotNull null, s("name") ?: s("brand") ?: "", s("brand"), s("street")?.let { st -> s("houseNumber")?.let { "$st $it" } ?: st }, LatLon(lat, lon), prices, (o["isOpen"] as? JsonPrimitive)?.booleanOrNull ?: true)
            }
        }

        /** Puts prices on the stations of [places] that lie within [maxM] of a priced one; the priced ones with no match are added as stations. */
        fun merge(places: List<Place>, priced: List<Priced>, from: LatLon?, maxM: Double = 90.0): List<Station> {
            val used = HashSet<String>()
            val out = ArrayList<Station>()
            for (p in places) {
                val hit = priced.filter { it.id !in used }.minByOrNull { Geo.distance(it.at, p.location) }?.takeIf { Geo.distance(it.at, p.location) <= maxM }
                if (hit != null) used += hit.id
                out += PumpModel.station(p, from, null, hit?.prices.orEmpty())
            }
            for (t in priced) if (t.id !in used) {
                val tags = linkedMapOf("amenity" to "fuel", "name" to t.name)
                t.brand?.let { tags["brand"] = it }
                t.street?.let { tags["addr:street"] = it }
                out += PumpModel.station(Place(null, t.at, tags, source = ID), from, null, t.prices)
            }
            return out
        }
    }
}
