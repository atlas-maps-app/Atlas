package app.atlas.ui.mine.offline

import app.atlas.geo.BBox
import app.atlas.search.PlaceSource
import app.atlas.services.RegionLayer
import java.time.Duration
import java.time.Instant
import kotlin.math.max

/** What the Offline screen works out from the regions: the storage bar, the age of the data, a card's icons, a map's dots. */
object OfflineModel {
    /** One slice of the storage bar and its legend entry: the layer, its bytes, the board's colour and symbol. */
    data class Slice(val layer: RegionLayer, val bytes: Long, val colour: Long, val symbol: String, val key: String)

    val MAP_COLOUR = 0xFF2E5BFF
    val ROUTES_COLOUR = 0xFF178A4E
    val SEARCH_COLOUR = 0xFFB8461A
    val TERRAIN_COLOUR = 0xFF7B4DFF
    val VOICE_COLOUR = 0xFFF2A71B

    /** The layers' slices in the board's order (map, routes, search, voices); a layer with nothing on the phone has none. */
    fun slices(details: List<RegionDetail>): List<Slice> {
        fun sum(l: RegionLayer) = details.sumOf { it.sizes[l] ?: 0L }
        return listOf(
            Slice(RegionLayer.MAP, sum(RegionLayer.MAP), MAP_COLOUR, "ui.map", "map"),
            Slice(RegionLayer.ROUTING, sum(RegionLayer.ROUTING), ROUTES_COLOUR, "ui.route", "routes"),
            Slice(RegionLayer.PLACES, sum(RegionLayer.PLACES), SEARCH_COLOUR, "ui.search", "search"),
            Slice(RegionLayer.VOICE, sum(RegionLayer.VOICE), VOICE_COLOUR, "ui.volume", "voices"),
        ).filter { it.bytes > 0 }
    }

    /** The slices' widths as shares of the whole bar (what is free of [capacity] fills the rest): each at least a sliver. */
    fun shares(slices: List<Slice>, capacity: Long): List<Float> {
        val cap = max(capacity, slices.sumOf { it.bytes }).coerceAtLeast(1L)
        return slices.map { (it.bytes.toDouble() / cap).toFloat().coerceAtLeast(0.01f) }
    }

    /** The total as the board writes it: a number and its unit ("3.4" "GB", "640" "MB"). */
    fun total(bytes: Long): Pair<String, String> = when {
        bytes >= 1_000_000_000L -> String.format(java.util.Locale.ROOT, "%.1f", bytes / 1e9) to "GB"
        bytes >= 1_000_000L -> Math.round(bytes / 1e6).toString() to "MB"
        bytes >= 1_000L -> Math.round(bytes / 1e3).toString() to "kB"
        else -> bytes.toString() to "B"
    }

    /** A span of time in the unit the board writes it in. */
    enum class AgeUnit { NOW, MINUTES, HOURS, DAYS, WEEKS }

    /** How long ago the newest data was fetched: just now, or a count of minutes, hours, days or weeks. Null with no regions. */
    fun refreshAge(details: List<RegionDetail>, now: Instant): Pair<AgeUnit, Long>? {
        val newest = details.filter { it.meta.layers.isNotEmpty() }.maxOfOrNull { it.meta.updatedAt } ?: return null
        val d = Duration.between(Instant.ofEpochMilli(newest), now)
        return when {
            d.isNegative || d.toMinutes() < 5 -> AgeUnit.NOW to 0L
            d.toHours() < 1 -> AgeUnit.MINUTES to d.toMinutes()
            d.toHours() < 48 -> AgeUnit.HOURS to d.toHours()
            d.toDays() < 14 -> AgeUnit.DAYS to d.toDays()
            else -> AgeUnit.WEEKS to d.toDays() / 7
        }
    }

    /** The symbols a region's card shows for what it holds: the routing modes, search, voice and 3D when the map is Atlas's. */
    fun icons(d: RegionDetail): List<String> = buildList {
        val have = d.meta.layers
        if (RegionLayer.ROUTING in have) addAll(listOf("mode.car", "mode.truck", "mode.bike", "mode.walk"))
        if (RegionLayer.PLACES in have) add("ui.search")
        if (RegionLayer.VOICE in have && d.meta.voices.isNotEmpty()) add("ui.volume")
        if (d.hasAtlas) add("ui.3d")
    }

    /**
     * Where the places of [box] lie, as points in 0..1 of the box (x east, y south), for a card's drawn map: the box is cut
     * into a grid and each cell gives a few places, so a big region shows its towns and not only the first rows of a table.
     */
    fun dots(source: PlaceSource, box: BBox, perCell: Int = 14, columns: Int = 6, rows: Int = 5): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        val w = (box.east - box.west) / columns
        val h = (box.north - box.south) / rows
        if (w <= 0 || h <= 0) return out
        for (r in 0 until rows) for (c in 0 until columns) {
            val cell = BBox(box.west + c * w, box.south + r * h, box.west + (c + 1) * w, box.south + (r + 1) * h)
            for (p in source.inBox(cell, perCell)) {
                val loc = p.location
                val x = ((loc.lon - box.west) / (box.east - box.west)).toFloat()
                val y = ((box.north - loc.lat) / (box.north - box.south)).toFloat()
                if (x in 0f..1f && y in 0f..1f) out += x to y
            }
        }
        return out
    }
}
