package app.atlas.render.data

import app.atlas.geo.LatLon

/**
 * A coarse "which country is this point in", for the clock and the building tradition of a region that has no entry in
 * the catalogue. Boxes overlap near borders; the smallest box holding the point wins, so enclaves and small states
 * resolve. A pack made from a Geofabrik extract knows its country exactly (packs/regions.json) and passes it instead.
 */
object Countries {
    private class Box(val code: String, val west: Double, val south: Double, val east: Double, val north: Double) {
        val area: Double get() = (east - west) * (north - south)
        fun has(p: LatLon) = p.lon in west..east && p.lat in south..north
    }

    // code, west, south, east, north
    private val boxes = listOf(
        Box("NL", 3.2, 50.75, 7.25, 53.6), Box("BE", 2.5, 49.5, 6.4, 51.5), Box("LU", 5.73, 49.44, 6.53, 50.19),
        Box("DE", 5.8, 47.25, 15.1, 55.1), Box("FR", -5.2, 41.3, 9.7, 51.1), Box("GB", -8.7, 49.8, 1.8, 60.9),
        Box("IE", -10.7, 51.4, -5.9, 55.5), Box("ES", -9.4, 35.9, 4.4, 43.9), Box("PT", -9.6, 36.9, -6.1, 42.2),
        Box("IT", 6.6, 36.6, 18.6, 47.1), Box("CH", 5.95, 45.8, 10.5, 47.85), Box("AT", 9.5, 46.35, 17.2, 49.05),
        Box("DK", 8.0, 54.5, 15.2, 57.8), Box("NO", 4.4, 57.9, 31.2, 71.3), Box("SE", 10.9, 55.3, 24.2, 69.1),
        Box("FI", 20.5, 59.7, 31.7, 70.2), Box("IS", -24.6, 63.2, -13.4, 66.6), Box("PL", 14.1, 49.0, 24.2, 54.9),
        Box("CZ", 12.1, 48.5, 18.9, 51.1), Box("SK", 16.8, 47.7, 22.6, 49.7), Box("HU", 16.1, 45.7, 22.9, 48.6),
        Box("RO", 20.2, 43.6, 29.8, 48.3), Box("BG", 22.3, 41.2, 28.7, 44.3), Box("GR", 19.3, 34.7, 28.3, 41.8),
        Box("HR", 13.4, 42.3, 19.5, 46.6), Box("SI", 13.3, 45.4, 16.7, 46.9), Box("RS", 18.8, 42.2, 23.1, 46.2),
        Box("EE", 21.7, 57.5, 28.3, 59.8), Box("LV", 20.9, 55.6, 28.3, 58.1), Box("LT", 20.9, 53.9, 26.9, 56.5),
        Box("UA", 22.1, 44.3, 40.3, 52.4), Box("BY", 23.1, 51.2, 32.8, 56.2), Box("TR", 25.6, 35.8, 44.9, 42.2),
        Box("RU", 27.0, 41.0, 180.0, 77.7), Box("MT", 14.1, 35.78, 14.6, 36.1), Box("CY", 32.2, 34.5, 34.7, 35.75),
        Box("US", -125.0, 24.4, -66.9, 49.5), Box("US", -170.0, 51.0, -129.9, 71.5), Box("US", -160.5, 18.8, -154.7, 22.3),
        Box("CA", -141.0, 41.7, -52.6, 83.2), Box("MX", -118.5, 14.5, -86.7, 32.8), Box("CU", -85.0, 19.8, -74.1, 23.3),
        Box("BR", -74.0, -33.8, -34.8, 5.3), Box("AR", -73.6, -55.1, -53.6, -21.8), Box("CL", -75.7, -55.9, -66.4, -17.5),
        Box("PE", -81.4, -18.4, -68.7, -0.0), Box("CO", -79.0, -4.3, -66.8, 12.5), Box("VE", -73.4, 0.6, -59.8, 12.2),
        Box("ZA", 16.4, -34.9, 32.9, -22.1), Box("EG", 24.7, 21.7, 36.9, 31.7), Box("MA", -13.2, 27.6, -1.0, 35.9),
        Box("KE", 33.9, -4.7, 41.9, 5.0), Box("NG", 2.7, 4.3, 14.7, 13.9), Box("ET", 33.0, 3.4, 48.0, 14.9),
        Box("IL", 34.2, 29.5, 35.9, 33.4), Box("SA", 34.6, 16.4, 55.7, 32.2), Box("AE", 51.5, 22.6, 56.4, 26.1),
        Box("IR", 44.0, 25.0, 63.3, 39.8), Box("IN", 68.1, 6.7, 97.4, 35.5), Box("PK", 60.9, 23.6, 77.8, 37.1),
        Box("CN", 73.5, 18.1, 134.8, 53.6), Box("JP", 122.9, 24.0, 153.99, 45.6), Box("KR", 125.0, 33.0, 131.0, 38.7),
        Box("TW", 119.9, 21.8, 122.1, 25.4), Box("TH", 97.3, 5.6, 105.7, 20.5), Box("VN", 102.1, 8.4, 109.5, 23.4),
        Box("SG", 103.6, 1.15, 104.1, 1.5), Box("MY", 99.6, 0.8, 119.3, 7.4), Box("ID", 95.0, -11.1, 141.0, 5.9),
        Box("PH", 116.9, 4.6, 126.6, 21.2), Box("AU", 112.9, -43.7, 153.7, -10.6), Box("NZ", 166.4, -47.4, 178.6, -34.4),
    )

