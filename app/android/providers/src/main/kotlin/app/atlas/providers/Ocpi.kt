package app.atlas.providers

import app.atlas.geo.LatLon
import app.atlas.model.Place
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * OCPI 2.2 `Location` objects (the EU's charger data standard, used by national access points such as NDW and by
 * charge point operators feeding roaming hubs) as charger places with live EVSE status.
 */
object Ocpi {
    private val alpha3: Map<String, String> by lazy {
        java.util.Locale.getISOCountries().associateBy { java.util.Locale("", it).isO3Country }
    }

    /** OCPI writes the address's country as ISO 3166 alpha-3 ("NLD"); OSM wants alpha-2 ("NL"). */
    internal fun country(code: String?): String? = when (code?.length) {
        2 -> code.uppercase()
        3 -> alpha3[code.uppercase()]
        else -> null
    }

    /** One OCPI Location as a place from [source]; null without coordinates or when it is not to be published. */
    fun toPlace(location: JsonObject, source: String): Place? {
        if (location.bool("publish") == false) return null
        val coords = location.obj("coordinates") ?: return null
        val lat = coords.dbl("latitude") ?: return null
        val lon = coords.dbl("longitude") ?: return null
        val id = location.str("id") ?: return null
        val listed = location.arr("evses").mapNotNull { it.asObject() }
        val evses = listed.filter { it.str("status") != "REMOVED" }
        val sockets = ArrayList<Socket>()
        val perSocket = LinkedHashMap<String, IntArray>() // available, total
        var available = 0
        var known = 0
        var latest: Instant? = null
        for (e in evses) {
            val status = e.str("status")
            if (status != null && status != "UNKNOWN") known++
            if (status == "AVAILABLE") available++
            e.str("last_updated")?.let { s -> runCatching { Instant.parse(s) }.getOrNull() }?.let { t -> if (latest == null || t.isAfter(latest)) latest = t }
            // An EVSE charges one car at a time: count each connector type once per EVSE.
            val types = HashSet<String>()
            for (c in e.arr("connectors").mapNotNull { it.asObject() }) {
                val type = Sockets.ocpi(c.str("standard") ?: continue, c.str("format") == "CABLE") ?: continue
                if (!types.add(type)) continue
                val kw = c.dbl("max_electric_power")?.let { it / 1000.0 } ?: run {
                    val v = c.dbl("max_voltage")
                    val a = c.dbl("max_amperage")
                    if (v != null && a != null) v * a * (if (c.str("power_type") == "AC_3_PHASE") 3.0 else 1.0) / 1000.0 else null
                }
                sockets.add(Socket(type, 1, kw?.let { Math.round(it * 10) / 10.0 }))
                val counts = perSocket.getOrPut(type) { IntArray(2) }
                counts[1]++
                if (status == "AVAILABLE") counts[0]++
            }
        }
        val tags = LinkedHashMap<String, String>()
        tags["amenity"] = "charging_station"
        val operator = location.obj("operator")?.str("name")?.trim() ?: location.obj("owner")?.str("name")?.trim()
        operator?.let { tags["operator"] = it }
        location.obj("operator")?.str("website")?.let { tags["website"] = it }
        location.str("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { tags["name"] = it }
        tags.putAll(Sockets.tags(sockets))
        if (evses.isNotEmpty()) tags["capacity"] = evses.size.toString()
        if (location.obj("opening_times")?.bool("twentyfourseven") == true) tags["opening_hours"] = "24/7"
        AddressTags.put(tags, location.str("address"), null, location.str("postal_code"), location.str("city"), country(location.str("country")) ?: location.str("country_code"))
        when (location.str("parking_type")) {
            "PARKING_GARAGE", "UNDERGROUND_GARAGE" -> tags["covered"] = "yes"
        }
        val extra = LinkedHashMap<String, String>()
        extra["charger:id"] = chargerId(source, id)
        if (location.obj("energy_mix")?.bool("is_green_energy") == true) extra["energy:green"] = "yes"
        val state = when {
            // OCPI keeps an EVSE that was taken away as REMOVED; with every one gone, so is the charger.
            evses.isEmpty() && listed.isNotEmpty() -> ChargerStatus.State.REMOVED
            evses.isEmpty() -> ChargerStatus.State.UNKNOWN
            available > 0 -> ChargerStatus.State.AVAILABLE
            evses.any { it.str("status") in setOf("CHARGING", "BLOCKED", "RESERVED") } -> ChargerStatus.State.OCCUPIED
            evses.all { it.str("status") in setOf("INOPERATIVE", "OUTOFORDER") } -> ChargerStatus.State.OUT_OF_ORDER
            evses.all { it.str("status") == "PLANNED" } -> ChargerStatus.State.PLANNED
            else -> ChargerStatus.State.UNKNOWN
        }
        val status = ChargerStatus(extra["charger:id"]!!, state, if (known > 0) available else null, evses.size, latest, perSocket.mapValues { (_, c) -> c[0] to c[1] })
        extra.putAll(status.toExtra())
        return Place(null, LatLon(lat, lon), tags, source = source, extra = extra)
    }
}
