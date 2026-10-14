package app.atlas.ui.shop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.geo.Geo
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Compass
import app.atlas.ui.kit.Kicker
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.WalkTime
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.shortDistance
import app.atlas.ui.kit.walkMinutes

/** The OSM category each quick need looks in. */
internal fun needCategory(kind: String): String = when (kind) {
    "water" -> "drinking_water"
    "toilet" -> "toilets"
    else -> "food_shops"
}

/**
 * A need, pointed at: the brass compass towards the nearest one, its distance and walk, then the others. Placeholder
 * for the shop package's "point me" screens (the needle follows the map's north until the rotation sensor is wired).
 */
@Composable
fun NeedsScreen(args: Dest.Needs, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val category = needCategory(args.kind)
    var nearest by remember(args.kind) { mutableStateOf<Place?>(null) }
    LaunchedEffect(args.kind, here) {
        runCatching { services.places.nearby(category, here, 1_500.0, 1).collect { r -> r.places.firstOrNull()?.let { nearest = it } } }
    }
    val title = stringResource(
        when (args.kind) {
            "water" -> R.string.shop_need_water
            "toilet" -> R.string.shop_need_toilet
            else -> R.string.shop_need_food
        },
    )
    AtlasScreen(title, onBack = { nav.back() }) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val target = nearest
            val bearing = target?.let { Geo.bearing(here, it.location).toFloat() }
            Compass(heading = 0f, size = 220.dp, target = bearing, contentDescription = target?.let { shortDistance(Geo.distance(here, it.location)) })
            if (target != null) {
                val d = Geo.distance(here, target.location)
                Text(walkMinutes(WalkTime.seconds(d)), style = Atlas.type.numeral)
                Text(listOfNotNull(target.name(), shortDistance(d)).joinToString(" · "), style = Atlas.type.body, color = Atlas.colors.inkSoft)
            }
        }
        Kicker(stringResource(R.string.shop_nearest))
        NearbyList(category, here, onPlace = { nav.go(Dest.Place.of(it)) }, radiusM = 1_500.0, limit = 10)
    }
}
