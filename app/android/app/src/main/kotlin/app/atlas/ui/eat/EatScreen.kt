package app.atlas.ui.eat

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.rememberHere

/** Eat & drink: the places to eat around you. Placeholder for the eat package's table of objects. */
@Composable
fun EatScreen(args: Dest.Eat, nav: Navigator) {
    val here = rememberHere()
    AtlasScreen(stringResource(R.string.eat_title), onBack = { nav.back() }) {
        NearbyList("eat", here, onPlace = { nav.go(Dest.Place.of(it)) }, emptySymbol = "ui-utensils")
    }
}
