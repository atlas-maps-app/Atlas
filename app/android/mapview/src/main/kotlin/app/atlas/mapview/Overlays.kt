package app.atlas.mapview

import androidx.compose.runtime.Immutable
import app.atlas.geo.LatLon
import app.atlas.model.Place

/**
 * The engine's looks. AUTO follows the real daylight at the camera's place; CHART is the boats' guidance (the live light
 * with the sea marks), not a look the map offers.
 */
enum class Theme { AUTO, DAY, NIGHT, CHART }

/** Optional layers on the one look. */
enum class MapLayer { HILLSHADE, CONTOURS, TRANSIT, CYCLE_NETWORK, HIKING, SEAMARKS, TREES, POIS, LABELS, BUILDINGS_3D, SHADOWS }

@Immutable
data class MapStyle(
    val theme: Theme = Theme.AUTO,
    val layers: Set<MapLayer> = setOf(MapLayer.POIS, MapLayer.LABELS, MapLayer.BUILDINGS_3D, MapLayer.SHADOWS, MapLayer.TREES),
    /** Preferred language for names (name:xx), null for local names. */
    val language: String? = null,
    /** Colours a little more subtle (calm) except close up, as the boards' Main shows. */
    val calm: Boolean = false,
)

/** How a marker is drawn. */
enum class MarkerStyle {
    /** A round pin with a symbol, pointing at its place. */
    PIN,
    /** A small dot with a symbol, for many places at once. */
    DOT,
    /** A brand's logo as the pin. */
    BRAND,
    /** A count of places close together. */
    CLUSTER,
    /** A chip with a symbol and a short value, e.g. "⚡ 150" or "★ 4.8". */
    CHIP,
}

/**
 * A thing on the map. [symbol] is an icon id from `:places` (Maki/Temaki/Atlas names, e.g. "restaurant", "cafe",
 * "charging_station"); [logo] a brand id from `:places` brands. [ring] draws a progress ring 0..1 around the pin
 * (how long a place is still open, a charger's free share); [badge] a small corner badge (a symbol id).
 */
@Immutable
data class Marker(
    val id: String,
    val at: LatLon,
    val symbol: String,
    val colour: Long = 0xFF14161B,
    val style: MarkerStyle = MarkerStyle.PIN,
    val label: String? = null,
    val value: String? = null,
    val logo: String? = null,
    val badge: String? = null,
    val ring: Float? = null,
    val count: Int? = null,
    val selected: Boolean = false,
    /** Higher wins when markers collide; losers fold into clusters or hide. */
    val priority: Int = 0,
    val place: Place? = null,
)

/** What a [MapLabel] names, which sets its look: the Main board's two map labels. */
enum class LabelKind {
    /** A water ("IJ"): italic serif 21 board px in #2F5A84 on a 10 px white glow at 85 %. */
    WATER,

    /** A quarter ("Jordaan"): italic serif 17 board px in #3A3F4B on white halos of 6 and 2 px. */
    AREA,
}

/**
 * A place's name over the map, centred on the first of its [points] that stands on screen clear of the map's chrome,
 * the markers and the other labels (centred on it, else above it or beside it): a quarter's one point, a large water's
 * points along it, the likeliest first. The board sets these over the engine's own small-capital canal names, which
 * they never repeat: only quarters and large waters are named here. Higher [priority] goes first.
 */
@Immutable
data class MapLabel(val id: String, val text: String, val kind: LabelKind, val points: List<LatLon>, val priority: Int = 0)

/** A route drawn on the map, with a white casing as on the boards. */
@Immutable
data class RouteLine(
    val id: String,
    val points: List<LatLon>,
    val colour: Long = 0xFF2E5BFF,
    val widthDp: Float = 6f,
    val casing: Long = 0xFFFFFFFF,
    val dashed: Boolean = false,
    /** An alternative: drawn quieter and under the main route. */
    val alternative: Boolean = false,
    /** Distance along the route already travelled, drawn faded. */
    val travelled: Double = 0.0,
    /** A chip on the line, e.g. "6′". */
    val label: String? = null,
)

/** The blue dot: where the phone is, how sure, and which way it faces. */
@Immutable
data class UserLocation(val at: LatLon, val accuracyM: Double, val heading: Double? = null, val speed: Double? = null)

/** A shaded circle, e.g. "within 200 m of a bike dock" in the query builder. */
@Immutable
data class AreaOverlay(val id: String, val centre: LatLon, val radiusM: Double, val fill: Long = 0x332E5BFF, val stroke: Long = 0xFF2E5BFF)

