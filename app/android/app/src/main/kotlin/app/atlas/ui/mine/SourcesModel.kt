package app.atlas.ui.mine

import app.atlas.providers.Catalogue
import app.atlas.providers.KeyNeed
import app.atlas.providers.ProviderInfo
import app.atlas.providers.ProviderState
import app.atlas.providers.stateFor
import java.net.URI

/** Where Atlas's numbers come from, for Data sources & keys, the privacy list and the credits. */
object SourcesModel {
    /** Tankerkönig, the German fuel price API (the Pump screen reads it with the key entered here). */
    val TANKERKOENIG = ProviderInfo(
        id = "tankerkoenig", name = "Tankerkönig", licence = "CC BY 4.0", attribution = "Fuel prices: Tankerkönig (creativecommons.tankerkoenig.de)",
        url = "https://creativecommons.tankerkoenig.de", keyNeed = KeyNeed.REQUIRED,
        note = "Prices of every petrol station in Germany, live. Free with your own key from creativecommons.tankerkoenig.de.",
    )

    /** Every source the Data screen lists. */
    val ALL: List<ProviderInfo> = Catalogue.ALL + TANKERKOENIG

    /** One row of the Data screen: the source, whether it is on, and whether it can work (a key may be missing). */
    data class Row(val info: ProviderInfo, val on: Boolean, val state: ProviderState, val keySet: Boolean) {
        val needsKey: Boolean get() = info.keyNeed == KeyNeed.REQUIRED || info.keyNeed == KeyNeed.PARTNER
        val canTakeKey: Boolean get() = info.keyNeed != KeyNeed.NONE
        val host: String? get() = hostOf(info.url)
    }

    /** The rows for the sources switched [off] and the [keys] entered, by provider id. */
    fun rows(off: Set<String>, keys: Map<String, String>, all: List<ProviderInfo> = ALL): List<Row> = all.map { info ->
        val key = keys[info.id]
        val keySet = !key.isNullOrBlank()
        // A source that is off by default (Chargemap) is on only once a key says yes.
        val on = info.id !in off && (info.onByDefault || keySet)
        Row(info, on, stateFor(info.keyNeed, key), keySet)
    }

    /** Sources that are on and can work now, and keys entered. */
    fun summary(rows: List<Row>): Pair<Int, Int> = rows.count { it.on && it.state == ProviderState.READY } to rows.count { it.keySet }

    /** The open sources: no key needed, on by default. */
    fun open(rows: List<Row>): List<Row> = rows.filter { !it.needsKey && it.info.keyNeed == KeyNeed.NONE }

    /** Sources that take a key to work better (optional) or at all (required, partner). */
    fun keyed(rows: List<Row>): List<Row> = rows.filter { it.canTakeKey }

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
