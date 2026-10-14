package app.atlas.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Categories
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.rememberHere
import java.util.Locale

/** Every place of a category around you. Placeholder for the search package. */
@Composable
fun CategoryScreen(args: Dest.Category, nav: Navigator) {
    val lang = remember { Locale.getDefault().language }
    val cat = remember(args.id) { Categories.byId(args.id) }
    val here = rememberHere()
    AtlasScreen(cat?.name(lang) ?: stringResource(R.string.category_title), onBack = { nav.back() }) {
        NearbyList(args.id, here, onPlace = { nav.go(Dest.Place.of(it)) }, emptySymbol = cat?.symbol ?: "maki-marker")
    }
}
