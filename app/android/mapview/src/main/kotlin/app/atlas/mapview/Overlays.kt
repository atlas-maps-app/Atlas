package app.atlas.mapview

import androidx.compose.runtime.Immutable
import app.atlas.geo.LatLon
import app.atlas.model.Place

/** The engine's looks. AUTO follows the real daylight at the camera's place. */
enum class Theme { AUTO, DAY, NIGHT, CHART, GRAY }

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

@Immutable
data class MapOverlays(
    val markers: List<Marker> = emptyList(),
    val routes: List<RouteLine> = emptyList(),
    val location: UserLocation? = null,
    val areas: List<AreaOverlay> = emptyList(),
    /** Shows a two-finger measuring tape when two fingers rest on the map. */
    val measure: Boolean = true,
)

/** What a tap found. */
sealed interface MapHit {
    val at: LatLon
    data class OnMarker(val marker: Marker, override val at: LatLon) : MapHit
    /** A place drawn by the engine itself (a POI from the tiles, a building). */
    data class OnFeature(val place: Place, override val at: LatLon) : MapHit
    data class Empty(override val at: LatLon) : MapHit
}
