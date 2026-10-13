package app.atlas.providers

import java.util.Locale

/** One kind of socket at a charger: OSM's `socket:<type>` name, how many, and the power in kW when known. */
data class Socket(val type: String, val count: Int, val kw: Double?)

/**
 * The charger vocabulary, in OSM's terms (https://wiki.openstreetmap.org/wiki/Key:socket:*): every provider's connector
 * names map to these, so a charger from Open Charge Map, NDW, AFDC or the Bundesnetzagentur reads like an OSM one.
 */
object Sockets {
    /** Sockets that charge with direct current. */
    val DC = setOf("type2_combo", "type1_combo", "chademo", "chaoji", "gb_dc", "tesla_supercharger", "tesla_supercharger_ccs", "nacs", "mcs", "type6")

    /** The tag Atlas adds with a charger's highest power in kW, for the router and the map pins. */
    const val POWER_KW = "atlas:power_kw"

    /** The sockets in [tags], one entry per value of a semicolon list (`socket:type2_combo=6;3` with `:output=350 kW;90 kW`). */
    fun of(tags: Map<String, String>): List<Socket> {
        val out = ArrayList<Socket>()
        for ((k, v) in tags) {
            if (!k.startsWith("socket:")) continue
            val type = k.substring(7)
            if (type.contains(':')) continue
            val outputs = tags["$k:output"]?.split(';')?.map { parseKw(it) } ?: emptyList()
            val counts = v.split(';').map { it.trim() }
            counts.forEachIndexed { i, c ->
                val n = c.toIntOrNull() ?: if (c == "yes") 1 else return@forEachIndexed
                if (n <= 0) return@forEachIndexed
                out.add(Socket(type, n, outputs.getOrNull(i) ?: outputs.singleOrNull()))
            }
        }
        return out.sortedWith(compareByDescending<Socket> { it.kw ?: 0.0 }.thenBy { it.type })
    }

    /** The highest power at the charger: the sockets' outputs, else `charging_station:output`, else Atlas's own tag. */
    fun maxKw(tags: Map<String, String>): Double? =
        of(tags).mapNotNull { it.kw }.maxOrNull() ?: parseKw(tags["charging_station:output"]) ?: tags[POWER_KW]?.toDoubleOrNull()

    /** True if any socket there is DC. */
    fun hasDc(tags: Map<String, String>): Boolean = of(tags).any { it.type in DC }

