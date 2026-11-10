package app.atlas.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.nav.Dest
import app.atlas.nav.Navigator
import app.atlas.places.CategoryNode
import app.atlas.places.CategoryTree

/**
 * What a kind is: its name and where it stands, the tags it is mapped with; for a place, whether its hours are its
 * brand's or old, and the way to correct them: What's here on the place, where a note leaves the right hours for the
 * mappers nearby (until 4 November this led to the facade and roof questions, which ask nothing of hours). Where
 * [inRow] is known, the star at its corner puts the kind in the home's own row or takes it out (the owner, 9 November:
 * "Yes to the long press"): a card of the grid, a kind's chip or a place's row sends its kind there without opening it.
 */
@Composable
internal fun ExplainDialog(
    e: Explained,
    lang: String,
    nav: Navigator,
    onAll: () -> Unit,
    onDismiss: () -> Unit,
    inRow: Boolean? = null,
    onStar: (Boolean) -> Unit = {},
) {
    val c = indexColors()
    val path = remember(e.node) { CategoryTree.pathOf(e.node.id).dropLast(1).joinToString(" › ") { it.name(lang) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.paper,
        icon = {
            Box(Modifier.fillMaxWidth()) {
                KindPin(e.node.symbol, e.node.colour, Modifier.align(Alignment.Center), size = 48.dp)
                if (inRow != null) Box(Modifier.align(Alignment.CenterEnd)) { RowStar(inRow, onStar, tint = c.ink) }
            }
        },
        title = { Text(e.node.name(lang), style = indexTitle(19f), color = c.ink) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (path.isNotEmpty()) Text(stringResource(R.string.find_explain_where) + " " + path, style = INDEX_META, color = c.soft)
                Text(stringResource(R.string.find_explain_tags), style = INDEX_META, color = c.soft)
                Text(e.node.filter.toText(), style = boardText(12.5f, 450, 17f, family = GEIST_MONO), color = c.ink, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (e.brandHours) Text(stringResource(R.string.find_brand_hours), style = INDEX_META, color = c.soon)
                if (e.kindHours) Text(stringResource(R.string.relax_kind_hours), style = INDEX_META, color = c.soon)
                if (e.stale) Text("⚠ " + stringResource(R.string.find_stale), style = INDEX_META, color = c.soon)
                val place = e.place
                val id = place?.id
                if (place != null && id != null) {
                    TextButton(onClick = { onDismiss(); nav.go(Dest.WhatsHere(place.location.lat, place.location.lon, id.toString())) }) {
                        Text(stringResource(R.string.find_explain_hours), color = c.ink)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onAll() }) { Text(stringResource(R.string.find_explain_all), color = c.ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.find_explain_ok), color = c.soft) } },
    )
}

/** The kinds that have a screen of their own in Atlas, and that screen: the search leads there in one tap. */
internal object AtlasScreens {
    fun of(node: CategoryNode): Pair<Dest, Int>? {
        presetScreen(node.preset?.id)?.let { return it }
        return when (node.category.id) {
            "eat", "restaurants" -> Dest.Eat() to R.string.find_screen_eat
            "drinks" -> Dest.Eat("bar") to R.string.find_screen_eat
            "groceries", "food_shops" -> Dest.Groceries() to R.string.find_screen_groceries
            // the high street's board is of every shop: the shops' own screen, no single kind's (the owner, 6 November:
            // "Why is there an arrow up to groceries at bikes?")
            "shops" -> Dest.Groceries("highstreet") to R.string.find_screen_highstreet
            "shopping" -> (Dest.Groceries("highstreet") to R.string.find_screen_highstreet).takeIf { node.preset == null }
            "recycling" -> Dest.Groceries("bins") to R.string.shop_bins
            // the fuel family holds both the pumps' screen and the chargers': each of its kinds leads to its own
            "charging" -> Dest.Charge() to R.string.find_screen_charge
            "fuel" -> Dest.Pump() to R.string.find_screen_fuel
            "toilets" -> Dest.Needs("toilet") to R.string.find_screen_needs
            "drinking_water" -> Dest.Needs("water") to R.string.find_screen_needs
            "water", "swim", "beach" -> Dest.Swim() to R.string.find_screen_swim
            "sauna_spa" -> Dest.Relax() to R.string.find_screen_relax
            "parks" -> Dest.Parkland() to R.string.find_screen_parks
            "nature", "nature_reserve" -> Dest.Nature() to R.string.find_screen_nature
            "nature_spots", "viewpoint" -> Dest.Peaks() to R.string.find_screen_peaks
            "picnic" -> Dest.Picnic() to R.string.find_screen_picnic
            "rentals", "bike_parking" -> Dest.Twowheel() to R.string.find_screen_twowheel
            else -> null
        }
    }

    private fun presetScreen(preset: String?): Pair<Dest, Int>? = when (preset) {
        null -> null
        "amenity/cafe" -> Dest.Eat("cafe") to R.string.find_screen_eat
        "amenity/bar", "amenity/pub", "amenity/biergarten" -> Dest.Eat("bar") to R.string.find_screen_eat
        "amenity/ice_cream", "shop/confectionery", "shop/pastry" -> Dest.Eat("sweets") to R.string.find_screen_eat
        // a bike shop mends bikes: the two wheels' board counts it among its repairs
        "amenity/bicycle_parking", "amenity/bicycle_rental", "amenity/bicycle_repair_station", "shop/bicycle" -> Dest.Twowheel() to R.string.find_screen_twowheel
        else -> null
    }
}
