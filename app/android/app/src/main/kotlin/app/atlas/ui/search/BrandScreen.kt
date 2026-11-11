package app.atlas.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.StateMessage

/**
 * One brand's page (the owner, 6 November: "Multiple layouts, themed brand detail overview page that appears when you
 * clearly search for a specific brand and accessible from the brands menu", then "details about the brand, everything
 * thats useful"): the search over the map opened on the chain ([SearchOverMap]), its band in the brand's own colours
 * ([BrandBand]), what is known of it ([BrandDetails]) and its branches near as rows, as cards or as its signs on the
 * map. The Brands board, a branch's "Part of" and a search for its very name all open it; back leaves it.
 */
@Composable
fun BrandScreen(args: Dest.Brand, nav: Navigator) {
    val brand = remember(args.id) { Brands.byId(args.id) }
    if (brand == null) {
        AtlasScreen(args.id, onBack = { nav.back() }) { StateMessage("ui-store", stringResource(R.string.brand_unknown), text = args.id) }
        return
    }
    SearchOverMap(nav, brand = brand)
}