    /** Power as OSM writes it, in kW: "50 kW", "22", "11000 W", "3,7 kW", "350 kW". */
    fun parseKw(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val m = POWER.find(s.trim()) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "w" -> n / 1000.0
            "mw" -> n * 1000.0
            else -> n
        }
    }

    private val POWER = Regex("""([0-9]+(?:[.,][0-9]+)?)\s*([kKmM]?[wW])?""")

    /** Builds `socket:*` tags from [sockets], merging counts per type and writing outputs as "<n> kW" lists. */
    fun tags(sockets: List<Socket>): Map<String, String> {
        val byType = LinkedHashMap<String, MutableList<Socket>>()
        for (s in sockets) byType.getOrPut(s.type) { ArrayList() }.add(s)
        val out = LinkedHashMap<String, String>()
        for ((type, list) in byType) {
            // Same power: one count. Different powers: aligned semicolon lists, highest first.
            val groups = list.groupBy { it.kw }.map { (kw, g) -> Socket(type, g.sumOf { it.count }, kw) }.sortedByDescending { it.kw ?: 0.0 }
            out["socket:$type"] = groups.joinToString(";") { it.count.toString() }
            if (groups.any { it.kw != null }) out["socket:$type:output"] = groups.joinToString(";") { g -> g.kw?.let { "${tagNumber(it)} kW" } ?: "" }
        }
        sockets.mapNotNull { it.kw }.maxOrNull()?.let { out[POWER_KW] = tagNumber(it) }
        return out
    }

    /**
     * True if a car with [connectors] (OSM socket names, as in `EvSpec.connectors`) can charge at a socket of [type].
     * A tethered type 2 cable fits a type 2 car; Tesla's CCS supercharger fits a CCS car; NACS fits a Tesla.
     */
    fun fits(connectors: Set<String>, type: String): Boolean = when (type) {
        in connectors -> true
        "type2_cable" -> "type2" in connectors
        "tesla_supercharger_ccs" -> "type2_combo" in connectors
        "nacs" -> "tesla_supercharger" in connectors
        "tesla_supercharger" -> "nacs" in connectors
        "tesla_destination" -> "type2" in connectors || "nacs" in connectors
        else -> false
    }

    /** True if the charger in [tags] has a socket for [connectors] with at least [minKw] (when its power is known). */
    fun accepts(tags: Map<String, String>, connectors: Set<String>, minKw: Double = 0.0): Boolean {
        val sockets = of(tags)
        if (sockets.isEmpty()) return connectors.isEmpty() && (minKw <= 0 || (maxKw(tags) ?: 0.0) >= minKw)
        return sockets.any { s -> (connectors.isEmpty() || fits(connectors, s.type)) && (minKw <= 0 || (s.kw ?: maxKw(tags) ?: 0.0) >= minKw) }
    }

    /** Open Charge Map connection type ids, from its reference data, to OSM socket names. */
    val OCM: Map<Int, String> = mapOf(
        1 to "type1", 2 to "chademo", 3 to "bs1363", 4 to "cee_blue", 8 to "tesla_roadster", 9 to "nema_5_20", 10 to "nema_14_30",
        11 to "nema_14_50", 13 to "typec", 16 to "cee_blue", 17 to "cee_red_16a", 22 to "nema_5_15", 23 to "typee", 25 to "type2",
        26 to "type3c", 27 to "nacs", 28 to "schuko", 29 to "as3112", 30 to "tesla_supercharger", 32 to "type1_combo",
        33 to "type2_combo", 34 to "cee_blue", 35 to "cee_red_16a", 36 to "type3a", 1036 to "type2_cable", 1037 to "sev1011_t13",
        1038 to "gb_ac", 1039 to "gb_ac", 1040 to "gb_dc", 1044 to "chaoji",
    )

    /** OCPI 2.2 connector standards (NDW, partner feeds) to OSM socket names; [cable] is OCPI's connector format. */
    fun ocpi(standard: String, cable: Boolean = false): String? = when (standard.uppercase(Locale.ROOT)) {
        "IEC_62196_T2" -> if (cable) "type2_cable" else "type2"
        "IEC_62196_T2_COMBO" -> "type2_combo"
        "IEC_62196_T1" -> "type1"
        "IEC_62196_T1_COMBO" -> "type1_combo"
        "CHADEMO" -> "chademo"
        "CHAOJI" -> "chaoji"
        "IEC_62196_T3A" -> "type3a"
        "IEC_62196_T3C" -> "type3c"
        "IEC_60309_2_SINGLE_16" -> "cee_blue"
        "IEC_60309_2_THREE_16" -> "cee_red_16a"
        "IEC_60309_2_THREE_32" -> "cee_red_32a"
        "IEC_60309_2_THREE_64" -> "cee_red_63a"
        "DOMESTIC_E" -> "typee"
        "DOMESTIC_F" -> "schuko"
        "DOMESTIC_G" -> "bs1363"
        "DOMESTIC_I" -> "as3112"
        "TESLA_S" -> "tesla_supercharger"
        "TESLA_R" -> "tesla_roadster"
        "GBT_AC" -> "gb_ac"
        "GBT_DC" -> "gb_dc"
        "NEMA_5_20" -> "nema_5_20"
        "NEMA_6_30" -> "nema_6_30"
        "NEMA_10_30" -> "nema_10_30"
        "NEMA_14_50" -> "nema_14_50"
        "SAE_J3400", "NACS" -> "nacs"
        "MCS" -> "mcs"
        else -> null
    }

    /** AFDC (US) connector codes to OSM socket names. */
    val AFDC: Map<String, String> = mapOf(
        "J1772" to "type1", "J1772COMBO" to "type1_combo", "CHADEMO" to "chademo", "TESLA" to "nacs", "J3271" to "mcs",
        "NEMA515" to "nema_5_15", "NEMA520" to "nema_5_20", "NEMA1450" to "nema_14_50",
    )

    /** The Bundesnetzagentur register's plug names ("AC Typ 2 Steckdose", "DC Fahrzeugkupplung Typ Combo 2 (CCS)") to OSM socket names. */
    fun bnetza(plug: String): String? {
        val p = plug.lowercase(Locale.ROOT)
        return when {
            "combo 2" in p || ("ccs" in p && "combo 1" !in p) -> "type2_combo"
            "combo 1" in p -> "type1_combo"
            "chademo" in p -> "chademo"
            "megawatt" in p || "mcs" in p -> "mcs"
            "tesla" in p -> "tesla_supercharger"
            "typ 2" in p && ("kupplung" in p || "kabel" in p) -> "type2_cable"
            "typ 2" in p -> "type2"
            "typ 1" in p -> "type1"
            "schuko" in p -> "schuko"
            "cee" in p && ("5" in p || "rot" in p) -> "cee_red_32a"
            "cee" in p -> "cee_blue"
            else -> null
        }
    }
}
