package app.atlas.ui.shop

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.rememberHere

/** Groceries around you. Placeholder for the shop package's pantry. */
@Composable
fun GroceriesScreen(args: Dest.Groceries, nav: Navigator) {
    val here = rememberHere()
    AtlasScreen(stringResource(R.string.shop_groceries), onBack = { nav.back() }) {
        NearbyList("groceries", here, onPlace = { nav.go(Dest.Place.of(it)) }, emptySymbol = "ui-basket")
    }
}
