package app.atlas.ui.mine

import app.atlas.geo.LatLon
import app.atlas.geo.Sun
import app.atlas.mapview.MapLayer
import app.atlas.mapview.Theme
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** What the map look screen works with: the layers and their symbols, the time of light, and how much the streets are mapped. */
object LayersModel {
    /** A layer you can switch on the map, with the board symbol that stands for it. */
    class LayerInfo(val layer: MapLayer, val symbol: String, val key: String)

    /** The layers in the order the sheet shows them. */
    val LAYERS = listOf(
        LayerInfo(MapLayer.BUILDINGS_3D, "ui.3d", "buildings"),
        LayerInfo(MapLayer.SHADOWS, "kit.sun-moon", "shadows"),
        LayerInfo(MapLayer.TREES, "nature.tree", "trees"),
        LayerInfo(MapLayer.POIS, "ui.pin", "pois"),
        LayerInfo(MapLayer.LABELS, "ui.text-size", "labels"),
        LayerInfo(MapLayer.HILLSHADE, "cat.nature", "hillshade"),
        LayerInfo(MapLayer.CONTOURS, "kit.slope", "contours"),
        LayerInfo(MapLayer.TRANSIT, "mode.transit", "transit"),
        LayerInfo(MapLayer.CYCLE_NETWORK, "mode.bike", "cycle"),
        LayerInfo(MapLayer.HIKING, "mode.walk", "hiking"),
        LayerInfo(MapLayer.SEAMARKS, "cat.water", "seamarks"),
    )

    /** The themes in the order of the chips; "calm" is the engine's grey look. */
    val THEMES = listOf(Theme.AUTO, Theme.DAY, Theme.NIGHT, Theme.CHART, Theme.GRAY)

    /** The layers kept in settings: the saved set (names) or, until one is saved, the map's own default. */
    fun layers(saved: Set<String>?, threeD: Boolean, default: Set<MapLayer>): Set<MapLayer> {
        val base = saved?.mapNotNull { runCatching { MapLayer.valueOf(it) }.getOrNull() }?.toSet() ?: default
        return if (threeD) base + MapLayer.BUILDINGS_3D else base - MapLayer.BUILDINGS_3D
    }

    /** The set to save when [layer] is switched [on]: the 3D buildings have a setting of their own, so they are not in it. */
    fun toggled(current: Set<MapLayer>, layer: MapLayer, on: Boolean): Set<MapLayer> = (if (on) current + layer else current - layer) - MapLayer.BUILDINGS_3D

    /** The instant at [minutes] after local midnight of [date] in [zone]. */
    fun at(date: LocalDate, minutes: Int, zone: ZoneId): Instant = date.atStartOfDay(zone).plusMinutes(minutes.toLong()).toInstant()

    /** The minute of the day (0..1439) of [now] in [zone]. */
    fun minuteOf(now: Instant, zone: ZoneId): Int = now.atZone(zone).let { it.hour * 60 + it.minute }

    /** The sun's key moments of [date] at [at] as minutes of the day: sunrise, solar noon and sunset (null in polar night or day). */
    fun sunMinutes(date: LocalDate, at: LatLon, zone: ZoneId): Triple<Int?, Int, Int?> {
        val t = Sun.times(date, at, zone)
        fun m(z: java.time.ZonedDateTime?) = z?.let { it.hour * 60 + it.minute }
        return Triple(m(t.sunrise), m(t.solarNoon) ?: 720, m(t.sunset))
    }

    /** "17:30" or "5:30 pm". */
    fun clock(minutes: Int, twelve: Boolean): String = SettingsModel.clock(minutes / 60, minutes % 60, twelve)
}

/**
 * How much of the streets around here OpenStreetMap knows: of the roads with a lamp, a surface, a pavement or parking to
 * map, the share that says so. Read from the tags of the ways around the map's centre; nothing is guessed.
 */
data class StreetCoverage(val lighting: Float, val surfaces: Float, val sidewalks: Float, val parking: Float, val ways: Int) {
    /** The mean of the four, "72% known". */
    val known: Int get() = ((lighting + surfaces + sidewalks + parking) / 4f * 100).roundToInt()

    companion object {
        private val CARRIAGEWAYS = setOf("primary", "secondary", "tertiary", "unclassified", "residential", "living_street")
        private val WALKWAYS = setOf("pedestrian", "footway", "cycleway", "path")

        /** The coverage of the ways whose tags are [ways] (each a `highway=*` way); null when there are none to judge. */
        fun of(ways: Iterable<Map<String, String>>): StreetCoverage? {
            var n = 0
            var roads = 0
            var lit = 0
            var surface = 0
            var sidewalk = 0
            var parking = 0
            var seen = 0
            for (t in ways) {
                val h = t["highway"] ?: continue
                val road = h in CARRIAGEWAYS
                val walk = h in WALKWAYS
                if (!road && !walk && h != "service") continue
                seen++
                n++
                if ("lit" in t) lit++
                if ("surface" in t) surface++
                if (road) {
                    roads++
                    if (t.keys.any { it == "sidewalk" || it.startsWith("sidewalk:") } || t["footway"] == "sidewalk") sidewalk++
                    if (t.keys.any { it.startsWith("parking:") }) parking++
                }
            }
            if (seen == 0) return null
            fun share(a: Int, b: Int) = if (b == 0) 0f else a.toFloat() / b
            return StreetCoverage(share(lit, n), share(surface, n), share(sidewalk, roads), share(parking, roads), seen)
        }

        /** The Overpass query for the highways within [radiusM] of [at], tags only. */
        fun query(at: LatLon, radiusM: Int = 400): String = "[out:json][timeout:25];way[\"highway\"~\"^(primary|secondary|tertiary|unclassified|residential|living_street|pedestrian|footway|cycleway|path|service)$\"]" +
            "(around:$radiusM,${String.format(java.util.Locale.ROOT, "%.5f", at.lat)},${String.format(java.util.Locale.ROOT, "%.5f", at.lon)});out tags;"
    }
}
