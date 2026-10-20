package app.atlas.ui.go

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.mapview.StageMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.Follow
import app.atlas.mapview.MapLayer
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.MapStyle
import app.atlas.mapview.Marker
import app.atlas.mapview.MarkerStyle
import app.atlas.mapview.RouteLine
import app.atlas.mapview.Theme
import app.atlas.mapview.UserLocation
import app.atlas.nav.LocalServices

/**
 * The map under a guided trip: following the course, tilted, with the route drawn and the part already travelled
 * faded, the destination's flag and you on it. [theme] is the look (night for driving after dark, the nautical chart
 * on the water); [routeColour] the line, [dashed] for a course over water; [markers] what else to show (bridges); [door] marks the end as a door, for walking.
 */
@Composable
fun GuidanceMap(
    view: NavView,
    modifier: Modifier = Modifier,
    theme: Theme = Theme.AUTO,
    routeColour: Long = 0xFF2E5BFF,
    zoom: Double = 17.2,
    pitch: Double = 52.0,
    padding: PaddingValues = PaddingValues(),
    follow: Boolean = true,
    dashed: Boolean = false,
    markers: List<Marker> = emptyList(),
    door: Boolean = false,
    /** What shows under the map until its first frame: the guidance screen's paper. */
    background: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) {
    val services = LocalServices.current
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val s = view.state
    val start = s?.snapped ?: view.route.points.first()
    val bearing = s?.routeBearing ?: view.route.line.bearingAt(0.0)
    val map = remember { MapState(CameraPosition(start, zoom, bearing, pitch)).also { it.follow = Follow.COURSE } }
    LaunchedEffect(follow) { map.follow = if (follow) Follow.COURSE else Follow.NONE }
    LaunchedEffect(follow, view.route) {
        if (!follow) view.route.line.bbox?.let { map.fit(it, 64) }
    }
    val overlays = MapOverlays(
        routes = listOf(RouteLine("route", view.route.points, colour = routeColour, widthDp = if (dashed) 5f else 9f, dashed = dashed, travelled = view.travelled)),
        markers = markers + Marker("to", view.route.points.last(), if (door) "ui-door" else "ui-flag", 0xFF14161B, MarkerStyle.PIN, priority = 100),
        location = s?.let { UserLocation(it.snapped, 5.0, it.routeBearing, it.speed) },
        measure = false,
    )
    StageMap(
        state = map,
        tiles = tiles,
        modifier = modifier,
        background = background,
        style = MapStyle(theme = theme, layers = setOf(MapLayer.LABELS, MapLayer.BUILDINGS_3D, MapLayer.TREES) + if (theme == Theme.CHART) setOf(MapLayer.SEAMARKS) else emptySet()),
        overlays = overlays,
        contentPadding = padding,
    )
}