/**
 * The building the app asks about (FillIn's question, the board's look), the way or relation [id]: drawn plain (no
 * windows, no facade detail), its walls in one flat colour [wall] (0xRRGGBB: the board's grey before an answer, the
 * answer's colour as the board draws it after), and [hatched] while the question is open. The core draws it so
 * (MapCore.ask); today's map does not.
 */
@Immutable
data class AskedBuilding(val id: app.atlas.model.OsmId, val wall: Int, val hatched: Boolean)

@Immutable
data class MapOverlays(
    val markers: List<Marker> = emptyList(),
    /** Names over the map, as the Main board sets "IJ" and "Jordaan" over the engine's frame. */
    val labels: List<MapLabel> = emptyList(),
    /** Where the screen's own chrome stands (boxes in dp from the map's top left): no label goes under it. */
    val labelKeepOut: List<androidx.compose.ui.geometry.Rect> = emptyList(),
    val routes: List<RouteLine> = emptyList(),
    val location: UserLocation? = null,
    val areas: List<AreaOverlay> = emptyList(),
    /** Shows a two-finger measuring tape when two fingers rest on the map. */
    val measure: Boolean = true,
    /** The building the app asks about, drawn as asked where the core draws the map. */
    val asked: AskedBuilding? = null,
) {
    /** Whether something lies on the ground under the symbols (a route, an area), which the canvas draws over the map. */
    val onGround: Boolean get() = routes.isNotEmpty() || areas.isNotEmpty()
}

/**
 * The building under a point of the map as the core drew it ([id]: the building, an outline or the one a part stands
 * in; [part] the part met, 0 for the outline itself), where the finger's ray met it ([at]), and the face it met as the
 * building's geometry draws it: its roof ([roof]), else a wall (a gable among them); that face's slot word ([slot]: the
 * tiles' roof_slot or wall_slot, which palette.PaletteFace reads) and its base colour ([rgb], 0xRRGGBB before light).
 */
@Immutable
data class MapPick(
    val id: app.atlas.model.OsmId,
    val part: Long,
    val at: LatLon,
    val roof: Boolean = false,
    val slot: Int = 0,
    val rgb: Int = 0,
) {
    companion object {
        /** The core's line of a pick (`MapCore.pick`: "way 123 0 52.3745 4.8838 roof 80 11425599"), or null for none. */
        fun parse(line: String): MapPick? {
            val parts = line.split(' ')
            if (parts.size != 8) return null
            val id = app.atlas.model.OsmId.parse(parts[0] + " " + parts[1]) ?: return null
            val lat = parts[3].toDoubleOrNull() ?: return null
            val lon = parts[4].toDoubleOrNull() ?: return null
            val roof = when (parts[5]) {
                "roof" -> true
                "wall" -> false
                else -> return null
            }
            val slot = parts[6].toIntOrNull() ?: return null
            val rgb = parts[7].toIntOrNull() ?: return null
            return MapPick(id, parts[2].toLongOrNull() ?: 0L, LatLon(lat, lon), roof, slot, rgb)
        }
    }
}

/**
 * What a point of the map shows of the ground as the core drew it (`MapCore.pickGround`): what the finger's ray met
 * ([what]), the slot of the user's palette that paints it ([slot]: palette.Slot's code, 0 where none does: sand, rock,
 * a cycleway's red) and where ([at]).
 */
@Immutable
data class GroundPick(val what: What, val slot: Int, val at: LatLon) {
    /** The bare ground under everything, a fill of the ground (a land's, an area's, a road's), the water, a quay's wall. */
    enum class What { PLATE, FILL, WATER, QUAY }

    companion object {
        /** The core's line of a ground pick (`MapCore.pickGround`: "water 16 52.3745 4.8838"), or null for none. */
        fun parse(line: String): GroundPick? {
            val parts = line.split(' ')
            if (parts.size != 4) return null
            val what = when (parts[0]) {
                "plate" -> What.PLATE
                "fill" -> What.FILL
                "water" -> What.WATER
                "quay" -> What.QUAY
                else -> return null
            }
            val slot = parts[1].toIntOrNull() ?: return null
            val lat = parts[2].toDoubleOrNull() ?: return null
            val lon = parts[3].toDoubleOrNull() ?: return null
            return GroundPick(what, slot, LatLon(lat, lon))
        }
    }
}

/** What a tap found. */
sealed interface MapHit {
    val at: LatLon
    data class OnMarker(val marker: Marker, override val at: LatLon) : MapHit
    /** A place drawn by the engine itself (a point of interest, a building). */
    data class OnFeature(val place: Place, override val at: LatLon) : MapHit
    data class Empty(override val at: LatLon) : MapHit
}
