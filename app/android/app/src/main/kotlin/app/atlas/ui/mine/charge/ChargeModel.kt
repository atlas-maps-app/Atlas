package app.atlas.ui.mine.charge

import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.providers.EvModel
import app.atlas.providers.Socket
import app.atlas.providers.Sockets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The power bands the Plugs board colours its markers by: AC up to 22 kW blue, 50 kW green, 150 kW orange, 300 kW and more purple. */
enum class PowerBand(val colour: Long, val label: String) {
    AC22(0xFF2E7BFF, "AC 22"), KW50(0xFF1E9E5A, "50"), KW150(0xFFF28C28, "150"), KW300(0xFF8E3CF7, "300+"), UNKNOWN(0xFF8A8F98, "?");

    companion object {
        fun of(kw: Double?): PowerBand = when {
            kw == null || !kw.isFinite() || kw <= 0 -> UNKNOWN
            kw <= 22.5 -> AC22
            kw <= 100 -> KW50
            kw < 250 -> KW150
            else -> KW300
        }
    }
}

/** A charging site as the screens show it: the place with its power, sockets, live state and price read out of its tags. */
data class Charger(
    val place: Place,
    val name: String,
    val operator: String?,
    /** The highest power at the site in kW, when its tags say. */
    val kw: Double?,
    val sockets: List<Socket>,
    /** Charge points of the site, when mapped. */
    val points: Int?,
    /** Points free now and in all, when a live source says (Open Charge Map, NDW); null when nobody does. */
    val free: Int?,
    val live: Boolean,
    val pricePerKwh: Double?,
    val currency: String?,
    val free0: Boolean,
    val distanceM: Double?,
    /** Plug and charge (the car authenticates itself) is mapped. */
    val plugAndCharge: Boolean,
) {
    val key: String get() = place.key
    val band: PowerBand get() = PowerBand.of(kw)
    val busy: Boolean get() = live && free == 0

    /** The sockets' OSM names, for the filters. */
    val types: Set<String> get() = sockets.map { it.type }.toSet()
}

/** What a charging screen works out: chargers from places, filters, the sheet's summary, the power mix and the time to charge. */
object ChargeModel {
    /**
     * The plugs of the connector tiles: label, the OSM socket names it stands for, the board symbol of its tile ([art]) and
     * of its face on the car's chips and the station's sockets ([face]).
     */
    class Plug(val id: String, val label: String, val types: Set<String>, val art: String, val face: String)

    val PLUGS = listOf(
        Plug("ccs2", "CCS2", setOf("type2_combo"), "plug.ccs2.2", "plug.ccs2"),
        Plug("type2", "Type 2", setOf("type2", "type2_cable"), "plug.type2.2", "plug.type2"),
        Plug("chademo", "CHAdeMO", setOf("chademo"), "plug.chademo", "plug.chademo"),
        Plug("schuko", "Schuko", setOf("schuko"), "plug.schuko", "plug.schuko"),
    )

    /** The minimum power steps of the "≥ kW" tile. */
    val MIN_KW_STEPS = listOf(0, 22, 50, 100, 150)

    fun of(place: Place, from: LatLon?, lang: String? = null): Charger {
        val sockets = Sockets.of(place.tags)
        val kw = Sockets.maxKw(place.tags)
        val avail = place.extra["status:available"]?.toIntOrNull()
        val total = place.extra["status:total"]?.toIntOrNull()
        val live = avail != null
        val price = price(place.tags)
        return Charger(
            place = place,
            name = place.name(lang) ?: place.tags["operator"] ?: place.tags["network"] ?: "",
            operator = place.tags["operator"] ?: place.tags["network"] ?: place.tags["brand"],
            kw = kw,
            sockets = sockets,
            points = total ?: place.tags["capacity"]?.toIntOrNull() ?: sockets.sumOf { it.count }.takeIf { it > 0 },
            free = avail,
            live = live,
            pricePerKwh = price?.first,
            currency = price?.second,
            free0 = place.tags["fee"] == "no",
            distanceM = from?.let { Geo.distance(it, place.location) },
            plugAndCharge = place.tags.keys.any { it.startsWith("authentication:") && place.tags[it] == "yes" && it.contains("plug") } ||
                place.tags["plug_and_charge"] == "yes",
        )
    }

