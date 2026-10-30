package app.atlas.ui.mine

import app.atlas.providers.Catalogue
import app.atlas.providers.KeyNeed
import app.atlas.providers.ProviderInfo
import app.atlas.providers.ProviderState
import app.atlas.providers.stateFor
import java.net.URI

/**
 * Where Atlas's numbers come from, for Data sources & keys, the privacy list and the credits: every source the app asks,
 * the providers' ([Catalogue.ALL]) and those the app's own screens ask, each with the hosts it is asked at. A source the
 * user switches off is asked no more: [blockedBy] holds back every request to its hosts (the app's HTTP client asks it).
 */
object SourcesModel {
    /** Tankerkönig, the German fuel price API (the Pump screen reads it with the key entered here). */
    val TANKERKOENIG = ProviderInfo(
        id = "tankerkoenig", name = "Tankerkönig", licence = "CC BY 4.0", attribution = "Fuel prices: Tankerkönig (creativecommons.tankerkoenig.de)",
        url = "https://creativecommons.tankerkoenig.de", keyNeed = KeyNeed.REQUIRED,
        note = "Prices of every petrol station in Germany, live. Free with your own key from creativecommons.tankerkoenig.de.",
        hosts = listOf("creativecommons.tankerkoenig.de"),
    )

    /** Atlas's own packs, released on GitHub: the map's windows read in place, and the regions taken offline. */
    val ATLAS_PACKS = ProviderInfo(
        id = "atlas-packs", name = "Atlas's packs", licence = "ODbL", attribution = "Map data © OpenStreetMap contributors; extracts by Geofabrik",
        url = "https://github.com/atlas-maps-app/Atlas/releases",
        note = "The map's windows and offline regions, built on GitHub from OpenStreetMap's data; each window is read by its own bytes.",
        hosts = listOf("github.com", "release-assets.githubusercontent.com"),
    )

    /** BRouter's routing data (brouter.de): the 5° tiles a route or an offline region needs. */
    val BROUTER = ProviderInfo(
        id = "brouter", name = "BRouter", licence = "ODbL", attribution = "Routing data: brouter.de, © OpenStreetMap contributors",
        url = "https://brouter.de", note = "Routing data in 5° tiles, sized when a route needs one and fetched when you take it.",
        hosts = listOf("brouter.de"),
    )

    /** Panoramax, the open street photos (the street photo here). */
    val PANORAMAX = ProviderInfo(
        id = "panoramax", name = "Panoramax", licence = "CC BY-SA 4.0", attribution = "Street photos: Panoramax, each with its author",
        url = "https://panoramax.fr", note = "The photo nearest a spot, from the instance that keeps it (nl.panoramax.xyz, for one).",
        hosts = listOf("api.panoramax.xyz"),
    )

    /** Open Prices, Open Food Facts' shelf prices (the Shop screens). */
    val OPEN_PRICES = ProviderInfo(
        id = "open-prices", name = "Open Prices", licence = "ODbL", attribution = "Prices: Open Prices by Open Food Facts",
        url = "https://prices.openfoodfacts.org", note = "Prices people photographed on shelf labels and receipts.",
        hosts = listOf("prices.openfoodfacts.org"),
    )

    /** The space station's orbit: CelesTrak's element set, else ARISS's copy of it (TleStore). */
    val ISS_ORBIT = ProviderInfo(
        id = "iss-orbit", name = "CelesTrak and ARISS", licence = "Public data", attribution = "The space station's orbit: CelesTrak, ARISS",
        url = "https://celestrak.org", note = "The station's orbit, asked at most every two hours, for when it passes over.",
        hosts = listOf("celestrak.org", "live.ariss.org"),
    )

    /** Every source the app asks: what the Data screen and the privacy list show. */
    val ALL: List<ProviderInfo> = Catalogue.ALL + listOf(ATLAS_PACKS, BROUTER, PANORAMAX, OPEN_PRICES, ISS_ORBIT, TANKERKOENIG)

    /** One row of the Data screen: the source, whether it is on, and whether it can work (a key may be missing). */
    data class Row(val info: ProviderInfo, val on: Boolean, val state: ProviderState, val keySet: Boolean) {
        val needsKey: Boolean get() = info.keyNeed == KeyNeed.REQUIRED || info.keyNeed == KeyNeed.PARTNER
        val canTakeKey: Boolean get() = info.keyNeed != KeyNeed.NONE

        /** The hosts it is asked at, as the privacy list names them: two at most, then how many more. */
        val host: String? get() = info.hosts.takeIf { it.isNotEmpty() }?.let { h -> h.take(2).joinToString(", ") + if (h.size > 2) " +${h.size - 2}" else "" } ?: hostOf(info.url)
    }

    /**
     * The rows for the sources switched [off] and the [keys] entered, by provider id. A source with no switch (asked only
     * when the user acts) is always on; one asked through another's servers is off with them.
     */
    fun rows(off: Set<String>, keys: Map<String, String>, all: List<ProviderInfo> = ALL): List<Row> = all.map { info ->
        val key = keys[info.id]
        val keySet = !key.isNullOrBlank()
        // A source that is off by default (Chargemap) is on only once a key says yes.
        val on = !info.switchable || (info.id !in off && (info.via == null || info.via !in off) && (info.onByDefault || keySet))
        Row(info, on, stateFor(info.keyNeed, key), keySet)
    }

    /** The sources the app asks now: on, and able to work (a key in hand where one is needed). */
    fun asked(rows: List<Row>): List<Row> = rows.filter { it.on && it.state == ProviderState.READY }

    /** Sources that are on and can work now, and keys entered. */
    fun summary(rows: List<Row>): Pair<Int, Int> = asked(rows).size to rows.count { it.keySet }

    /** The open sources: no key needed, on by default. */
    fun open(rows: List<Row>): List<Row> = rows.filter { !it.needsKey && it.info.keyNeed == KeyNeed.NONE }

    /** Sources that take a key to work better (optional) or at all (required, partner). */
    fun keyed(rows: List<Row>): List<Row> = rows.filter { it.canTakeKey }

    /**
     * The source that holds back a request to [host]: the one the host belongs to, where the user switched it off ([off],
     * by id); null lets the request go. A source asked through another's servers (OpenStreetMap's chargers, through
     * Overpass) is held back where it is asked, as those servers serve others too; one without a switch never is.
     */
    fun blockedBy(host: String, off: Set<String>, all: List<ProviderInfo> = ALL): String? =
        if (off.isEmpty()) null else all.firstOrNull { it.id in off && it.switchable && it.via == null && host in it.hosts }?.id

    /** A licence in a few characters for a tag: "ODbL", "CC BY 4.0", "open data", "own terms". */
    fun licenceLabel(licence: String): String {
        val l = licence.trim()
        return when {
            l.length <= 14 -> l
            l.startsWith("Open data", true) || l.startsWith("Open timetables", true) -> "open data"
            l.startsWith("Public", true) -> "public data"
            l.contains("terms", true) -> "own terms"
            else -> l.take(12).trimEnd(' ', ',', ';') + "…"
        }
    }

    /** The host of an address, "overpass-api.de" from a URL; null for a bad one. */
    fun hostOf(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()
}
