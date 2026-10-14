package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.atlas.R
import app.atlas.geo.BBox
import app.atlas.geo.LatLon
import app.atlas.mapview.AtlasMap
import app.atlas.mapview.CameraPosition
import app.atlas.mapview.MapHit
import app.atlas.mapview.MapOverlays
import app.atlas.mapview.MapState
import app.atlas.mapview.Marker
import app.atlas.mapview.UserLocation
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.Sheet
import app.atlas.ui.kit.SheetStop
import app.atlas.ui.kit.SymbolButton
import app.atlas.ui.kit.rememberSheetState
import app.atlas.ui.kit.softShadow

/**
 * Places on the map with their list in a sheet, as "Every pharmacy on the map" and "All 8 on the map" show them: the
 * map fitted to [fit] (and here), the [markers] (a tap opens the place), the back button and a [title] pill over the
 * map, and the [sheet] content below.
 */
@Composable
internal fun OnTheMap(
    here: LatLon,
    markers: List<Marker>,
    fit: List<LatLon>,
    onBack: () -> Unit,
    onPlace: (Place) -> Unit,
    onLongPress: (LatLon) -> Unit,
    title: @Composable RowScope.() -> Unit,
    sheet: @Composable ColumnScope.() -> Unit,
) {
    val services = LocalServices.current
    val c = Atlas.colors
    val tiles by services.tiles.collectAsStateWithLifecycle()
    val map = remember { MapState(CameraPosition(here, 15.5)) }
    val sheetState = rememberSheetState(SheetStop.HALF, setOf(SheetStop.PEEK, SheetStop.HALF, SheetStop.FULL))
    LaunchedEffect(fit) {
        // The camera can be fitted once the view knows its size: after its first frame.
        snapshotFlow { map.visibleBounds }.first { it != null }
        BBox.of(fit + here)?.let { b -> runCatching { map.fit(b.buffer(60.0), 36) } }
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(c.paper)) {
        AtlasMap(
            state = map,
            tiles = tiles,
            modifier = Modifier.fillMaxSize(),
            overlays = MapOverlays(markers = markers, location = UserLocation(here, 15.0), measure = false),
            contentPadding = PaddingValues(top = 84.dp, bottom = maxHeight * HALF),
            onTap = { hit ->
                when (hit) {
                    is MapHit.OnMarker -> hit.marker.place
                    is MapHit.OnFeature -> hit.place
                    is MapHit.Empty -> null
                }?.let(onPlace)
            },
            onLongPress = onLongPress,
        )
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolButton("ui-chevron-left", stringResource(R.string.kit_back), onBack, size = 52.dp)
            Row(
                Modifier.heightIn(min = 52.dp).softShadow(CircleShape, c.shadow, 8.dp).clip(CircleShape).background(c.card)
                    .padding(start = 8.dp, end = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = title,
            )
        }
        Sheet(sheetState, peekHeight = 132.dp, halfFraction = HALF, fullTopGap = 96.dp, content = sheet)
    }
}

/** How much of the screen the sheet takes at half. */
private const val HALF = 0.5f