    /** The price per kWh in the site's `charge` tag: "0.49 EUR/kWh", "€0,49/kWh", "0.30 EUR/kWh;1.00 EUR/session". Null when there is none per kWh. */
    fun price(tags: Map<String, String>): Pair<Double, String>? {
        val raw = tags["charge"] ?: return null
        for (part in raw.split(';')) {
            if (!part.contains("kwh", true)) continue
            val m = Regex("""([€$£]|[A-Za-z]{3})?\s*([0-9]+(?:[.,][0-9]+)?)\s*([€$£]|[A-Za-z]{3})?\s*/?\s*kwh""", RegexOption.IGNORE_CASE).find(part) ?: continue
            val value = m.groupValues[2].replace(',', '.').toDoubleOrNull() ?: continue
            val cur = (m.groupValues[1].ifEmpty { m.groupValues[3] }).ifEmpty { "EUR" }.let { when (it) { "€" -> "EUR"; "$" -> "USD"; "£" -> "GBP"; else -> it.uppercase() } }
            return value to cur
        }
        return null
    }

    /** The chargers that take one of [ports] (any when empty) at [minKw] or more (0: any), and are free when [availableOnly] and live. */
    fun filter(all: List<Charger>, ports: Set<String>, minKw: Int, availableOnly: Boolean): List<Charger> = all.filter { c ->
        (ports.isEmpty() || c.sockets.isEmpty() || c.types.any { it in ports }) &&
            (minKw == 0 || (c.kw ?: 0.0) >= minKw) &&
            (!availableOnly || !c.live || (c.free ?: 0) > 0)
    }

    /** The ports (OSM names) of the plug tiles that are on, from a car's ports. */
    fun portsOf(plugIds: Set<String>): Set<String> = PLUGS.filter { it.id in plugIds }.flatMap { it.types }.toSet()

    /** The plug tiles a set of OSM port names lights. */
    fun plugIdsOf(ports: Set<String>): Set<String> = PLUGS.filter { p -> p.types.any { it in ports } }.map { it.id }.toSet()

    /** The sheet's summary: sites, free points of those with live data, and the cheapest price. */
    data class Summary(val sites: Int, val free: Int?, val cheapest: Double?, val currency: String?)

    fun summary(list: List<Charger>): Summary {
        val live = list.filter { it.live }
        val prices = list.mapNotNull { c -> c.pricePerKwh?.let { it to c.currency } }
        val cheapest = prices.minByOrNull { it.first }
        return Summary(list.size, if (live.isEmpty()) null else live.sumOf { it.free ?: 0 }, cheapest?.first, cheapest?.second)
    }

    /** How many sites in each power band, in the board's order. */
    fun mix(list: List<Charger>): List<Pair<PowerBand, Int>> = PowerBand.entries.map { b -> b to list.count { it.band == b } }.filter { it.second > 0 }

    /**
     * Minutes to charge [car] from [from] to [to] (0..1) at a site giving [stationKw]: the car takes what its charging
     * curve allows (or full power to 80 % and a taper to the top), never more than the station gives. Null for a car with
     * no power figure.
     */
    fun chargeMinutes(car: EvModel, from: Double, to: Double, stationKw: Double, dc: Boolean, efficiency: Double = 0.92): Double? {
        val carMax = (if (dc) car.maxDcKw else car.maxAcKw) ?: return null
        if (carMax <= 0 || stationKw <= 0 || to <= from) return 0.0
        var hours = 0.0
        val steps = ((to - from) * 100).roundToInt().coerceAtLeast(1)
        val step = (to - from) / steps
        for (i in 0 until steps) {
            val soc = from + step * (i + 0.5)
            val taper = if (dc) curveAt(car, soc, carMax) else carMax
            val p = min(stationKw, taper) * efficiency
            hours += car.batteryKwh * step / max(p, 1.0)
        }
        return hours * 60.0
    }

    private fun curveAt(car: EvModel, soc: Double, maxKw: Double): Double {
        val c = car.curve
        if (c.size >= 2) {
            val i = c.indexOfLast { it.first <= soc }.coerceAtLeast(0)
            val a = c[i]
            val b = c.getOrNull(i + 1) ?: return a.second
            val t = ((soc - a.first) / (b.first - a.first)).coerceIn(0.0, 1.0)
            return a.second + (b.second - a.second) * t
        }
        return if (soc <= 0.8) maxKw else maxKw * (1.0 - (soc - 0.8) / 0.2 * 0.85)
    }

    /** Kilometres of range at [soc] for [car]: the charge in the battery over its mixed consumption. */
    fun rangeKm(car: EvModel, soc: Double): Double =
        if (car.consumption > 0 && car.batteryKwh > 0) soc.coerceIn(0.0, 1.0) * car.batteryKwh / (car.consumption / 100.0) else 0.0

    /** "1:05" or "20′": minutes as the charge plan bar writes them. */
    fun minutes(m: Double): String {
        val n = m.roundToInt().coerceAtLeast(1)
        return if (n < 60) "$n′" else "${n / 60}:${(n % 60).toString().padStart(2, '0')}"
    }
}
