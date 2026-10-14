package app.atlas.ui.eat

import androidx.compose.runtime.Composable
import app.atlas.nav.Dest
import app.atlas.nav.Navigator

/**
 * Eat & drink. [Dest.Eat.kind] picks the screen: none for the table (Menus); "carte", "carte:<cuisine>" for one
 * cuisine's places; "order" the guest check; "bar"; "cafe"; "late"; "sweets"; "market"; "map" the eat map; and a
 * cuisine world by its plank ("ramen", "sushi", "izakaya", "soba", "japanese") or as "world:<id>".
 */
@Composable
fun EatScreen(args: Dest.Eat, nav: Navigator) {
    val kind = args.kind?.trim()?.lowercase().orEmpty()
    when {
        kind.isEmpty() -> MenusScreen(nav)
        kind == "carte" -> CarteScreen(nav)
        kind.startsWith("carte:") -> CuisinePage(kind.removePrefix("carte:"), nav)
        kind == "order" -> OrderScreen(nav)
        kind == "bar" -> BarScreen(nav)
        kind == "cafe" -> CafeScreen(nav)
        kind == "late" -> LateScreen(nav)
        kind == "sweets" -> SweetsScreen(nav)
        kind == "market" -> MarketScreen(nav)
        kind == "map" -> EatMapScreen(nav)
        else -> CuisineWorlds.route(kind)?.let { (world, plank) -> WorldScreen(world, plank, nav) } ?: MenusScreen(nav)
    }
}