    /** The ISO 3166 code of the country around [p], or null far from any of the boxes. */
    fun of(p: LatLon): String? = boxes.filter { it.has(p) }.minByOrNull { it.area }?.code

    private val zones = mapOf(
        "NL" to "Europe/Amsterdam", "BE" to "Europe/Brussels", "LU" to "Europe/Luxembourg", "DE" to "Europe/Berlin",
        "FR" to "Europe/Paris", "GB" to "Europe/London", "IE" to "Europe/Dublin", "ES" to "Europe/Madrid", "PT" to "Europe/Lisbon",
        "IT" to "Europe/Rome", "CH" to "Europe/Zurich", "AT" to "Europe/Vienna", "DK" to "Europe/Copenhagen", "NO" to "Europe/Oslo",
        "SE" to "Europe/Stockholm", "FI" to "Europe/Helsinki", "IS" to "Atlantic/Reykjavik", "PL" to "Europe/Warsaw",
        "CZ" to "Europe/Prague", "SK" to "Europe/Bratislava", "HU" to "Europe/Budapest", "RO" to "Europe/Bucharest",
        "BG" to "Europe/Sofia", "GR" to "Europe/Athens", "HR" to "Europe/Zagreb", "SI" to "Europe/Ljubljana", "RS" to "Europe/Belgrade",
        "EE" to "Europe/Tallinn", "LV" to "Europe/Riga", "LT" to "Europe/Vilnius", "UA" to "Europe/Kyiv", "BY" to "Europe/Minsk",
        "TR" to "Europe/Istanbul", "MT" to "Europe/Malta", "CY" to "Asia/Nicosia", "CU" to "America/Havana", "MX" to "America/Mexico_City",
        "BR" to "America/Sao_Paulo", "AR" to "America/Argentina/Buenos_Aires", "CL" to "America/Santiago", "PE" to "America/Lima",
        "CO" to "America/Bogota", "VE" to "America/Caracas", "ZA" to "Africa/Johannesburg", "EG" to "Africa/Cairo",
        "MA" to "Africa/Casablanca", "KE" to "Africa/Nairobi", "NG" to "Africa/Lagos", "ET" to "Africa/Addis_Ababa",
        "IL" to "Asia/Jerusalem", "SA" to "Asia/Riyadh", "AE" to "Asia/Dubai", "IR" to "Asia/Tehran", "IN" to "Asia/Kolkata",
        "PK" to "Asia/Karachi", "CN" to "Asia/Shanghai", "JP" to "Asia/Tokyo", "KR" to "Asia/Seoul", "TW" to "Asia/Taipei",
        "TH" to "Asia/Bangkok", "VN" to "Asia/Ho_Chi_Minh", "SG" to "Asia/Singapore", "MY" to "Asia/Kuala_Lumpur", "PH" to "Asia/Manila",
    )

    /** The IANA time zone of a point in country [code], by longitude where the country spans several. Defaults to UTC. */
    fun timezone(code: String?, p: LatLon): String {
        val lon = p.lon
        val lat = p.lat
        return when (code) {
            "US" -> when {
                lon < -154 && lat < 23 -> "Pacific/Honolulu"
                lat > 51 && lon < -130 -> "America/Anchorage"
                lon < -114.5 -> "America/Los_Angeles"
                lon < -101 -> "America/Denver"
                lon < -87 -> "America/Chicago"
                else -> "America/New_York"
            }
            "CA" -> when {
                lon < -114 -> "America/Vancouver"
                lon < -101 -> "America/Edmonton"
                lon < -87 -> "America/Winnipeg"
                lon < -60 -> "America/Toronto"
                else -> "America/Halifax"
            }
            "AU" -> when {
                lon < 129 -> "Australia/Perth"
                lon < 141 -> "Australia/Adelaide"
                else -> "Australia/Sydney"
            }
            "RU" -> when {
                lon < 60 -> "Europe/Moscow"
                lon < 90 -> "Asia/Yekaterinburg"
                lon < 120 -> "Asia/Krasnoyarsk"
                lon < 140 -> "Asia/Yakutsk"
                else -> "Asia/Vladivostok"
            }
            "ID" -> if (lon < 114) "Asia/Jakarta" else if (lon < 135) "Asia/Makassar" else "Asia/Jayapura"
            "NZ" -> "Pacific/Auckland"
            null -> "UTC"
            else -> zones[code] ?: "UTC"
        }
    }

    /**
     * The building tradition the engine draws for a country (render/traditions: the five that are drawn today), or null
     * for the region-wide palette. The catalogue lists the planned ones; they join here as they are drawn.
     */
    fun tradition(code: String?): String? = when (code) {
        "NL" -> "nl_amsterdam"
        "FR" -> "fr_paris"
        "IT" -> "it_tuscan"
        "NO" -> "no_timber"
        "GB" -> "gb_terrace"
        else -> null
    }
}
