package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brands
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.rememberHere

/** One brand and its branches around you, nearest first. Placeholder for the search package's brand sheet. */
@Composable
fun BrandScreen(args: Dest.Brand, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    val brand = remember(args.id) { Brands.byId(args.id) }
    val branches by produceState<BrandBranches?>(null, args.id, here) {
        value = BrandsAround.near(services, here, BRANDS_RADIUS_M * 2).chains.firstOrNull { it.brand.id == args.id }
            ?: brand?.let { BrandBranches(it, emptyList()) }
    }
    AtlasScreen(brand?.name ?: args.id, onBack = { nav.back() }) {
        if (brand == null) {
            StateMessage("ui-store", args.id)
            return@AtlasScreen
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(brand, 56.dp)
            val n = branches?.branches?.size
            Column {
                Text(
                    if (n == null) stringResource(R.string.kit_loading) else pluralStringResource(R.plurals.brand_branches_near, n, n),
                    style = Atlas.type.bodyStrong,
                )
            }
        }
        val list = branches?.branches.orEmpty()
        if (branches != null && list.isEmpty()) StateMessage("ui-store", stringResource(R.string.brands_none), tint = Atlas.colors.inkSoft)
        if (list.isNotEmpty()) {
            Card(padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                BranchRows(list.take(40), here, onPlace = { services.remember(it); nav.go(Dest.Place.of(it)) })
            }
        }
    }
}
