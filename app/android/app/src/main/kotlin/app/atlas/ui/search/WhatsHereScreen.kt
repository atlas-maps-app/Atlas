package app.atlas.ui.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import app.atlas.Services
import app.atlas.geo.BBox
import app.atlas.geo.Geo
import app.atlas.geo.LatLon
import app.atlas.model.Place
import app.atlas.nav.LocalServices
import app.atlas.places.TagFilter
import app.atlas.services.TilePlaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.geo.CoordinateFormat
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.StateMessage

/** What is at a point: its coordinates in every form, and what is around. Placeholder for the search package. */
@Composable
fun WhatsHereScreen(args: Dest.WhatsHere, nav: Navigator) {
    val services = LocalServices.current
    val p = args.at
    AtlasScreen(stringResource(R.string.whatshere_title), onBack = { nav.back() }, italic = true) {
        Kicker(stringResource(R.string.whatshere_coordinates))
        Card {
            Text(CoordinateFormat.decimal(p), style = Atlas.type.mono)
            Text(CoordinateFormat.dms(p), style = Atlas.type.mono)
            Text(CoordinateFormat.plusCode(p), style = Atlas.type.mono)
            CoordinateFormat.utm(p)?.let { Text(it, style = Atlas.type.mono) }
        }
        Kicker(stringResource(R.string.whatshere_nearby))
        val near by produceState<List<Place>?>(null, p) { value = namedAround(services, p, 150.0, 12) }
        when (val list = near) {
            null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = Atlas.colors.inkSoft)
            else -> if (list.isEmpty()) StateMessage("ui-map-pin", stringResource(R.string.brands_none))
            else Card(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                BranchRows(list, p, onPlace = { services.remember(it); nav.go(Dest.Place.of(it)) })
            }
        }
    }
}

/**
 * The named things at and around [p], nearest first: from a downloaded region's place store (measured to their
 * outlines, so the building you pressed on comes first), else from the map tiles' points of interest.
 */
private suspend fun namedAround(services: Services, p: LatLon, radiusM: Double, limit: Int): List<Place> = withContext(Dispatchers.Default) {
    val store = services.placeStores
    if (store.size > 0) {
        // Places Atlas lists (not streets, districts or signs), measured to their outlines; a vast area whose middle is
        // far away (the whole canal ring) is not "here".
        store.inBox(BBox.around(p, radiusM))
            .filter { it.category != null && it.tags["name"] != null && it.distanceTo(p) <= radiusM && Geo.distance(p, it.location) <= 2 * radiusM }
            .sortedBy { it.distanceTo(p) }.take(limit).map { it.place }
    } else {
        services.tiles.value?.let { runCatching { TilePlaces.near(it, p, radiusM, TagFilter.Has("name"), limit) }.getOrNull() }.orEmpty()
    }
}
