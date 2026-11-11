package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Category
import app.atlas.services.PlaceOrigin
import app.atlas.ui.kit.Atlas

/**
 * Every place of a kind around you, on the map and in the list (decision 12): the one search over the map, inside the
 * kind [Dest.Category.id] (an Atlas category or group id, or a node of the tree), with its narrower kinds and filters.
 */
@Composable
fun CategoryScreen(args: Dest.Category, nav: Navigator) = SearchOverMap(nav, kind = args.id)

/** Where a list came from, said in one grey line: the region on the phone, the map's data on the phone, or online. */
@Composable
internal fun OriginLine(origin: PlaceOrigin, error: String?) {
    val c = Atlas.colors
    val (symbol, text) = when (origin) {
        PlaceOrigin.STORE -> "ui-smartphone" to stringResource(R.string.origin_store)
        PlaceOrigin.PACK -> "ui-layers" to stringResource(R.string.origin_pack)
        PlaceOrigin.ONLINE -> "ui-cloud" to stringResource(R.string.origin_online)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        app.atlas.ui.kit.Symbol(if (error != null) "ui-cloud-off" else symbol, tint = if (error != null) c.caution else c.inkFaint, size = 14.dp)
        Text(if (error != null) stringResource(R.string.origin_failed, text) else text, style = Atlas.type.caption, color = c.inkFaint, maxLines = 2)
    }
}
