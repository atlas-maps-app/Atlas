package app.atlas.ui.shop

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.NearbyList
import app.atlas.ui.kit.rememberHere

/**
 * Groceries: the pantry (no kind), a shelf's aisle ("super", "bakery", …), or one of the tools ("list", "compare",
 * "find", "errands", "help", "highstreet", "bins", "drop", "stroll", "refill"). An unknown kind opens the pantry.
 */
@Composable
fun GroceriesScreen(args: Dest.Groceries, nav: Navigator) {
    val kind = args.kind
    when {
        kind == null -> PantryScreen(nav)
        kind == "list" -> ListScreen(nav)
        kind == "compare" -> CompareScreen(nav)
        kind == "refill" || kind == "refill:return" -> RefillScreen(nav, if (kind == "refill:return") RefillKind.RETURN else null)
        kind == "bins" -> BinsScreen(nav)
        kind == "stroll" -> StrollScreen(nav)
        kind == "drop" || kind.startsWith("drop:") -> DropScreen(kind.substringAfter("drop:", ""), nav)
        kind == "errands" -> ErrandsScreen(nav)
        kind == "highstreet" -> HighStreetScreen(nav)
        kind.startsWith("street:") -> {
            val parts = kind.split(":")
            val family = Family.entries.firstOrNull { it.name.equals(parts.getOrNull(1), ignoreCase = true) } ?: Family.FASHION
            AislesScreen(family.spec(), nav, indie = parts.getOrNull(2) == "indie")
        }
        kind == "help" || Shelf.of(kind) == Shelf.SHARE -> HelpScreen(nav)
        kind == "find" || kind.startsWith("find:") -> FindScreen(kind.substringAfter("find:", ""), nav)
        Shelf.of(kind) != null && Shelf.of(kind) != Shelf.REFILL -> AislesScreen(Shelf.of(kind)!!.spec(), nav)
        else -> {
            val here = rememberHere()
            AtlasScreen(stringResource(R.string.shop_groceries), onBack = { nav.back() }) {
                NearbyList("groceries", here, onPlace = { nav.go(Dest.Place.of(it)) }, emptySymbol = "ui-basket")
            }
        }
    }
}
