package app.atlas.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.atlas.R
import app.atlas.model.Place
import app.atlas.nav.Dest
import app.atlas.nav.LocalServices
import app.atlas.nav.Navigator
import app.atlas.places.Brand
import app.atlas.places.Categories
import app.atlas.services.LayeredPlaceFinder
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasScreen
import app.atlas.ui.kit.Card
import app.atlas.ui.kit.Hairline
import app.atlas.ui.kit.PlaceRow
import app.atlas.ui.kit.PlaceSummary
import app.atlas.ui.kit.Segment
import app.atlas.ui.kit.SegmentedPill
import app.atlas.ui.kit.StateMessage
import app.atlas.ui.kit.Symbol
import app.atlas.ui.kit.contentColourFor
import app.atlas.ui.kit.openLine
import app.atlas.ui.kit.rememberHere
import app.atlas.ui.kit.rememberNow
import java.util.Locale

/** How far around you Brands looks. */
internal const val BRANDS_RADIUS_M = 1_200.0

/**
 * Brands around you: "Chains N | Independent M", each chain a logo tile with its branch count, the independents as a
 * list. Placeholder for the search package's Brands board.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrandsScreen(args: Dest.Brands, nav: Navigator) {
    val services = LocalServices.current
    val here = rememberHere()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val filter = remember(args.category) { args.category?.let { Categories.byId(it) }?.let { LayeredPlaceFinder.filterOf(it) } }
    val nearby by produceState<BrandsNearby?>(null, here, filter) {
        val all = BrandsAround.near(services, here, BRANDS_RADIUS_M)
        value = if (filter == null) all else BrandsAround.split(
            (all.chains.flatMap { it.branches } + all.independents).filter { filter.matches(it.tags) }, here, BRANDS_RADIUS_M, all.origin,
        )
    }
    AtlasScreen(stringResource(R.string.brands_title), onBack = { nav.back() }) {
        val n = nearby
        SegmentedPill(
            listOf(
                Segment(stringResource(R.string.brands_chains), "ui-link", n?.chains?.size),
                Segment(stringResource(R.string.brands_independent), "ui-store", n?.independents?.size),
            ),
            tab, { tab = it },
        )
        when {
            n == null -> Text(stringResource(R.string.kit_loading), style = Atlas.type.caption, color = Atlas.colors.inkSoft)
            tab == 0 && n.chains.isEmpty() || tab == 1 && n.independents.isEmpty() ->
                StateMessage("ui-store", stringResource(R.string.brands_none))
            tab == 0 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 3) {
                for (chain in n.chains.take(30)) {
                    BrandTile(chain.brand, chain.branches.size, Modifier.weight(1f)) { nav.go(Dest.Brand(chain.brand.id)) }
                }
            }
            else -> Card(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                BranchRows(n.independents.take(40), here, onPlace = { services.remember(it); nav.go(Dest.Place.of(it)) })
            }
        }
    }
}

/** A chain's tile: its logo (or a monogram in its colour), its name, and how many branches are near. */
@Composable
internal fun BrandTile(brand: Brand, branches: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val description = stringResource(R.string.brands_branches, brand.name, branches)
    Column(
        modifier
            .aspectRatio(0.92f)
            .clip(RoundedCornerShape(20.dp))
            .background(Atlas.colors.card)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BrandLogo(brand, 48.dp)
        Text(brand.name, Modifier.padding(top = 8.dp), style = Atlas.type.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(branches.toString(), style = Atlas.type.numeralSmall, color = Atlas.colors.inkSoft)
    }
}

/** A brand's logo on its colour, or its initial when it has no logo. */
@Composable
internal fun BrandLogo(brand: Brand, size: Dp) {
    val bg = brand.colour?.let { Color(it) } ?: Atlas.colors.pill
    val fg = brand.textColour?.let { Color(it) } ?: contentColourFor(bg)
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(bg), contentAlignment = Alignment.Center) {
        val slug = brand.logoSlug
        if (slug != null) Symbol("logo-$slug", tint = fg, size = size * 0.62f)
        else Text(brand.name.take(1).uppercase(), style = Atlas.type.numeralSmall, color = fg)
    }
}

/** Places as list rows, with their open status and walking minutes from [here]. */
@Composable
internal fun BranchRows(places: List<Place>, here: app.atlas.geo.LatLon, onPlace: (Place) -> Unit) {
    val lang = remember { Locale.getDefault().language }
    val now = rememberNow(places)
    places.forEachIndexed { i, p ->
        val s = remember(p, here, now) { PlaceSummary.of(p, here, lang, now) }
        val open = openLine(s, now)
        PlaceRow(
            name = s.name,
            symbol = s.symbol,
            colour = Color(s.colour),
            meta = listOfNotNull(open?.first, s.kind, s.place.tags["addr:street"]).joinToString(" · ").ifEmpty { null },
            metaColour = open?.second ?: Atlas.colors.inkSoft,
            trailing = s.walk,
            onClick = { onPlace(p) },
        )
        if (i < places.lastIndex) Hairline()
    }
    Spacer(Modifier.size(4.dp))
}
