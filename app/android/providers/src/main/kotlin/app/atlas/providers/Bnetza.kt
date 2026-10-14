package app.atlas.providers

import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.model.Place
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/**
 * The Bundesnetzagentur's Ladesäulenregister: Germany's official register of public chargers (CC BY 4.0), a monthly
 * CSV of about 55 MB. It is static (no live status) and incomplete (only operators who finished the notification
 * procedure), but authoritative for what it holds: operator, power, plugs, EVSE ids, payment and opening hours.
 *
 * The file name carries its date, so [sourceUrl] reads the current link from the register's web page.
 */
class BnetzaChargers(
    http: Http,
    cacheDir: File?,
    private val pageUrl: String = PAGE,
    private val fixedUrl: String? = null,
    maxAge: Duration = 30.days,
    autoDownload: Boolean = false,
    region: BBox? = null,
    clock: () -> Instant = Instant::now,
) : DownloadedChargers(http, cacheDir, maxAge, autoDownload, region, clock) {
    override val info = INFO
    override val cacheName = ID

    override suspend fun sourceUrl(): String {
        fixedUrl?.let { return it }
        val html = http.execute(http.request(pageUrl).header("Accept", "text/html").build()).requireOk().text
        return csvLink(html, pageUrl) ?: throw ProviderException(ID, "No CSV link on the Bundesnetzagentur's register page")
    }

    override fun parse(body: InputStream, region: BBox?, add: (Place) -> Unit) = Companion.parse(body, region, add)

    companion object {
        const val ID = "bnetza"
        const val PAGE = "https://www.bundesnetzagentur.de/DE/Fachthemen/ElektrizitaetundGas/E-Mobilitaet/Ladesaeulenkarte/start.html"

        val INFO = ProviderInfo(
            id = ID,
            name = "Bundesnetzagentur (Germany)",
            licence = "CC BY 4.0",
            attribution = "Ladesäulenregister: Bundesnetzagentur.de, CC BY 4.0",
            url = "https://www.bundesnetzagentur.de/ladesaeulenkarte",
            note = "Germany's official register, monthly and without live status. About 55 MB; Atlas fetches it on Wi-Fi.",
        )

        /** The newest register CSV linked from the page [html] at [base]. */
        fun csvLink(html: String, base: String = PAGE): String? {
            val links = Regex("""href="([^"]*Ladesaeulenregister[^"]*\.csv)"""").findAll(html).map { it.groupValues[1] }.toList()
            val best = links.maxByOrNull { Regex("""\d{4}-\d{2}-\d{2}""").find(it)?.value ?: "" } ?: return null
            return base.toHttpUrl().resolve(best)?.toString()
        }

        /** Parses the register CSV (UTF-8, `;`, decimal commas, a preamble before the header), calling [add] per charger in [region]. */
        fun parse(body: InputStream, region: BBox?, add: (Place) -> Unit) {
            val reader = BufferedReader(InputStreamReader(body, Charsets.UTF_8), 1 shl 16)
            var columns: Columns? = null
            while (true) {
                val row = Csv.readRow(reader) ?: break
                val c = columns
                if (c == null) {
                    if (row.firstOrNull()?.trimStart('﻿') == "Ladeeinrichtungs-ID") columns = Columns(row)
                    continue
                }
                val place = c.place(row) ?: continue
                if (region == null || place.location in region) add(place)
            }
            if (columns == null) throw ProviderException(ID, "No header row in the Ladesäulenregister")
        }

        private val WEEKDAYS = listOf("Montag" to "Mo", "Dienstag" to "Tu", "Mittwoch" to "We", "Donnerstag" to "Th", "Freitag" to "Fr", "Samstag" to "Sa", "Sonntag" to "Su")

        /** The register's opening hours as OSM's `opening_hours`: "247" is 24/7; day and time lists become rules. */
        internal fun openingHours(kind: String?, days: String?, times: String?): String? {
            val k = kind?.trim().orEmpty()
            if (k == "247" || k.contains("24/7")) return "24/7"
            if (days.isNullOrBlank() || times.isNullOrBlank()) return null
            val ds = days.split(';').map { it.trim() }
            val ts = times.split(';').map { it.trim() }
            if (ds.size != ts.size) return null
            val rules = ArrayList<Pair<MutableList<Int>, String>>()
            for ((i, d) in ds.withIndex()) {
                val day = WEEKDAYS.indexOfFirst { it.first.equals(d, ignoreCase = true) }.takeIf { it >= 0 } ?: return null
                val t = ts[i].replace(" ", "")
                if (!TIMES.matches(t)) return null
                val last = rules.lastOrNull()
                if (last != null && last.second == t && last.first.last() == day - 1) last.first.add(day) else rules.add(mutableListOf(day) to t)
            }
            if (rules.size == 1 && rules[0].first.size == 7 && rules[0].second == "00:00-23:59") return "24/7"
            return rules.joinToString("; ") { (dd, t) ->
                val span = if (dd.size == 1) WEEKDAYS[dd[0]].second else "${WEEKDAYS[dd.first()].second}-${WEEKDAYS[dd.last()].second}"
                "$span ${t.replace("23:59", "24:00")}"
            }
        }

        private val TIMES = Regex("""\d{2}:\d{2}-\d{2}:\d{2}(,\d{2}:\d{2}-\d{2}:\d{2})*""")
        private val GERMAN_DATE = Regex("""(\d{2})\.(\d{2})\.(\d{4})""")

        private fun number(s: String?): Double? = s?.trim()?.replace(',', '.')?.toDoubleOrNull()

        private class Columns(header: List<String>) {
            private val at = header.withIndex().associate { (i, h) -> h.trim().trimStart('﻿') to i }

            private fun List<String>.col(name: String): String? = at[name]?.let { getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() }

            fun place(row: List<String>): Place? {
                val id = row.col("Ladeeinrichtungs-ID") ?: return null
                val lat = number(row.col("Breitengrad")) ?: return null
                val lon = number(row.col("Längengrad")) ?: return null
                if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
                val tags = LinkedHashMap<String, String>()
                tags["amenity"] = "charging_station"
                val operator = row.col("Betreiber")
                operator?.let { tags["operator"] = it }
                row.col("Anzeigename (Karte)")?.takeIf { it != operator }?.let { tags["brand"] = it }
                val sockets = ArrayList<Socket>()
                val evses = ArrayList<String>()
                for (i in 1..6) {
                    val kw = number(row.col("Nennleistung Stecker$i"))
                    row.col("EVSE-ID$i")?.let(evses::add)
                    val plugs = row.col("Steckertypen$i") ?: continue
                    for (plug in plugs.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
                        Sockets.bnetza(plug)?.let { sockets.add(Socket(it, 1, kw)) }
                    }
                }
                tags.putAll(Sockets.tags(sockets))
                row.col("Anzahl Ladepunkte")?.toIntOrNull()?.let { tags["capacity"] = it.toString() }
                number(row.col("Nennleistung Ladeeinrichtung [kW]"))?.let { tags["charging_station:output"] = "${tagNumber(it)} kW" }
                if (evses.isNotEmpty()) tags["ref:EU:EVSE"] = evses.joinToString(";")
                row.col("Inbetriebnahmedatum")?.let { d ->
                    GERMAN_DATE.matchEntire(d)?.let { m -> tags["start_date"] = "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]}" }
                }
                openingHours(row.col("Öffnungszeiten"), row.col("Öffnungszeiten: Wochentage"), row.col("Öffnungszeiten: Tageszeiten"))?.let { tags["opening_hours"] = it }
                row.col("Bezahlsysteme")?.lowercase()?.let { pay ->
                    if ("rfid" in pay || "ladekarte" in pay) tags["authentication:membership_card"] = "yes"
                    if ("online" in pay || "app" in pay) tags["authentication:app"] = "yes"
                    if ("kreditkarte" in pay) tags["payment:credit_cards"] = "yes"
                    if ("debitkarte" in pay || "girocard" in pay || "ec-karte" in pay) tags["payment:debit_cards"] = "yes"
                    if ("kostenlos" in pay) tags["fee"] = "no"
                    if ("bargeld" in pay) tags["payment:cash"] = "yes"
                }
                val street = listOfNotNull(row.col("Straße"), row.col("Hausnummer")).joinToString(" ").takeIf { it.isNotEmpty() }
                AddressTags.put(tags, row.col("Straße"), row.col("Hausnummer"), row.col("Postleitzahl"), row.col("Ort"), "DE")
                val state = when (row.col("Status")?.lowercase()) {
                    "in betrieb" -> ChargerStatus.State.OPERATIONAL
                    "außer betrieb", "ausser betrieb" -> ChargerStatus.State.OUT_OF_ORDER
                    "in planung", "geplant" -> ChargerStatus.State.PLANNED
                    else -> ChargerStatus.State.UNKNOWN
                }
                val extra = LinkedHashMap<String, String>()
                extra["charger:id"] = chargerId(ID, id)
                extra["attribution"] = INFO.attribution
                extra["licence"] = INFO.licence
                street?.let { extra["label"] = listOfNotNull(it, row.col("Ort")).joinToString(", ") }
                extra.putAll(ChargerStatus(extra["charger:id"]!!, state, total = tags["capacity"]?.toIntOrNull()).toExtra())
                return Place(null, LatLon(lat, lon), tags, source = ID, extra = extra)
            }
        }
    }
}

/** A forgiving reader for semicolon CSV with double-quoted fields (quotes doubled inside, newlines allowed). */
internal object Csv {
    fun readRow(reader: BufferedReader, separator: Char = ';'): List<String>? {
        var line = reader.readLine() ?: return null
        while (line.count { it == '"' } % 2 == 1) line += "\n" + (reader.readLine() ?: break)
        return split(line, separator)
    }

    fun split(line: String, separator: Char = ';'): List<String> {
        val out = ArrayList<String>(48)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}
